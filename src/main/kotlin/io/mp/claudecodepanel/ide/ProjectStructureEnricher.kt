package io.mp.claudecodepanel.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.activity.FilePackage
import io.mp.claudecodepanel.activity.SourceStructureParser
import io.mp.claudecodepanel.activity.StructuralRelation
import io.mp.claudecodepanel.activity.StructuralRelationKind
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Enriches files Claude touches with **real** project structure (Phase 2a): a file's package/module,
 * its type imports resolved to actual project files, and a test file's production target. Runs off the
 * EDT in a cancellable, smart-mode (post-indexing) [ReadAction.nonBlocking] read action, feeding
 * [StructuralRelation]/[FilePackage] events back on the UI thread. Enriches each path once per session
 * and only files Claude already touched — never a project-wide sweep.
 *
 * Resolution is index-based: a short type name links only when **exactly one** project file bears that
 * name (unambiguous), so an edge always points at a file that truly exists. Import/test parsing is done
 * by the platform-free [SourceStructureParser]; this class is the platform IO/index layer.
 */
class ProjectStructureEnricher(private val project: Project, private val parent: Disposable) {

    private val enriched = ConcurrentHashMap.newKeySet<String>()

    fun reset() = enriched.clear()

    fun enrich(path: String, sink: (List<AgentActivityEvent>) -> Unit) {
        if (!isSource(path) || !enriched.add(path)) return
        ReadAction.nonBlocking<List<AgentActivityEvent>> { compute(path) }
            .inSmartMode(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any()) { events -> if (events.isNotEmpty()) sink(events) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun compute(path: String): List<AgentActivityEvent> {
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return emptyList()
        if (vf.length > MAX_BYTES) return emptyList()
        val text = try { String(vf.contentsToByteArray(), StandardCharsets.UTF_8) } catch (e: Exception) { return emptyList() }
        val parsed = SourceStructureParser.parse(text, path)
        val now = Instant.now()
        val scope = GlobalSearchScope.projectScope(project)
        val out = ArrayList<AgentActivityEvent>()

        parsed.packageName?.let { pkg ->
            out.add(FilePackage(path, pkg, ModuleUtilCore.findModuleForFile(vf, project)?.name, now))
        }
        var links = 0
        for (name in parsed.importedTypes) {
            if (links >= MAX_LINKS) break
            val target = resolveUniqueType(name, scope, vf) ?: continue
            out.add(StructuralRelation(path, target.path, name, StructuralRelationKind.IMPORTS, now))
            links++
        }
        parsed.testTargetType?.let { prod ->
            resolveUniqueType(prod, scope, vf)?.let {
                out.add(StructuralRelation(path, it.path, prod, StructuralRelationKind.TESTS, now))
            }
        }
        return out
    }

    /** A project file named `<Type>.{kt,kts,java}`, only when exactly one such file exists (unambiguous). */
    private fun resolveUniqueType(typeName: String, scope: GlobalSearchScope, self: VirtualFile): VirtualFile? {
        val matches = LinkedHashSet<VirtualFile>()
        for (ext in EXTS) {
            for (f in FilenameIndex.getVirtualFilesByName("$typeName.$ext", scope)) {
                if (f != self) matches.add(f)
                if (matches.size > 1) return null // ambiguous — don't guess
            }
        }
        return matches.singleOrNull()
    }

    private fun isSource(path: String): Boolean = EXTS.any { path.endsWith(".$it") }

    private companion object {
        val EXTS = listOf("kt", "kts", "java")
        const val MAX_LINKS = 30
        const val MAX_BYTES = 400_000L
    }
}
