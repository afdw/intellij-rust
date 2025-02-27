/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

@file:Suppress("LoopToCallChain")

package org.rust.lang.core.resolve

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.util.AutoInjectedCrates.CORE
import org.rust.cargo.util.AutoInjectedCrates.STD
import org.rust.ide.injected.isDoctestInjection
import org.rust.lang.RsConstants
import org.rust.lang.core.macros.*
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsFile.Attributes.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.NameResolutionTestmarks.crateRootModule
import org.rust.lang.core.resolve.NameResolutionTestmarks.missingMacroUse
import org.rust.lang.core.resolve.NameResolutionTestmarks.modDeclExplicitPathInInlineModule
import org.rust.lang.core.resolve.NameResolutionTestmarks.modDeclExplicitPathInNonInlineModule
import org.rust.lang.core.resolve.NameResolutionTestmarks.modRsFile
import org.rust.lang.core.resolve.NameResolutionTestmarks.otherVersionOfSameCrate
import org.rust.lang.core.resolve.NameResolutionTestmarks.selfInGroup
import org.rust.lang.core.resolve.indexes.RsLangItemIndex
import org.rust.lang.core.resolve.indexes.RsMacroIndex
import org.rust.lang.core.resolve.ref.DotExprResolveVariant
import org.rust.lang.core.resolve.ref.FieldResolveVariant
import org.rust.lang.core.resolve.ref.MethodResolveVariant
import org.rust.lang.core.resolve.ref.deepResolve
import org.rust.lang.core.stubs.index.RsNamedElementIndex
import org.rust.lang.core.types.*
import org.rust.lang.core.types.infer.foldTyTypeParameterWith
import org.rust.lang.core.types.infer.substitute
import org.rust.lang.core.types.ty.*
import org.rust.openapiext.Testmark
import org.rust.openapiext.hitOnFalse
import org.rust.openapiext.isUnitTestMode
import org.rust.openapiext.toPsiFile
import org.rust.stdext.buildList

// IntelliJ Rust name resolution algorithm.
// Collapse all methods (`ctrl shift -`) to get a bird's eye view.
//
// The entry point is
// `process~X~ResolveVariants(x: RsReferenceElement, processor: RsResolveProcessor)`
// family of methods.
//
// Conceptually, each of these methods returns a sequence of `RsNameElement`s
// visible at the reference location. During completion, all of them are presented
// as completion variants, and during resolve only the one with the name matching
// the reference is selected.
//
// Instead of Kotlin `Sequence`'s, a callback (`RsResolveProcessor`) is used, because
// it gives **much** nicer stacktraces (we used to have `Sequence` here some time ago).
//
// Instead of using `RsNameElement` directly, `RsResolveProcessor` operates on `ScopeEntry`s.
// `ScopeEntry` allows to change the effective name of an element (for aliases) and to retrieve
// the actual element lazily.
//
// The `process~PsiElement~Declarations` family of methods list name elements belonging
// to a particular element (for example, variants of an enum).
//
// Technicalities:
//
//   * We can get into infinite loop during name resolution. This is handled by
//     `RsReferenceCached`.
//   * The results of name resolution are cached and invalidated on every code change.
//     Caching also is handled by `RsReferenceCached`.
//   * Ideally, all of the methods except for `processLexicalDeclarations` should operate on stubs only.
//   * Rust uses two namespaces for declarations ("types" and "values"). The necessary namespace is
//     determined by the syntactic position of the reference in `processResolveVariants` function and
//     is passed down to the `processDeclarations` functions.
//   * Instead of `getParent` we use `getContext` here. This trick allows for funny things like creating
//     a code fragment in a temporary file and attaching it to some existing file. See the usages of
//     [RsCodeFragmentFactory]

private val LOG = Logger.getInstance("org.rust.lang.core.resolve.NameResolution")

fun processDotExprResolveVariants(
    lookup: ImplLookup,
    receiverType: Ty,
    processor: (DotExprResolveVariant) -> Boolean
): Boolean {
    if (processFieldExprResolveVariants(lookup, receiverType, processor)) return true
    if (processMethodDeclarationsWithDeref(lookup, receiverType, processor)) return true

    return false
}

fun processFieldExprResolveVariants(
    lookup: ImplLookup,
    receiverType: Ty,
    processor: (FieldResolveVariant) -> Boolean
): Boolean {
    for ((i, ty) in lookup.coercionSequence(receiverType).withIndex()) {
        if (ty !is TyAdt || ty.item !is RsStructItem) continue
        if (processFieldDeclarations(ty.item) { processor(FieldResolveVariant(it.name, it.element!!, ty, i)) }) return true
    }

    return false
}

fun processStructLiteralFieldResolveVariants(
    field: RsStructLiteralField,
    isCompletion: Boolean,
    processor: RsResolveProcessor
): Boolean {
    val resolved = field.parentStructLiteral.path.reference.deepResolve()
    val structOrEnumVariant = resolved as? RsFieldsOwner ?: return false
    if (processFieldDeclarations(structOrEnumVariant, processor)) return true
    if (!isCompletion && field.expr == null) {
        processNestedScopesUpwards(field, VALUES, processor)
    }
    return false
}

fun processStructPatternFieldResolveVariants(
    field: RsPatFieldFull,
    processor: RsResolveProcessor
): Boolean {
    val resolved = field.parentStructPattern.path.reference.deepResolve()
    val resolvedStruct = resolved as? RsFieldsOwner ?: return false
    return processFieldDeclarations(resolvedStruct, processor)
}

fun processMethodCallExprResolveVariants(lookup: ImplLookup, receiverType: Ty, processor: RsMethodResolveProcessor): Boolean =
    processMethodDeclarationsWithDeref(lookup, receiverType, processor)

/**
 * Looks-up file corresponding to particular module designated by `mod-declaration-item`:
 *
 *  ```
 *  // foo.rs
 *  pub mod bar; // looks up `bar.rs` or `bar/mod.rs` in the same dir if `foo` is crate root,
 *               // `foo/bar.rs` or `foo/bar/mod.rs` otherwise
 *
 *  pub mod nested {
 *      pub mod baz; // looks up `nested/baz.rs` or `nested/baz/mod.rs` if `foo` is crate root,
 *                   // `foo/nested/baz.rs` or `foo/nested/baz/mod.rs` otherwise
 *  }
 *
 *  ```
 *
 *  | A module without a body is loaded from an external file, by default with the same name as the module,
 *  | plus the '.rs' extension. When a nested sub-module is loaded from an external file, it is loaded
 *  | from a subdirectory path that mirrors the module hierarchy.
 *
 * Reference:
 *      https://github.com/rust-lang-nursery/reference/blob/master/src/items/modules.md
 */
