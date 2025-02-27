/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.rust.lang.core.parser.RustParser
import org.rust.lang.core.parser.RustParserUtil.collapsedTokenType
import org.rust.lang.core.parser.createRustPsiBuilder
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.IDENTIFIER
import org.rust.lang.core.psi.ext.*
import org.rust.openapiext.Testmark
import org.rust.openapiext.forEachChild
import org.rust.stdext.joinToWithBuffer
import org.rust.stdext.mapNotNullToSet
import java.util.*
import java.util.Collections.singletonMap

private data class MacroSubstitution(
    /**
     * Maps meta variable names with the actual values, e.g. for this macro
     * ```rust
     * macro_rules! foo {
     *     ($ i:item) => ( $i )
     * }
     * foo!( mod a {} )
     * ```
     * we map "i" to "mod a {}"
     */
    val variables: Map<String, String>,

    /**
     * Contains macro groups values. E.g. for this macro
     * ```rust
     * macro_rules! foo {
     *     ($($ i:item),*; $($ e:expr),*) => {
     *         fn foo() { $($ e);*; }
     *         $($ i)*
     *     }
     * }
     * foo! { mod a {}, mod b {}; 1, 2 }
     * ```
     * It will contains
     * ```
     * [
     *     MacroGroup {
     *         substs: [
     *             {"i" => "mod a {}"},
     *             {"i" => "mod b {}"}
     *         ]
     *     },
     *     MacroGroup {
     *         substs: [
     *             {"e" => "1"},
     *             {"e" => "2"}
     *         ]
     *     }
     * ]
     * ```
     */
    val groups: List<MacroGroup>
)

private data class MacroGroup(
    val definition: RsMacroBindingGroup,
    val substs: List<MacroSubstitution>
)

private data class WithParent(
    private val subst: MacroSubstitution,
    private val parent: WithParent?
) {
    val groups: List<MacroGroup>
        get() = subst.groups

    fun getVar(name: String): String? =
        subst.variables[name] ?: parent?.getVar(name)
}

class MacroExpander(val project: Project) {
    fun expandMacroAsText(def: RsMacro, call: RsMacroCall, wrap: Boolean = true): String? {
        expandBuiltinMacroAsText(call)?.let {
            return@expandMacroAsText it
        }
        val (case, subst) = findMatchingPattern(def, call, wrap = wrap) ?: return null
        val macroExpansion = case.macroExpansion ?: return null

        val substWithGlobalVars = WithParent(
            subst,
            WithParent(
                MacroSubstitution(
                    singletonMap("crate", expandDollarCrateVar(call, def)),
                    emptyList()
                ),
                null
            )
        )

        return substituteMacro(macroExpansion.macroExpansionContents, substWithGlobalVars)
    }

    private fun expandBuiltinMacroAsText(call: RsMacroCall): String? {
        return when (call.macroName) {
            "env", "option_env" -> execOnArguments(call, wrap = false) { arguments ->
                if (arguments.isEmpty()) {
                    return@execOnArguments null
                }
                val value = System.getenv(arguments[0])
                when (call.macroName) {
                    "env" -> makeString(value ?: return@execOnArguments null)
                    "option_env" -> when {
                        value != null -> "Some(${makeString(value)})"
                        else -> "None"
                    }
                    else -> return@execOnArguments null
                }
            }
            "concat" -> execOnArguments(call, wrap = false) { arguments ->
                makeString(arguments.joinToString(""))
            }
            "include" -> execOnArguments(call, wrap = false) { arguments ->
                if (arguments.isEmpty()) {
                    return@execOnArguments null
                }
                val context = call.context ?: return@execOnArguments null
                val file = InjectedLanguageManager.getInstance(call.project).getTopLevelFile(context)
                val parent = file?.originalFile?.virtualFile?.parent ?: return@execOnArguments null
                val directory = file.manager.findDirectory(parent)
                val targetFile = directory?.virtualFile?.findFileByRelativePath(arguments[0])
                String(targetFile?.contentsToByteArray() ?: return@execOnArguments null)
            }
            else -> null
        }
    }

