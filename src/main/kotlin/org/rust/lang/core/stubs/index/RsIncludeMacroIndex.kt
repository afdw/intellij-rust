/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.stubs.index

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.PathUtil
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.findIncludingFile
import org.rust.lang.core.psi.ext.includingFilePath
import org.rust.lang.core.psi.rustStructureOrAnyPsiModificationTracker
import org.rust.lang.core.stubs.RsFileStub
import org.rust.lang.core.stubs.RsMacroCallStub

class RsIncludeMacroIndex : StringStubIndexExtension<RsMacroCall>() {
    override fun getVersion(): Int = RsFileStub.Type.stubVersion
    override fun getKey(): StubIndexKey<String, RsMacroCall> = KEY

    companion object {
        val KEY : StubIndexKey<String, RsMacroCall> =
            StubIndexKey.createIndexKey("org.rust.lang.core.stubs.index.RsIncludeMacroIndex")

        fun index(stub: RsMacroCallStub, indexSink: IndexSink) {
            val key = key(stub.psi) ?: return
            indexSink.occurrence(KEY, key)
        }

        /**
         * Returns mod item that includes given [file] via `include!()` macro
         */
        fun getIncludingMod(file: RsFile): RsMod? {
            return CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(
                    getIncludingModInternal(file),
                    file.rustStructureOrAnyPsiModificationTracker
                )
            }
        }

        private fun getIncludingModInternal(file: RsFile): RsMod? {
            val key = file.name
            val project = file.project

            var parentMod: RsMod? = null
            StubIndex.getInstance().processElements(KEY, key, project, GlobalSearchScope.allScope(project), RsMacroCall::class.java) { macroCall ->
                val includingFile = macroCall.findIncludingFile()
                if (includingFile == file) {
                    parentMod = macroCall.containingMod
                    false
                } else {
                    true
                }
            }
            return parentMod
        }

        private fun key(call: RsMacroCall): String? {
            val path = call.includingFilePath ?: return null
            return PathUtil.getFileName(path)
        }
    }
}