fun processModDeclResolveVariants(modDecl: RsModDeclItem, processor: RsResolveProcessor): Boolean {
    val containingMod = modDecl.containingMod

    val ownedDirectory = containingMod.ownedDirectory
    val inModRs = modDecl.contextualFile.name == RsConstants.MOD_RS_FILE
    val inCrateRoot = containingMod.isCrateRoot

    val explicitPath = modDecl.pathAttribute
    if (explicitPath != null) {
        // Explicit path is relative to:
        // * owned directory when module declared in inline module
        // * parent of module declaration otherwise
        val dir = if (containingMod is RsFile) {
            modDeclExplicitPathInNonInlineModule.hit()
            modDecl.contextualFile.parent
        } else {
            modDeclExplicitPathInInlineModule.hit()
            ownedDirectory
        } ?: return false
        val vFile = dir.virtualFile.findFileByRelativePath(FileUtil.toSystemIndependentName(explicitPath))
            ?: return false
        val mod = vFile.toPsiFile(modDecl.project)?.rustFile ?: return false

        val name = modDecl.name ?: return false
        return processor(name, mod)
    }
    if (ownedDirectory == null) return false
    if (modDecl.isLocal) return false

    fun fileName(file: PsiFile): String {
        val fileName = FileUtil.getNameWithoutExtension(file.name)
        val modDeclName = modDecl.referenceName
        // Handle case-insensitive filesystem (windows)
        return if (modDeclName.toLowerCase() == fileName.toLowerCase()) modDeclName else fileName
    }

    for (file in ownedDirectory.files) {
        if (file == modDecl.contextualFile.originalFile || file.name == RsConstants.MOD_RS_FILE) continue
        val mod = file.rustFile ?: continue
        val name = fileName(file)
        if (processor(name, mod)) return true
    }

    for (d in ownedDirectory.subdirectories) {
        val mod = d.findFile(RsConstants.MOD_RS_FILE)?.rustFile
        if (mod != null) {
            if (processor(d.name, mod)) return true
        }

        // Submodule file of crate root (for example, `mod foo;` in `src/main.rs`)
        // can be located in the same directory with parent module (i.e. in `src/foo.rs`)
        // or in `mod.rs` of subdirectory of crate root dir (i.e. in `src/foo/mod.rs`)
        // Both cases are handled above
        if (inCrateRoot) {
            crateRootModule.hit()
            continue
        }

        // We shouldn't search possible module files in subdirectories
        // if module declaration is located in `mod.rs`
        if (inModRs) {
            modRsFile.hit()
            continue
        }

        if (d.name == containingMod.modName) {
            for (file in d.files) {
                if (file.name == RsConstants.MOD_RS_FILE) continue
                val rustFile = file.rustFile ?: continue
                val fileName = fileName(file)
                if (processor(fileName, rustFile)) return true
            }
        }
    }

    return false
}

fun processExternCrateResolveVariants(element: RsElement, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    val isDoctestInjection = element.isDoctestInjection
    val target = element.containingCargoTarget ?: return false
    val pkg = target.pkg

    val visitedDeps = mutableSetOf<String>()
    fun processPackage(pkg: CargoWorkspace.Package, dependencyName: String? = null): Boolean {
        if (isCompletion && pkg.origin != PackageOrigin.DEPENDENCY) return false
        val libTarget = pkg.libTarget ?: return false
        // When crate depends on another version of the same crate
        // we shouldn't add current package into resolve/completion results
        if (otherVersionOfSameCrate.hitOnFalse(libTarget == target) && !isDoctestInjection) return false

        if (pkg.origin == PackageOrigin.STDLIB && pkg.name in visitedDeps) return false
        visitedDeps += pkg.name
        return processor.lazy(dependencyName ?: libTarget.normName) {
            libTarget.crateRoot?.toPsiFile(element.project)?.rustFile
        }
    }

    val crateRoot = element.crateRoot
    if (crateRoot != null && !isCompletion) {
        if (processor("self", crateRoot)) return true
    }
    if (processPackage(pkg)) return true
    val explicitDepsFirst = pkg.dependencies.sortedBy {
        when (it.pkg.origin) {
            PackageOrigin.WORKSPACE,
            PackageOrigin.DEPENDENCY,
            PackageOrigin.TRANSITIVE_DEPENDENCY -> {
                NameResolutionTestmarks.shadowingStdCrates.hit()
                0
            }
            PackageOrigin.STDLIB -> 1
        }
    }
    for (dependency in explicitDepsFirst) {
        if (processPackage(dependency.pkg, dependency.name)) return true
    }
    return false
}

private fun findDependencyCrateByName(context: RsElement, name: String): RsFile? {
    val refinedName = when {
        name.startsWith(MACRO_CRATE_IDENTIFIER_PREFIX) && context.findMacroCallExpandedFrom() != null -> {
            NameResolutionTestmarks.dollarCrateMagicIdentifier.hit()
            val refinedName = name.removePrefix(MACRO_CRATE_IDENTIFIER_PREFIX)
            if (refinedName == "self") {
                return context.crateRoot as? RsFile
            }
            refinedName
        }
        else -> name
    }
    var found: RsFile? = null
    processExternCrateResolveVariants(context, false) {
        if (it.name == refinedName) {
            found = it.element as? RsFile
            true
        } else {
            false
        }
    }
    return found
}

fun processPathResolveVariants(lookup: ImplLookup, path: RsPath, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    val parent = path.context
    if (parent is RsMacroCall) {
        return processMacroCallPathResolveVariants(path, isCompletion, processor)
    }
    val qualifier = path.qualifier
    val typeQual = path.typeQual
    val ns = when (parent) {
        is RsPath, is RsTypeElement, is RsTraitRef, is RsStructLiteral -> TYPES
        is RsUseSpeck -> when {
            // use foo::bar::{self, baz};
            //     ~~~~~~~~
            // use foo::bar::*;
            //     ~~~~~~~~
            parent.useGroup != null || parent.isStarImport -> TYPES
            // use foo::bar;
            //     ~~~~~~~~
            else -> TYPES_N_VALUES_N_MACROS
        }
        is RsPathExpr -> if (isCompletion) TYPES_N_VALUES else VALUES
        else -> TYPES_N_VALUES
    }

    // RsPathExpr can became a macro by adding a trailing `!`, so we add macros to completion
    if (isCompletion && parent is RsPathExpr && qualifier?.path == null) {
        if (processMacroCallPathResolveVariants(path, true, processor)) return true
    }

    return when {
        // foo::bar
        qualifier != null ->
            processQualifiedPathResolveVariants(lookup, isCompletion, ns, qualifier, path, parent, processor)

        // `<T as Trait>::Item` or `<T>::Item`
        typeQual != null ->
            processExplicitTypeQualifiedPathResolveVariants(lookup, ns, typeQual, processor)

        else -> processUnqualifiedPathResolveVariants(isCompletion, ns, path, parent, processor)
    }
}