    private fun execOnArguments(
        call: RsMacroCall,
        wrap: Boolean = true,
        exec: (List<String>) -> String?
    ): String? {
        val macroBody = call.macroBody ?: return null
        val file = RsPsiFactory(project).createFile("fn f() { ($macroBody,); }")
        val tupleExpr = file.stubDescendantOfTypeOrStrict<RsExpr>() as? RsTupleExpr ?: return null
        val exprList = tupleExpr.exprList
        exprList.forEach { expr ->
            if (!expr.setContextAndExpandedFrom(call)) {
                return null
            }
        }
        return when {
            exprList.all { it is RsLitExpr } -> {
                exec(exprList.map { expr ->
                    val kind = (expr as RsLitExpr).kind
                    when (kind) {
                        is RsLiteralKind.Boolean -> kind.value.toString()
                        is RsLiteralKind.Integer -> kind.value?.toString() ?: return null
                        is RsLiteralKind.Float -> kind.value?.toString() ?: return null
                        is RsLiteralKind.String -> kind.value ?: return null
                        is RsLiteralKind.Char -> kind.value ?: return null
                        null -> return null
                    }
                })
            }
            exprList.any { it is RsMacroExpr } -> {
                exprList.map { expr ->
                    when (expr) {
                        is RsMacroExpr -> expandMacroAsText(
                            expr.macroCall.resolveToMacro() ?: return null,
                            expr.macroCall,
                            wrap = wrap
                        ) ?: return null
                        else -> expr.text
                    }
                }.joinToString(
                    prefix = "${call.macroName}!(",
                    postfix = if (call.expansionContext == MacroExpansionContext.ITEM) ");" else ")"
                )
            }
            else -> null
        }
    }

    private fun makeString(s: String): String = "\"$s\""

    private fun findMatchingPattern(
        def: RsMacro,
        call: RsMacroCall,
        wrap: Boolean = true
    ): Pair<RsMacroCase, MacroSubstitution>? {
        val macroCallBody = project.createRustPsiBuilder(call.macroBody ?: return null)
        var start = macroCallBody.mark()
        val macroCaseList = def.macroBodyStubbed?.macroCaseList ?: return null

        for (case in macroCaseList) {
            val subst = case.pattern.match(macroCallBody, wrap = wrap)
            if (subst != null) {
                return case to subst
            } else {
                start.rollbackTo()
                start = macroCallBody.mark()
            }
        }

        return null
    }

    private fun substituteMacro(root: PsiElement, subst: WithParent): String? =
        buildString { if (!substituteMacro(this, root, subst)) return null }

    private fun substituteMacro(sb: StringBuilder, root: PsiElement, subst: WithParent): Boolean {
        val children = generateSequence(root.firstChild) { it.nextSibling }.filter { it !is PsiComment }
        for (child in children) {
            when (child) {
                is RsMacroExpansion, is RsMacroExpansionContents ->
                    if (!substituteMacro(sb, child, subst)) return false
                is RsMacroReference ->
                    sb.safeAppend(subst.getVar(child.referenceName) ?: return false)
                is RsMacroExpansionReferenceGroup -> {
                    child.macroExpansionContents?.let { contents ->
                        val separator = child.macroExpansionGroupSeparator?.text ?: ""

                        val matchedGroup = subst.groups.singleOrNull()
                            ?: subst.groups.firstOrNull { it.definition.matches(contents) }
                            ?: return false

                        matchedGroup.substs.joinToWithBuffer(sb, separator) { sb ->
                            if (!substituteMacro(sb, contents, WithParent(this, subst))) return false
                        }
                    }
                }
                else -> {
                    sb.safeAppend(child.text)
                }
            }
        }
        return true
    }

    /** Ensures that the buffer ends (or [str] starts) with a whitespace and appends [str] to the buffer */
    private fun StringBuilder.safeAppend(str: String) {
        if (!isEmpty() && !last().isWhitespace() && !str.isEmpty() && !str.first().isWhitespace()) {
            append(" ")
        }
        append(str)
    }
}

