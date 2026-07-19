package io.mp.claudecodepanel.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElementOfType
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.activity.FilePackage
import io.mp.claudecodepanel.activity.SourceStructureParser
import io.mp.claudecodepanel.activity.StructuralRelation
import io.mp.claudecodepanel.activity.StructuralRelationKind
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

    // path -> modification stamp last enriched: an unchanged file isn't re-read on every tool_result,
    // but an edited one is picked up again (new imports / supertypes).
    private val enriched = ConcurrentHashMap<String, Long>()

    fun reset() = enriched.clear()

    /**
     * Enrich a touched file. [edited] = Claude just wrote it — its Edit/Write tools change the file
     * **on disk** (not through the IDE), so we async-refresh the VFS and re-enrich unconditionally; a
     * read (`edited = false`) dedups by modification stamp so repeated reads don't re-run the read action.
     */
    fun enrich(path: String, edited: Boolean, sink: (List<AgentActivityEvent>) -> Unit) {
        if (!isSource(path)) return
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        if (edited) {
            enriched.remove(path)
            vf.refresh(true, false) {
                enriched[path] = vf.modificationStamp
                submitEnrich(vf, path, sink)
            }
        } else {
            val stamp = vf.modificationStamp
            if (enriched.put(path, stamp) == stamp) return // already enriched at this version
            submitEnrich(vf, path, sink)
        }
    }

    private fun submitEnrich(vf: VirtualFile, path: String, sink: (List<AgentActivityEvent>) -> Unit) {
        ReadAction.nonBlocking<List<AgentActivityEvent>> { computeFor(vf, path) }
            .inSmartMode(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any()) { events -> if (events.isNotEmpty()) sink(events) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * The synchronous core (must run inside a read action). [sourcePath] is the path Claude used, so the
     * emitted events line up with the graph node created for it. Exposed for the platform test fixture.
     */
    internal fun computeFor(vf: VirtualFile, sourcePath: String = vf.path): List<AgentActivityEvent> {
        if (vf.length > MAX_BYTES) return emptyList()
        // Read via PSI (not raw VFS bytes): works for real files and in-memory test fixtures alike, and
        // reuses the PsiFile we need for UAST anyway.
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return emptyList()
        val parsed = SourceStructureParser.parse(psiFile.text, sourcePath)
        val now = Instant.now()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val out = ArrayList<AgentActivityEvent>()

        parsed.packageName?.let { pkg ->
            out.add(FilePackage(sourcePath, pkg, ModuleUtilCore.findModuleForFile(vf, project)?.name, now))
        }
        // Imports + class hierarchy from real PSI via UAST — precise, package-aware, and resolved through
        // PSI references (not the global name index), so it works cleanly in tests too. Quiet if the
        // language has no UAST provider.
        val uFile = try { psiFile.toUElementOfType<UFile>() } catch (e: Exception) { null }
        if (uFile != null) {
            addImports(uFile, sourcePath, now, fileIndex, vf, out)
            addSupertypes(uFile, sourcePath, now, fileIndex, out)
        }
        // Test → production: nothing to resolve via PSI, so match by unique short name in the index.
        parsed.testTargetType?.let { prod ->
            resolveUniqueType(prod, GlobalSearchScope.allScope(project), fileIndex, vf)?.let {
                out.add(StructuralRelation(sourcePath, it.path, prod, StructuralRelationKind.TESTS, now))
            }
        }
        return out
    }

    /** Type imports resolved to their exact declaration; member/wildcard imports resolve to non-classes. */
    private fun addImports(
        uFile: UFile, sourcePath: String, now: Instant, fileIndex: ProjectFileIndex,
        self: VirtualFile, out: MutableList<AgentActivityEvent>,
    ) {
        var links = 0
        for (imp in uFile.imports) {
            if (links >= MAX_LINKS) break
            val cls = imp.resolve() as? PsiClass ?: continue
            val target = cls.containingFile?.virtualFile ?: continue
            if (target == self || !fileIndex.isInContent(target)) continue
            out.add(StructuralRelation(sourcePath, target.path, cls.name ?: "?", StructuralRelationKind.IMPORTS, now))
            links++
        }
    }

    /**
     * Class hierarchy via UAST — `javaPsi.superClass` / `.interfaces` resolve uniformly for Kotlin and
     * Java and point at the exact declaration. Only project (in-content) supertypes are linked; library
     * roots (`Object`, `Any`, framework bases) are skipped.
     */
    private fun addSupertypes(uFile: UFile, path: String, now: Instant, fileIndex: ProjectFileIndex, out: MutableList<AgentActivityEvent>) {
        var added = 0
        for (uClass in uFile.classes) {
            val psi = uClass.javaPsi
            psi.superClass?.let { if (relate(it, path, StructuralRelationKind.EXTENDS, now, fileIndex, out)) added++ }
            for (iface in psi.interfaces) {
                if (added >= MAX_LINKS) break
                if (relate(iface, path, StructuralRelationKind.IMPLEMENTS, now, fileIndex, out)) added++
            }
            if (added >= MAX_LINKS) break
        }
    }

    private fun relate(
        superClass: PsiClass, sourcePath: String, kind: StructuralRelationKind, now: Instant,
        fileIndex: ProjectFileIndex, out: MutableList<AgentActivityEvent>,
    ): Boolean {
        val name = superClass.name ?: return false
        if (name == "Object" || name == "Any") return false // implicit roots
        val targetVf = superClass.containingFile?.virtualFile ?: return false
        if (targetVf.path == sourcePath || !fileIndex.isInContent(targetVf)) return false // project files only
        out.add(StructuralRelation(sourcePath, targetVf.path, name, kind, now))
        return true
    }

    /** A project (in-content) file named `<Type>.{kt,kts,java}`, only when exactly one exists (unambiguous). */
    private fun resolveUniqueType(
        typeName: String, scope: GlobalSearchScope, fileIndex: ProjectFileIndex, self: VirtualFile,
    ): VirtualFile? {
        val matches = LinkedHashSet<VirtualFile>()
        for (ext in EXTS) {
            for (f in FilenameIndex.getVirtualFilesByName("$typeName.$ext", scope)) {
                if (f != self && fileIndex.isInContent(f)) matches.add(f)
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