/**
 * foo::bar
 * |    |
 * |    [path]
 * [qualifier]
 */
private fun processQualifiedPathResolveVariants(
    lookup: ImplLookup,
    isCompletion: Boolean,
    ns: Set<Namespace>,
    qualifier: RsPath,
    path: RsPath,
    parent: PsiElement?,
    processor: RsResolveProcessor
): Boolean {
    val (base, subst) = qualifier.reference.advancedResolve() ?: run {
        val primitiveType = TyPrimitive.fromPath(qualifier)
        if (primitiveType != null) {
            val selfSubst = mapOf(TyTypeParameter.self() to primitiveType).toTypeSubst()
            if (processAssociatedItemsWithSelfSubst(lookup, primitiveType, ns, selfSubst, processor)) return true
        }
        return false
    }
    val isSuperChain = isSuperChain(qualifier)
    if (base is RsMod) {
        val s = base.`super`
        // `super` is allowed only after `self` and `super`
        // so we add `super` in completion only when it produces valid path
        if (s != null && (!isCompletion || isSuperChain) && processor("super", s)) return true

        val containingMod = path.containingMod
        if (Namespace.Macros in ns && base is RsFile && base.isCrateRoot &&
            containingMod is RsFile && containingMod.isCrateRoot) {
            if (processAll(exportedMacros(base), processor)) return true
        }
    }
    if (parent is RsUseSpeck && path.path == null) {
        selfInGroup.hit()
        if (processor("self", base)) return true
    }
    if (processItemOrEnumVariantDeclarations(base, ns, processor, withPrivateImports = isSuperChain)) return true
    if (base is RsTypeDeclarationElement && parent !is RsUseSpeck) { // Foo::<Bar>::baz
        val baseTy = if (base is RsImplItem && qualifier.hasCself) {
            // impl S { fn foo() { Self::bar() } }
            base.typeReference?.type ?: TyUnknown
        } else {
            val realSubst = if (qualifier.typeArgumentList != null) {
                // If the path contains explicit type arguments `Foo::<_, Bar, _>::baz`
                // it means that all possible `TyInfer` has already substituted (with `_`)
                subst
            } else {
                subst.mapTypeValues { (_, v) -> v.foldTyTypeParameterWith { TyInfer.TyVar(it) } }
            }
            base.declaredType.substitute(realSubst)
        }

        // Self-qualified type paths `Self::Item` inside impl items are restricted to resolve
        // to only members of the current impl or implemented trait or its parent traits
        val restrictedTraits = if (Namespace.Types in ns && base is RsImplItem && qualifier.hasCself) {
            NameResolutionTestmarks.selfRelatedTypeSpecialCase.hit()
            base.implementedTrait?.flattenHierarchy
                ?.map { it.foldTyTypeParameterWith { TyInfer.TyVar(it) } }
        } else {
            null
        }

        if (processTypeQualifiedPathResolveVariants(lookup, processor, ns, baseTy, restrictedTraits)) return true
    }
    return false
}

/** `<T as Trait>::Item` or `<T>::Item` */
private fun processExplicitTypeQualifiedPathResolveVariants(
    lookup: ImplLookup,
    ns: Set<Namespace>,
    typeQual: RsTypeQual,
    processor: RsResolveProcessor
): Boolean {
    val trait = typeQual.traitRef?.resolveToBoundTrait()
        // TODO this is a hack to fix completion test `test associated type in explicit UFCS form`.
        // Looks like we should use getOriginalOrSelf during resolve
        ?.let { BoundElement(CompletionUtil.getOriginalOrSelf(it.element), it.subst) }
        ?.let { it.foldTyTypeParameterWith { TyInfer.TyVar(it) } }
    val type = typeQual.typeReference.type
    return processTypeQualifiedPathResolveVariants(lookup, processor, ns, type, trait?.let { listOf(it) })
}

private fun processUnqualifiedPathResolveVariants(
    isCompletion: Boolean,
    ns: Set<Namespace>,
    path: RsPath,
    parent: PsiElement?,
    processor: RsResolveProcessor
): Boolean {
    if (isCompletion) {
        // Complete possible associated types in a case like `Trait</*caret*/>`
        val possibleTypeArgs = parent?.parent?.parent
        if (possibleTypeArgs is RsTypeArgumentList) {
            val trait = (possibleTypeArgs.parent as? RsPath)?.reference?.resolve() as? RsTraitItem
            if (trait != null && processAssocTypeVariants(trait, processor)) return true
        }
    }

    val containingMod = path.containingMod
    val crateRoot = path.crateRoot
    /** Path starts with `::` */
    val hasColonColon = path.hasColonColon
    if (!hasColonColon) {
        run {
            // hacks around $crate macro metavar. See `expandDollarCrateVar` function docs
            val referenceName = path.referenceName
            if (referenceName.startsWith(MACRO_CRATE_IDENTIFIER_PREFIX) && path.findMacroCallExpandedFrom() != null) {
                val crate = referenceName.removePrefix(MACRO_CRATE_IDENTIFIER_PREFIX)
                val result = if (crate == "self") {
                    processor.lazy(referenceName) { path.crateRoot }
                } else {
                    processExternCrateResolveVariants(path, false) {
                        if (it.name == crate) processor.lazy(referenceName) { it.element } else false
                    }
                }
                if (result) return true
            }
        }
        if (Namespace.Types in ns) {
            if (processor("self", containingMod)) return true
            val superMod = containingMod.`super`
            if (superMod != null && processor("super", superMod)) return true
            if (crateRoot != null && processor("crate", crateRoot)) return true
        }
    }

    // In 2015 edition a path is crate-relative (global) if it's inside use item,
    // inside "visibility restriction" or if it starts with `::`
    // ```rust, edition2015
    // use foo::bar; // `foo` is crate-relative
    // let a = ::foo::bar; // `foo` is also crate-relative
    // pub(in foo::bar) fn baz() {}
    //       //^ crate-relative path too
    // ```
    // In 2018 edition a path is crate-relative if it starts with `crate::` (handled above)
    // or if it's inside "visibility restriction". `::`-qualified path on 2018 edition means that
    // such path is a name of some dependency crate (that should be resolved without `extern crate`)
    val isEdition2018 = path.isEdition2018
    val isCrateRelative = !isEdition2018 && (hasColonColon || path.contextStrict<RsUseItem>() != null)
        || path.contextStrict<RsVisRestriction>() != null
    // see https://doc.rust-lang.org/edition-guide/rust-2018/module-system/path-clarity.html#the-crate-keyword-refers-to-the-current-crate
    val isExternCrate = isEdition2018 && hasColonColon
    return when {
        isCrateRelative -> if (crateRoot != null) {
            processItemOrEnumVariantDeclarations(crateRoot, ns, processor, withPrivateImports = true)
        } else {
            false
        }

        isExternCrate -> processExternCrateResolveVariants(path, isCompletion, processor)

        else -> processNestedScopesUpwards(path, ns, isCompletion, processor)
    }
}