/**
 * Returns (synthetic) path from [call] to [def]'s crate
 * We can't just expand `$crate` to something like `::crate_name` because
 * we can pass a result of `$crate` expansion to another macro as a single identifier.
 *
 * Let's look at the example:
 * ```
 * // foo_crate
 * macro_rules! foo {
 *     () => { bar!($crate); } // $crate consumed as a single identifier by `bar!`
 * }
 * // bar_crate
 * macro_rules! bar {
 *     ($i:ident) => { fn f() { $i::some_item(); } }
 * }
 * // baz_crate
 * mod baz {
 *     foo!();
 * }
 * ```
 * Imagine that macros `foo`, `bar` and the foo's call are located in different
 * crates. In this case, when expanding `foo!()`, we should expand $crate to
 * `::foo_crate`. This `::` is needed to make the path to the crate guaranteed
 * absolutely (and also to make it work on rust 2015 edition).
 * Ok, let's look at the result of single step of `foo` macro expansion:
 * `foo!()` => `bar!(::foo_crate)`. Syntactic construction `::foo_crate` consists
 * of 2 tokens: `::` and `foo_crate` (identifier). BUT `bar` expects a single
 * identifier as an input! And this is successfully complied by the rust compiler!!
 *
 * The secret is that we should not really expand `$crate` to `::foo_crate`.
 * We should expand it to "something" that can be passed to another macro
 * as a single identifier.
 *
 * Rustc figures it out by synthetic token (without text representation).
 * Rustc can do it this way because its macro substitution is based on AST.
 * But our expansion is text-based, so we must provide something textual
 * that can be parsed as an identifier.
 *
 * It's a very awful hack and we know it.
 * DON'T TRY THIS AT HOME
 */
private fun expandDollarCrateVar(call: RsMacroCall, def: RsMacro): String {
    val defTarget = def.containingCargoTarget
    val callTarget = call.containingCargoTarget
    val crateName = if (defTarget == callTarget) "self" else defTarget?.normName ?: ""
    return MACRO_CRATE_IDENTIFIER_PREFIX + crateName
}

/** Prefix for synthetic identifier produced from `$crate` metavar. See [expandDollarCrateVar] */
const val MACRO_CRATE_IDENTIFIER_PREFIX: String = "IntellijRustDollarCrate_"