private fun processTypeQualifiedPathResolveVariants(
    lookup: ImplLookup,
    processor: RsResolveProcessor,
    ns: Set<Namespace>,
    baseTy: Ty,
    restrictedTraits: List<BoundElement<RsTraitItem>>?
): Boolean {
    val shadowingProcessor = if (restrictedTraits != null) {
        fun(e: AssocItemScopeEntry): Boolean {
            if (e.element !is RsTypeAlias) return processor(e)

            val implementedTrait = e.source.value.implementedTrait
                ?.foldTyTypeParameterWith { TyInfer.TyVar(it) }
                ?: return processor(e)

            val isAppropriateTrait = restrictedTraits.any {
                lookup.ctx.probe { lookup.ctx.combineBoundElements(it, implementedTrait) }
            }
            return if (isAppropriateTrait) processor(e) else false
        }
    } else {
        processor
    }
    val selfSubst = if (baseTy !is TyTraitObject) {
        mapOf(TyTypeParameter.self() to baseTy).toTypeSubst()
    } else {
        emptySubstitution
    }
    if (processAssociatedItemsWithSelfSubst(lookup, baseTy, ns, selfSubst, shadowingProcessor)) return true
    return false
}

fun processPatBindingResolveVariants(binding: RsPatBinding, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    return processNestedScopesUpwards(binding, if (isCompletion) TYPES_N_VALUES else VALUES) { entry ->
        processor.lazy(entry.name) {
            val element = entry.element ?: return@lazy null
            val isConstant = element.isConstantLike
            val isPathOrDestructable = when (element) {
                is RsMod, is RsEnumItem, is RsEnumVariant, is RsStructItem -> true
                else -> false
            }
            if (isConstant || (isCompletion && isPathOrDestructable)) element else null
        }
    }
}

fun processLabelResolveVariants(label: RsLabel, processor: RsResolveProcessor): Boolean {
    for (scope in label.ancestors) {
        if (scope is RsLambdaExpr || scope is RsFunction) return false
        if (scope is RsLabeledExpression) {
            val labelDecl = scope.labelDecl ?: continue
            if (processor(labelDecl)) return true
        }
    }
    return false
}

fun processLifetimeResolveVariants(lifetime: RsLifetime, processor: RsResolveProcessor): Boolean {
    if (lifetime.isPredefined) return false
    loop@ for (scope in lifetime.ancestors) {
        val lifetimeParameters = when (scope) {
            is RsGenericDeclaration -> scope.typeParameterList?.lifetimeParameterList
            is RsWhereClause -> scope.wherePredList.mapNotNull { it.forLifetimes }.flatMap { it.lifetimeParameterList }
            is RsForInType -> scope.forLifetimes.lifetimeParameterList
            is RsPolybound -> scope.forLifetimes?.lifetimeParameterList
            else -> continue@loop
        }
        if (processAll(lifetimeParameters.orEmpty(), processor)) return true
    }
    return false
}

fun processLocalVariables(place: RsElement, processor: (RsPatBinding) -> Unit) {
    walkUp(place, { it is RsItemElement }) { cameFrom, scope ->
        processLexicalDeclarations(scope, cameFrom, VALUES) { v ->
            val el = v.element
            if (el is RsPatBinding) processor(el)
            false
        }
    }
}

/**
 * Resolves an absolute path.
 */
fun resolveStringPath(path: String, workspace: CargoWorkspace, project: Project): Pair<RsNamedElement, CargoWorkspace.Package>? {
    check(!path.startsWith("::"))
    val parts = path.split("::", limit = 2)
    if (parts.size != 2) return null
    val pkg = workspace.findPackage(parts[0]) ?: run {
        return if (isUnitTestMode) {
            // Allows to set a fake path for some item in tests via
            // lang attribute, e.g. `#[lang = "std::iter::Iterator"]`
            RsLangItemIndex.findLangItem(project, path)?.let { it to workspace.packages.first() }
        } else {
            null
        }
    }

    val el = pkg.targets.asSequence()
        .mapNotNull { RsCodeFragmentFactory(project).createCrateRelativePath(parts[1], it) }
        .mapNotNull { it.reference.resolve() }
        .filterIsInstance<RsNamedElement>()
        .firstOrNull() ?: return null
    return el to pkg
}

fun processMacroReferenceVariants(ref: RsMacroReference, processor: RsResolveProcessor): Boolean {
    val definition = ref.ancestorStrict<RsMacroCase>() ?: return false
    val simple = definition.macroPattern.descendantsOfType<RsMacroBinding>()
        .toList()

    return simple.any { processor(it) }
}

fun processDeriveTraitResolveVariants(element: RsMetaItem, traitName: String, processor: RsResolveProcessor): Boolean {
    val knownDerive = KNOWN_DERIVABLE_TRAITS[traitName]?.findTrait(element.knownItems)
    return if (knownDerive != null) {
        processor(knownDerive)
    } else {
        val traits = RsNamedElementIndex.findElementsByName(element.project, traitName)
            .filterIsInstance<RsTraitItem>()
        processAll(traits, processor)
    }
}

fun processBinaryOpVariants(element: RsBinaryOp, operator: OverloadableBinaryOperator,
                            processor: RsResolveProcessor): Boolean {
    val binaryExpr = element.ancestorStrict<RsBinaryExpr>() ?: return false
    val rhsType = binaryExpr.right?.type ?: return false
    val lhsType = binaryExpr.left.type
    val lookup = ImplLookup.relativeTo(element)
    val function = lookup.findOverloadedOpImpl(lhsType, rhsType, operator)
        ?.members
        ?.functionList
        ?.find { it.name == operator.fnName }
        ?: return false
    return processor(function)
}

fun processAssocTypeVariants(element: RsAssocTypeBinding, processor: RsResolveProcessor): Boolean {
    val trait = element.parentPath?.reference?.resolve() as? RsTraitItem ?: return false
    return processAssocTypeVariants(trait, processor)
}

fun processAssocTypeVariants(trait: RsTraitItem, processor: RsResolveProcessor): Boolean {
    if (trait.associatedTypesTransitively.any { processor(it) }) return true
    return false
}

private fun processMacroCallPathResolveVariants(path: RsPath, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    // Allowed only 1 or 2-segment paths: `foo!()` or `foo::bar!()`, but not foo::bar::baz!();
    val qualifier = path.qualifier
    if (qualifier?.path != null) return false
    return if (qualifier == null) {
        if (isCompletion) {
            processMacroCallVariantsInScope(path, processor)
        } else {
            val resolved = pickFirstResolveVariant(path.referenceName) { processMacroCallVariantsInScope(path, it) }
                as? RsNamedElement
            resolved?.let { processor(it) } ?: false
        }
    } else {
        processMacrosExportedByCrateName(path, qualifier.referenceName, processor)
    }
}

fun processMacrosExportedByCrateName(context: RsElement, crateName: String, processor: RsResolveProcessor): Boolean {
    val crateRoot = findDependencyCrateByName(context, crateName) ?: return false
    val exportedMacros = exportedMacros(crateRoot)
    return processAll(exportedMacros, processor)
}

fun processMacroCallVariantsInScope(context: PsiElement, processor: RsResolveProcessor): Boolean {
    val result = context.project.macroExpansionManager.withResolvingMacro {
        MacroResolver.processMacrosInLexicalOrderUpward(context) { processor(it) }
    }
    if (result) return true

    val prelude = context.contextOrSelf<RsElement>()?.findDependencyCrateRoot(STD) ?: return false
    return processAll(exportedMacros(prelude), processor)
}

private class MacroResolver private constructor() {
    private val exportingMacrosCrates = mutableMapOf<String, RsFile>()
    private val useItems = mutableListOf<RsUseItem>()

    private fun collectMacrosInScopeDownward(scope: RsItemsOwner): List<RsNamedElement> {
        val visibleMacros = mutableListOf<RsNamedElement>()

        for (item in scope.itemsAndMacros) {
            processMacrosAtScopeEntry(item) {
                visibleMacros.add(it)
            }
        }

        visibleMacros.reverse() // Reverse list to recover lexical order of macro declarations

        processRemainedExportedMacros { visibleMacros.add(it) }

        return visibleMacros
    }

    private fun processMacrosInLexicalOrderUpward(startElement: PsiElement, processor: (RsNamedElement) -> Boolean): Boolean {
        if (processScopesInLexicalOrderUpward(startElement) {
            if (it is RsElement) {
                processMacrosAtScopeEntry(it, processor)
            } else {
                false
            }
        }) {
            return true
        }

        val crateRoot = startElement.contextOrSelf<RsElement>()?.crateRoot as? RsFile
        if (crateRoot != null) {
            NameResolutionTestmarks.processSelfCrateExportedMacros.hit()
            if (processAll(exportedMacros(crateRoot), processor)) return true
        }

        return processRemainedExportedMacros(processor)
    }

    /**
     * In short, it processes left siblings, then left siblings of the parent (without parent itself) and so on
     * until root (file), then it goes up to module declaration of the file (`mod foo;`) and processes its
     * siblings, and so on until crate root
     */
    private fun processScopesInLexicalOrderUpward(
        startElement: PsiElement,
        processor: (PsiElement) -> Boolean
    ): Boolean {
        val stub = (startElement as? StubBasedPsiElement<*>)?.greenStub
        return if (stub != null) {
            stubBasedProcessScopesInLexicalOrderUpward(stub, processor)
        } else {
            psiBasedProcessScopesInLexicalOrderUpward(startElement, processor)
        }
    }

    private tailrec fun psiBasedProcessScopesInLexicalOrderUpward(
        element: PsiElement,
        processor: (PsiElement) -> Boolean
    ): Boolean {
        if (processAll(element.leftSiblings, processor)) return true

        // ```
        // //- main.rs
        // macro_rules! foo { ... }
        // bar! { foo!(); } // expands to `foo!();` macro call
        // ```
        // In such case, if we expanded `bar!` macro call to macro call `foo!` and then resolving
        // `foo` macro, both `parent` and `context` of `foo!` macro call are "main.rs" file.
        // But we want to process macro definition before `bar!` macro call, so we have to use
        // a macro call as a "parent"
        val context = (element as? RsExpandedElement)?.expandedFrom ?: element.context ?: return false
        return when {
            context is RsFile -> processScopesInLexicalOrderUpward(context.declaration ?: return false, processor)
            // Optimization. Let this function be tailrec if go up by real parent (in the same file)
            context != element.parent -> processScopesInLexicalOrderUpward(context, processor)
            else -> psiBasedProcessScopesInLexicalOrderUpward(context, processor)
        }
    }

    private tailrec fun stubBasedProcessScopesInLexicalOrderUpward(
        element: StubElement<*>,
        processor: (PsiElement) -> Boolean
    ): Boolean {
        val parentStub = element.parentStub ?: return false
        val siblings = parentStub.childrenStubs
        val index = siblings.indexOf(element)
        check(index != -1) { "Can't find stub index" }
        val leftSiblings = siblings.subList(0, index)
        for (it in leftSiblings) {
            if (processor(it.psi)) return true
        }
        // See comment in psiBasedProcessScopesInLexicalOrderUpward
        val parentPsi = (element.psi as? RsExpandedElement)?.expandedFrom ?: parentStub.psi
        return when {
            parentPsi is RsFile -> processScopesInLexicalOrderUpward(parentPsi.declaration ?: return false, processor)
            // Optimization. Let this function be tailrec if go up by stub parent
            parentPsi != parentStub.psi -> processScopesInLexicalOrderUpward(parentPsi, processor)
            else -> stubBasedProcessScopesInLexicalOrderUpward(parentStub, processor)
        }
    }

    private fun processMacrosAtScopeEntry(
        item: RsElement,
        processor: (RsNamedElement) -> Boolean
    ): Boolean {
        when (item) {
            is RsMacro -> if (processor(item)) return true
            is RsModItem ->
                if (missingMacroUse.hitOnFalse(item.hasMacroUse)) {
                    if (processAll(visibleMacros(item), processor)) return true
                }
            is RsModDeclItem ->
                if (missingMacroUse.hitOnFalse(item.hasMacroUse)) {
                    val mod = item.reference.resolve() as? RsMod ?: return false
                    if (processAll(visibleMacros(mod), processor)) return true
                }
            is RsExternCrateItem -> {
                val mod = item.reference.resolve() as? RsFile ?: return false
                if (missingMacroUse.hitOnFalse(item.hasMacroUse)) {
                    // If extern crate has `#[macro_use]` attribute
                    // we can use all exported macros from the corresponding crate
                    if (processAll(exportedMacros(mod), processor)) return true
                } else {
                    // otherwise we can use only reexported macros
                    val reexportedMacros = reexportedMacros(item)
                    if (reexportedMacros != null) {
                        // via #[macro_reexport] attribute (old way)
                        if (processAll(reexportedMacros, processor)) return true
                    } else {
                        // or from `use` items (new way)
                        exportingMacrosCrates[item.nameWithAlias] = mod
                    }
                }


            }
            is RsUseItem -> useItems += item
        }

        return false
    }

    private fun processRemainedExportedMacros(processor: (RsNamedElement) -> Boolean): Boolean {
        for (useItem in useItems) {
            if (processAll(collectMacrosImportedWithUseItem(useItem, exportingMacrosCrates), processor)) return true
        }
        return false
    }