private class MacroPattern private constructor(
    val pattern: Sequence<PsiElement>
) {
    fun match(macroCallBody: PsiBuilder, wrap: Boolean = true): MacroSubstitution? {
        return matchPartial(macroCallBody, wrap = wrap)?.let { result ->
            if (!macroCallBody.eof()) {
                MacroExpansionMarks.failMatchPatternByExtraInput.hit()
                null
            } else {
                result
            }
        }
    }

    private fun isEmpty() = pattern.firstOrNull() == null

    private fun matchPartial(macroCallBody: PsiBuilder, wrap: Boolean = true): MacroSubstitution? {
        ProgressManager.checkCanceled()
        val map = HashMap<String, String>()
        val groups = mutableListOf<MacroGroup>()

        for (psi in pattern) {
            when (psi) {
                is RsMacroBinding -> {
                    val name = psi.metaVarIdentifier.text ?: return null
                    val type = psi.fragmentSpecifier ?: return null

                    val lastOffset = macroCallBody.currentOffset
                    val parsed = parse(macroCallBody, type)
                    if (!parsed || (lastOffset == macroCallBody.currentOffset && type != "vis")) {
                        MacroExpansionMarks.failMatchPatternByBindingType.hit()
                        return null
                    }
                    val text = macroCallBody.originalText.substring(lastOffset, macroCallBody.currentOffset)

                    // Wrap expressions in () to avoid problems related to operator precedence during expansion
                    if (type == "expr" && wrap)
                        map[name] = "($text)"
                    else
                        map[name] = text
                }
                is RsMacroBindingGroup -> {
                    groups += MacroGroup(psi, matchGroup(psi, macroCallBody) ?: return null)
                }
                else -> {
                    if (!macroCallBody.isSameToken(psi)) {
                        MacroExpansionMarks.failMatchPatternByToken.hit()
                        return null
                    }
                }
            }
        }
        return MacroSubstitution(map, groups)
    }

    private fun parse(builder: PsiBuilder, type: String): Boolean {
        return if (type == "ident") {
            parseIdentifier(builder)
        } else {
            // we use similar logic as in org.rust.lang.core.parser.RustParser#parseLight
            val root = RsElementTypes.FUNCTION
            val adaptBuilder = GeneratedParserUtilBase.adapt_builder_(
                root,
                builder,
                RustParser(),
                RustParser.EXTENDS_SETS_
            )
            val marker = GeneratedParserUtilBase
                .enter_section_(adaptBuilder, 0, GeneratedParserUtilBase._COLLAPSE_, null)

            val parsed = when (type) {
                "path" -> RustParser.PathGenericArgsWithColons(adaptBuilder, 0)
                "expr" -> RustParser.Expr(adaptBuilder, 0, -1)
                "ty" -> RustParser.TypeReference(adaptBuilder, 0)
                "pat" -> RustParser.Pat(adaptBuilder, 0)
                "stmt" -> parseStatement(adaptBuilder)
                "block" -> RustParser.SimpleBlock(adaptBuilder, 0)
                "item" -> parseItem(adaptBuilder)
                "meta" -> RustParser.MetaItemWithoutTT(adaptBuilder, 0)
                "vis" -> parseVis(adaptBuilder)
                "tt" -> RustParser.TT(adaptBuilder, 0)
                "lifetime" -> RustParser.Lifetime(adaptBuilder, 0)
                "literal" -> RustParser.LitExpr(adaptBuilder, 0)
                else -> false
            }
            GeneratedParserUtilBase.exit_section_(adaptBuilder, 0, marker, root, parsed, true) { _, _ -> false }
            parsed
        }
    }

    private fun matchGroup(group: RsMacroBindingGroup, macroCallBody: PsiBuilder, wrap: Boolean = true): List<MacroSubstitution>? {
        val groups = mutableListOf<MacroSubstitution>()
        val pattern = MacroPattern.valueOf(group.macroPatternContents ?: return null)
        if (pattern.isEmpty()) return null
        val separator = group.macroBindingGroupSeparator?.firstChild
        var mark: PsiBuilder.Marker? = null

        while (true) {
            if (macroCallBody.eof()) {
                MacroExpansionMarks.groupInputEnd1.hit()
                mark?.rollbackTo()
                break
            }

            val lastOffset = macroCallBody.currentOffset
            val result = pattern.matchPartial(macroCallBody, wrap = wrap)
            if (result != null) {
                if (macroCallBody.currentOffset == lastOffset) {
                    MacroExpansionMarks.groupMatchedEmptyTT.hit()
                    return null
                }
                groups += result
            } else {
                MacroExpansionMarks.groupInputEnd2.hit()
                mark?.rollbackTo()
                break
            }

            if (macroCallBody.eof()) {
                MacroExpansionMarks.groupInputEnd3.hit()
                // Don't need to roll the marker back: we just successfully parsed a group
                break
            }

            if (group.q != null) {
                // `$(...)?` means "0 or 1 occurrences"
                MacroExpansionMarks.questionMarkGroupEnd.hit()
                break
            }

            if (separator != null) {
                mark?.drop()
                mark = macroCallBody.mark()
                if (!macroCallBody.isSameToken(separator)) {
                    MacroExpansionMarks.failMatchGroupBySeparator.hit()
                    break
                }
            }
        }

        val isExpectedAtLeastOne = group.plus != null
        if (isExpectedAtLeastOne && groups.isEmpty()) {
            MacroExpansionMarks.failMatchGroupTooFewElements.hit()
            return null
        }

        return groups
    }

    private fun parseIdentifier(b: PsiBuilder): Boolean {
        return if (b.tokenType in IDENTIFIER_TOKENS) {
            b.advanceLexer()
            true
        } else {
            false
        }
    }

    private fun parseStatement(b: PsiBuilder): Boolean =
        RustParser.LetDecl(b, 0) || RustParser.Expr(b, 0, -1)

    private fun parseItem(b: PsiBuilder): Boolean =
        parseItemFns.any { it(b, 0) }

    private fun parseVis(b: PsiBuilder): Boolean {
        RustParser.Vis(b, 0)
        return true // Vis can be empty
    }

    companion object {
        fun valueOf(psi: RsMacroPatternContents): MacroPattern =
            MacroPattern(psi.childrenSkipWhitespaceAndComments().flatten())

        private fun Sequence<PsiElement>.flatten(): Sequence<PsiElement> = flatMap {
            when (it) {
                is RsMacroPattern, is RsMacroPatternContents ->
                    it.childrenSkipWhitespaceAndComments().flatten()
                else -> sequenceOf(it)
            }
        }

        private fun PsiElement.childrenSkipWhitespaceAndComments(): Sequence<PsiElement> =
            generateSequence(firstChild) { it.nextSibling }
                .filter { it !is PsiWhiteSpace && it !is PsiComment }

        private val parseItemFns = listOf(
            RustParser::Constant,
            RustParser::TypeAlias,
            RustParser::Function,
            RustParser::TraitItem,
            RustParser::ImplItem,
            RustParser::ModItem,
            RustParser::ModDeclItem,
            RustParser::ForeignModItem,
            RustParser::StructItem,
            RustParser::EnumItem,
            RustParser::UseItem,
            RustParser::ExternCrateItem,
            RustParser::MacroCall
        )

        /**
         * Some tokens that treated as keywords by our lexer,
         * but rustc's macro parser treats them as identifiers
         */
        private val IDENTIFIER_TOKENS = TokenSet.orSet(tokenSetOf(RsElementTypes.IDENTIFIER), RS_KEYWORDS)
    }
}