    companion object {
        fun processMacrosInLexicalOrderUpward(startElement: PsiElement, processor: (RsNamedElement) -> Boolean): Boolean =
            MacroResolver().processMacrosInLexicalOrderUpward(startElement, processor)

        private fun visibleMacros(scope: RsItemsOwner): List<RsNamedElement> =
            CachedValuesManager.getCachedValue(scope) {
                val macros = MacroResolver().collectMacrosInScopeDownward(scope)
                CachedValueProvider.Result.create(macros, scope.rustStructureOrAnyPsiModificationTracker)
            }

        private fun <T> processAll(elements: Collection<T>, processor: (T) -> Boolean): Boolean =
            processAll(elements.asSequence(), processor)

        private fun <T> processAll(elements: Sequence<T>, processor: (T) -> Boolean): Boolean {
            for (e in elements) {
                if (processor(e)) return true
            }
            return false
        }
    }
}

private fun exportedMacros(scope: RsFile): List<RsNamedElement> {
    if (!scope.isCrateRoot) {
        LOG.warn("`${scope.virtualFile}` should be crate root")
        return emptyList()
    }
    return CachedValuesManager.getCachedValue(scope) {
        val macros = exportedMacrosInternal(scope)
        CachedValueProvider.Result.create(macros, scope.rustStructureOrAnyPsiModificationTracker)
    }
}

private fun exportedMacrosInternal(scope: RsFile): List<RsNamedElement> {
    if (scope.containingCargoTarget?.isProcMacro == true) {
        return scope.stubChildrenOfType<RsFunction>()
            .filter { it.queryAttributes.hasAtomAttribute("proc_macro") }
    }

    val allExportedMacros = RsMacroIndex.allExportedMacros(scope.project)
    return buildList {
        addAll(allExportedMacros[scope].orEmpty())

        val exportingMacrosCrates = mutableMapOf<String, RsMod>()

        val externCrates = scope.stubChildrenOfType<RsExternCrateItem>()
        for (item in externCrates) {
            val reexportedMacros = reexportedMacros(item)
            if (reexportedMacros != null) {
                addAll(reexportedMacros)
            } else {
                exportingMacrosCrates[item.nameWithAlias] = item.reference.resolve() as? RsMod ?: continue
            }
        }

        val implicitStdlibCrate = implicitStdlibCrate(scope)
        if (implicitStdlibCrate != null) {
            exportingMacrosCrates[implicitStdlibCrate.name] = implicitStdlibCrate.crateRoot
        }

        if (exportingMacrosCrates.isNotEmpty()) {
            for (useItem in scope.stubChildrenOfType<RsUseItem>()) {
                // only public use items can reexport macros
                if (!useItem.isPublic) continue
                addAll(collectMacrosImportedWithUseItem(useItem, exportingMacrosCrates))
            }
        }
    }
}

/**
 * Returns list of re-exported macros via `[macro_reexport]` attribute from given extern crate
 * or null if extern crate item doesn't have `[macro_reexport]` attribute
 */
private fun reexportedMacros(item: RsExternCrateItem): List<RsMacro>? {
    val macroReexportAttr = item.findOuterAttr("macro_reexport") ?: return null
    val exportingMacroNames = macroReexportAttr
        .metaItem
        .metaItemArgs
        ?.metaItemList
        ?.mapNotNull { it.name }
        ?: return emptyList()
    val mod = item.reference.resolve() as? RsFile ?: return emptyList()
    val nameToExportedMacro = exportedMacros(mod).mapNotNull {
        if (it !is RsMacro) return@mapNotNull null
        val name = it.name ?: return@mapNotNull null
        name to it
    }.toMap()
    return exportingMacroNames.mapNotNull { nameToExportedMacro[it] }
}

private fun collectMacrosImportedWithUseItem(
    useItem: RsUseItem,
    exportingMacrosCrates: Map<String, RsMod>
): List<RsNamedElement> {
    // We don't want to perform path resolution during macro resolve (because it can recursively perform
    // macro resolve and so be slow and incorrect).
    // We assume that macro can only be imported by 2-segment path (`foo::bar`), where the first segment
    // is a name of the crate (may be aliased) and the second segment is the name of the macro.
    return buildList {
        val root = useItem.useSpeck ?: return@buildList

        for ((crateName, macroName) in collect2segmentPaths(root)) {
            val crateRoot = exportingMacrosCrates[crateName] as? RsFile
                ?: findDependencyCrateByName(useItem, crateName)
                ?: continue
            val exportedMacros = exportedMacros(crateRoot)
            addAll(if (macroName == null) {
                exportedMacros
            } else {
                exportedMacros.filter { it.name == macroName }
            })
        }
    }
}

/** Represents a path of 2 segments `foo::bar`. For wildcard `foo::*` path [rightSegment] is null */
private data class TwoSegmentPath(val leftSegment: String, val rightSegment: String?)

/** For given `use foo::{bar, baz::quux, spam::{eggs}}` return the only `[foo::bar]`. */
private fun collect2segmentPaths(rootSpeck: RsUseSpeck): List<TwoSegmentPath> {
    val result = mutableListOf<TwoSegmentPath>()

    fun go(speck: RsUseSpeck) {
        val starImport = speck.isStarImport
        val path = speck.path
        val qualifier = path?.qualifier ?: speck.qualifier
        val group = speck.useGroup
        if (path != null && qualifier != null && qualifier.qualifier != null) return
        val firstSegment = qualifier ?: path
        val lastSegment = path ?: qualifier
        if (firstSegment != null && firstSegment.kind != PathKind.IDENTIFIER) return
        when {
            group == null && firstSegment != null && (path != null || starImport) -> {
                if (firstSegment != lastSegment && starImport) return
                result += TwoSegmentPath(firstSegment.referenceName, if (starImport) null else path?.referenceName)
            }
            group != null && firstSegment == lastSegment -> {
                group.useSpeckList.forEach { go(it) }
            }
        }
    }
    go(rootSpeck)

    return result
}

private fun processFieldDeclarations(struct: RsFieldsOwner, processor: RsResolveProcessor): Boolean =
    struct.fields.any { field ->
        val name = field.name ?: return@any false
        processor(name, field)
    }

private fun processMethodDeclarationsWithDeref(lookup: ImplLookup, receiver: Ty, processor: RsMethodResolveProcessor): Boolean {
    return lookup.coercionSequence(receiver).withIndex().any { (i, ty) ->
        val methodProcessor: (AssocItemScopeEntry) -> Boolean = { (name, element, _, source) ->
            element is RsFunction && element.isMethod && processor(MethodResolveVariant(name, element, ty, i, source))
        }
        processAssociatedItems(lookup, ty, VALUES, methodProcessor)
    }
}

private fun processAssociatedItems(
    lookup: ImplLookup,
    type: Ty,
    ns: Set<Namespace>,
    processor: (AssocItemScopeEntry) -> Boolean
): Boolean {
    val traitBounds = (type as? TyTypeParameter)?.let { lookup.getEnvBoundTransitivelyFor(it).toList() }
    val visitedInherent = mutableSetOf<String>()
    fun processTraitOrImpl(traitOrImpl: TraitImplSource, inherent: Boolean): Boolean {
        fun inherentProcessor(entry: RsNamedElement): Boolean {
            val name = entry.name ?: return false
            if (inherent) visitedInherent.add(name)
            if (!inherent && name in visitedInherent) return false

            val subst = if (traitBounds != null && traitOrImpl is TraitImplSource.TraitBound) {
                // Retrieve trait subst for associated type like
                // trait SliceIndex<T> { type Output; }
                // fn get<I: : SliceIndex<S>>(index: I) -> I::Output
                // Resulting subst will contains mapping T => S
                traitBounds.filter { it.element == traitOrImpl.value }.map { it.subst }
            } else {
                listOf(emptySubstitution)
            }
            return subst.any { processor(AssocItemScopeEntry(name, entry, it, traitOrImpl)) }
        }

        /**
         * For `impl T for Foo`, this'll walk impl members and trait `T` members,
         * which are not implemented.
         */
        fun processMembersWithDefaults(accessor: (RsMembers) -> List<RsNamedElement>): Boolean {
            val directlyImplemented = traitOrImpl.value.members?.let { accessor(it) }.orEmpty()
            if (directlyImplemented.any { inherentProcessor(it) }) return true

            if (traitOrImpl is TraitImplSource.ExplicitImpl) {
                val direct = directlyImplemented.map { it.name }.toSet()
                val membersFromTrait = traitOrImpl.value.implementedTrait?.element?.members ?: return false
                for (member in accessor(membersFromTrait)) {
                    if (member.name !in direct && inherentProcessor(member)) return true
                }
            }

            return false
        }

        if (Namespace.Values in ns) {
            if (processMembersWithDefaults { it.expandedMembers.functionsAndConstants }) return true
        }
        if (Namespace.Types in ns) {
            if (processMembersWithDefaults { it.expandedMembers.types }) return true
        }
        return false
    }

    val (inherent, traits) = lookup.findImplsAndTraits(type).partition { it is TraitImplSource.ExplicitImpl && it.value.traitRef == null }
    if (inherent.any { processTraitOrImpl(it, true) }) return true
    if (traits.any { processTraitOrImpl(it, false) }) return true
    return false
}

private fun processAssociatedItemsWithSelfSubst(
    lookup: ImplLookup,
    type: Ty,
    ns: Set<Namespace>,
    selfSubst: Substitution,
    processor: (AssocItemScopeEntry) -> Boolean
): Boolean {
    return processAssociatedItems(lookup, type, ns) {
        processor(it.copy(subst = it.subst + selfSubst))
    }
}

private fun processLexicalDeclarations(
    scope: RsElement,
    cameFrom: PsiElement,
    ns: Set<Namespace>,
    processor: RsResolveProcessor
): Boolean {
    check(cameFrom.context == scope)

    fun processPattern(pattern: RsPat, processor: RsResolveProcessor): Boolean {
        val boundNames = PsiTreeUtil.findChildrenOfType(pattern, RsPatBinding::class.java)
            .filter { it.reference.resolve() == null }
        return processAll(boundNames, processor)
    }

    fun processOrPats(orPats: RsOrPats, processor: RsResolveProcessor): Boolean {
        if (Namespace.Values !in ns) return false
        if (cameFrom == orPats) return false
        val patList = orPats.patList
        if (cameFrom in patList) return false
        // Rust allows to defined several patterns in the single match arm and in if/while expr condition,
        // but they all must bind the same variables, hence we can inspect
        // only the first one.
        val pat = patList.firstOrNull()
        if (pat != null && processPattern(pat, processor)) return true
        return false
    }

    fun processCondition(condition: RsCondition?, processor: RsResolveProcessor): Boolean {
        if (condition == null || condition == cameFrom) return false
        val orPats = condition.orPats ?: return false
        return processOrPats(orPats, processor)
    }

    when (scope) {
        is RsMod -> {
            if (processItemDeclarationsWithCache(scope, ns, processor, withPrivateImports = true)) return true
        }

        is RsTypeAlias -> {
            if (processAll(scope.typeParameters, processor)) return true
        }

        is RsStructItem,
        is RsEnumItem,
        is RsTraitOrImpl -> {
            scope as RsGenericDeclaration
            if (processAll(scope.typeParameters, processor)) return true
            if (processor("Self", scope)) return true
            if (scope is RsImplItem) {
                scope.traitRef?.let { traitRef ->
                    // really should be unnamed, but "_" is not a valid name in rust, so I think it's ok
                    if (processor.lazy("_") { traitRef.resolveToTrait() }) return true
                }
            }
        }

        is RsFunction -> {
            if (Namespace.Types in ns) {
                if (processAll(scope.typeParameters, processor)) return true
            }
            // XXX: `cameFrom !is RsValueParameterList` prevents switches to AST in cases like
            // `fn foo(a: usize, b: [u8; SIZE])`. Note that rustc really process them and show
            // [E0435] on this: `fn foo(a: usize, b: [u8; a])`.
            if (Namespace.Values in ns && cameFrom !is RsValueParameterList) {
                val selfParam = scope.selfParameter
                if (selfParam != null && processor("self", selfParam)) return true

                for (parameter in scope.valueParameters) {
                    val pat = parameter.pat ?: continue
                    if (processPattern(pat, processor)) return true
                }
            }
        }

        is RsBlock -> {
            // We want to filter out
            // all non strictly preceding let declarations.
            //
            // ```
            // let x = 92; // visible
            // let x = x;  // not visible
            //         ^ context.place
            // let x = 62; // not visible
            // ```
            val visited = mutableSetOf<String>()
            if (Namespace.Values in ns) {
                val shadowingProcessor = { e: ScopeEntry ->
                    (e.name !in visited) && run {
                        visited += e.name
                        processor(e)
                    }
                }

                for (stmt in scope.stmtList.asReversed()) {
                    val pat = (stmt as? RsLetDecl)?.pat ?: continue
                    if (PsiUtilCore.compareElementsByPosition(cameFrom, stmt) < 0) continue
                    if (stmt == cameFrom) continue
                    if (processPattern(pat, shadowingProcessor)) return true
                }
            }

            return processItemDeclarations(scope, ns, processor, withPrivateImports = true)
        }

        is RsForExpr -> {
            if (scope.expr == cameFrom) return false
            if (Namespace.Values !in ns) return false
            val pat = scope.pat
            if (pat != null && processPattern(pat, processor)) return true
        }

        is RsIfExpr -> {
            // else branch of 'if let' expression shouldn't look into condition pattern
            if (scope.elseBranch == cameFrom) return false
            return processCondition(scope.condition, processor)
        }
        is RsWhileExpr -> {
            return processCondition(scope.condition, processor)
        }

        is RsLambdaExpr -> {
            if (Namespace.Values !in ns) return false
            for (parameter in scope.valueParameterList.valueParameterList) {
                val pat = parameter.pat
                if (pat != null && processPattern(pat, processor)) return true
            }
        }

        is RsMatchArm -> {
            return processOrPats(scope.orPats, processor)
        }
    }
    return false
}