private val RsMacroCase.pattern: MacroPattern
    get() = MacroPattern.valueOf(macroPattern.macroPatternContents)

private fun PsiBuilder.isSameToken(psi: PsiElement): Boolean {
    val (elementType, size) = collapsedTokenType(this) ?: (tokenType to 1)
    val result = psi.elementType == elementType && (elementType != IDENTIFIER || psi.text == tokenText)
    if (result) {
        PsiBuilderUtil.advance(this, size)
    }
    return result
}

private fun RsMacroBindingGroup.matches(contents: RsMacroExpansionContents): Boolean {
    val available = availableVars
    val used = contents.descendantsOfType<RsMacroReference>()
    return used.all { it.referenceName in available || it.referenceName == "crate" }
}

/**
 * Metavars available inside this group. Includes vars from this group, from all descendant groups
 * and from all ancestor groups. Sibling groups are excluded. E.g these vars available for these groups
 * ```
 * ($a [$b $($c)* ]    $($d)*      $($e           $($f)*)*)
 *         ^ a, b, c   ^ a, b, d   ^ a, b, e, f   ^ a, b, e, f
 * ```
 */
private val RsMacroBindingGroup.availableVars: Set<String>
    get() = CachedValuesManager.getCachedValue(this) {
        CachedValueProvider.Result.create(
            collectAvailableVars(this),
            ancestorStrict<RsMacro>()!!.modificationTracker
        )
    }

private fun collectAvailableVars(groupDefinition: RsMacroBindingGroup): Set<String> {
    val vars = groupDefinition.descendantsOfType<RsMacroBinding>().toMutableList()

    fun go(psi: PsiElement) {
        when (psi) {
            is RsMacroBinding -> vars += psi
            !is RsMacroBindingGroup -> psi.forEachChild(::go)
        }
    }

    groupDefinition.ancestors
        .drop(1)
        .takeWhile { it !is RsMacroCase }
        .forEach(::go)

    return vars.mapNotNullToSet { it.name }
}

object MacroExpansionMarks {
    val failMatchPatternByToken = Testmark("failMatchPatternByToken")
    val failMatchPatternByExtraInput = Testmark("failMatchPatternByExtraInput")
    val failMatchPatternByBindingType = Testmark("failMatchPatternByBindingType")
    val failMatchGroupBySeparator = Testmark("failMatchGroupBySeparator")
    val failMatchGroupTooFewElements = Testmark("failMatchGroupTooFewElements")
    val questionMarkGroupEnd = Testmark("questionMarkGroupEnd")
    val groupInputEnd1 = Testmark("groupInputEnd1")
    val groupInputEnd2 = Testmark("groupInputEnd2")
    val groupInputEnd3 = Testmark("groupInputEnd3")
    val groupMatchedEmptyTT = Testmark("groupMatchedEmptyTT")
}