fun processNestedScopesUpwards(scopeStart: RsElement, ns: Set<Namespace>, processor: RsResolveProcessor): Boolean =
    processNestedScopesUpwards(scopeStart, ns, isCompletion = false, processor = processor)

fun processNestedScopesUpwards(
    scopeStart: RsElement,
    ns: Set<Namespace>,
    isCompletion: Boolean,
    processor: RsResolveProcessor
): Boolean {
    val prevScope = mutableSetOf<String>()
    if (walkUp(scopeStart, { it is RsMod }) { cameFrom, scope ->
        processWithShadowing(prevScope, processor) { shadowingProcessor ->
            processLexicalDeclarations(scope, cameFrom, ns, shadowingProcessor)
        }
    }) {
        return true
    }

    // Prelude is injected via implicit star import `use std::prelude::v1::*;`
    if (processor(ScopeEvent.STAR_IMPORTS)) return false

    if (Namespace.Types in ns) {
        if (scopeStart.isEdition2018 && !scopeStart.containingMod.isCrateRoot) {
            val crateRoot = scopeStart.crateRoot
            if (crateRoot != null) {
                val result = processWithShadowing(prevScope, processor) { shadowingProcessor ->
                    crateRoot.processExpandedItemsExceptImpls { item ->
                        if (item is RsExternCrateItem) {
                            processExternCrateItem(item, shadowingProcessor, true)
                        } else {
                            false
                        }
                    }
                }
                if (result) return true
            }
        }

        // "extern_prelude" feature. Extern crate names can be resolved as if they were in the prelude.
        // See https://blog.rust-lang.org/2018/10/25/Rust-1.30.0.html#module-system-improvements
        // See https://github.com/rust-lang/rust/pull/54404/
        val result = processWithShadowing(prevScope, processor) { shadowingProcessor ->
            processExternCrateResolveVariants(scopeStart, isCompletion, shadowingProcessor)
        }
        if (result) return true
    }

    val prelude = findPrelude(scopeStart)
    if (prelude != null) {
        val preludeProcessor: (ScopeEntry) -> Boolean = { v -> v.name !in prevScope && processor(v) }
        return processItemDeclarationsWithCache(prelude, ns, preludeProcessor, withPrivateImports = false)
    }

    return false
}

private inline fun processWithShadowing(
    prevScope: MutableSet<String>,
    crossinline processor: RsResolveProcessor,
    f: (RsResolveProcessor) -> Boolean
): Boolean {
    val currScope = mutableListOf<String>()
    val shadowingProcessor = { e: ScopeEntry ->
        e.name !in prevScope && run {
            currScope += e.name
            processor(e)
        }
    }
    return try {
        f(shadowingProcessor)
    } finally {
        prevScope.addAll(currScope)
    }
}

private fun findPrelude(element: RsElement): RsFile? {
    val crateRoot = element.crateRoot as? RsFile ?: return null
    val cargoPackage = crateRoot.containingCargoPackage
    val isStdlib = cargoPackage?.origin == PackageOrigin.STDLIB && !element.isDoctestInjection
    val packageName = cargoPackage?.normName

    // `std` and `core` crates explicitly add their prelude
    val stdlibCrateRoot = if (isStdlib && (packageName == STD || packageName == CORE)) {
        crateRoot
    } else {
        implicitStdlibCrate(crateRoot)?.crateRoot
    }

    return stdlibCrateRoot
        ?.virtualFile
        ?.findFileByRelativePath("../prelude/v1.rs")
        ?.toPsiFile(element.project)
        ?.rustFile
}

// Implicit extern crate from stdlib
private fun implicitStdlibCrate(scope: RsFile): ImplicitStdlibCrate? {
    val (name, crateRoot) = when (scope.attributes) {
        NO_CORE -> return null
        NO_STD -> CORE to scope.findDependencyCrateRoot(CORE)
        NONE -> STD to scope.findDependencyCrateRoot(STD)
    }
    return if (crateRoot == null) null else ImplicitStdlibCrate(name, crateRoot)
}

// There's already similar functions in TreeUtils, should use it
private fun walkUp(
    start: PsiElement,
    stopAfter: (RsElement) -> Boolean,
    stopAtFileBoundary: Boolean = true,
    processor: (cameFrom: PsiElement, scope: RsElement) -> Boolean
): Boolean {
    var cameFrom = start
    var scope = start.context as RsElement?
    while (scope != null) {
        ProgressManager.checkCanceled()
        if (processor(cameFrom, scope)) return true
        if (stopAfter(scope)) break
        cameFrom = scope
        scope = scope.context as? RsElement
        if (!stopAtFileBoundary) {
            if (scope == null && cameFrom is RsFile) {
                scope = cameFrom.`super`
            }
        }
    }
    return false
}

fun isSuperChain(path: RsPath): Boolean {
    val qualifier = path.path
    val referenceName = path.referenceName
    return (referenceName == "super" || referenceName == "self" || referenceName == "crate") &&
        (qualifier == null || isSuperChain(qualifier))
}


object NameResolutionTestmarks {
    val shadowingStdCrates = Testmark("shadowingStdCrates")
    val missingMacroExport = Testmark("missingMacroExport")
    val missingMacroUse = Testmark("missingMacroUse")
    val processSelfCrateExportedMacros = Testmark("processSelfCrateExportedMacros")
    val dollarCrateMagicIdentifier = Testmark("dollarCrateMagicIdentifier")
    val selfInGroup = Testmark("selfInGroup")
    val otherVersionOfSameCrate = Testmark("otherVersionOfSameCrate")
    val crateRootModule = Testmark("crateRootModule")
    val modRsFile = Testmark("modRsFile")
    val modDeclExplicitPathInInlineModule = Testmark("modDeclExplicitPathInInlineModule")
    val modDeclExplicitPathInNonInlineModule = Testmark("modDeclExplicitPathInNonInlineModule")
    val selfRelatedTypeSpecialCase = Testmark("selfRelatedTypeSpecialCase")
}

private data class ImplicitStdlibCrate(val name: String, val crateRoot: RsFile)
