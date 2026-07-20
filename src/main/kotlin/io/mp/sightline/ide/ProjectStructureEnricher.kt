package io.mp.sightline.ide

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
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElementOfType
import io.mp.sightline.activity.AgentActivityEvent
import io.mp.sightline.activity.AndroidResourceParser
import io.mp.sightline.activity.FilePackage
import io.mp.sightline.activity.NavGraphParser
import io.mp.sightline.activity.SourceStructureParser
import io.mp.sightline.activity.StructuralRelation
import io.mp.sightline.activity.StructuralRelationKind
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
        if (!isEnrichable(path)) return
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
        val now = Instant.now()
        val fileIndex = ProjectFileIndex.getInstance(project)
        // Android resource files: link to the sources that reference the resource, plus (for a nav graph)
        // forward to its destination screens.
        AndroidResourceParser.resourceRef(sourcePath)?.let { ref ->
            val out = ArrayList<AgentActivityEvent>()
            if (isNavResource(sourcePath)) out += computeNav(psiFile.text, sourcePath, now, fileIndex, vf)
            out += computeResourceReferrers(ref, sourcePath, now, fileIndex, vf)
            return out
        }
        val parsed = SourceStructureParser.parse(psiFile.text, sourcePath)
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

    /**
     * Resolves a navigation graph's destinations (`android:name` classes) to project files, emitting a
     * [StructuralRelationKind.NAVIGATES_TO] per unambiguously-resolved destination. The FQN is declared
     * explicitly in the resource; we link it to the single project file of that short name (never guess
     * when ambiguous), so a nav edge always points at a file that really exists.
     */
    private fun computeNav(
        text: String, sourcePath: String, now: Instant, fileIndex: ProjectFileIndex, self: VirtualFile,
    ): List<AgentActivityEvent> {
        val out = ArrayList<AgentActivityEvent>()
        var links = 0
        for (dest in NavGraphParser.parse(text)) {
            if (links >= MAX_LINKS) break
            val shortName = dest.className.substringAfterLast('.')
            val target = resolveUniqueType(shortName, GlobalSearchScope.allScope(project), fileIndex, self) ?: continue
            out.add(StructuralRelation(sourcePath, target.path, shortName, StructuralRelationKind.NAVIGATES_TO, now))
            links++
        }
        return out
    }

    /**
     * On-demand **find usages** (the lazy P1 tier): project files that reference the type(s) declared in
     * [vf], via precise PSI [ReferencesSearch]. Run only when the user explicitly asks (an inspector
     * action) — never during automatic enrichment; the broad/eager version is deferred to Phase 2b.
     * Emits [StructuralRelationKind.REFERENCED_BY] relations. Must run inside a read action.
     */
    internal fun findUsages(vf: VirtualFile, sourcePath: String = vf.path): List<AgentActivityEvent> {
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return emptyList()
        val uFile = try { psiFile.toUElementOfType<UFile>() } catch (e: Exception) { null } ?: return emptyList()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val now = Instant.now()
        val referrers = LinkedHashSet<String>()
        val out = ArrayList<AgentActivityEvent>()
        for (uClass in uFile.classes) {
            if (referrers.size >= MAX_LINKS) break
            ReferencesSearch.search(uClass.javaPsi, GlobalSearchScope.projectScope(project)).forEach { ref ->
                val refVf = ref.element.containingFile?.virtualFile ?: return@forEach
                if (refVf != vf && fileIndex.isInContent(refVf) && referrers.size < MAX_LINKS && referrers.add(refVf.path)) {
                    out.add(StructuralRelation(sourcePath, refVf.path, refVf.nameWithoutExtension, StructuralRelationKind.REFERENCED_BY, now))
                }
            }
        }
        return out
    }

    /** Off-EDT [findUsages] for the inspector's "Find usages" action; delivers results on the UI thread. */
    fun findUsagesAsync(path: String, sink: (List<AgentActivityEvent>) -> Unit) {
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        ReadAction.nonBlocking<List<AgentActivityEvent>> { findUsages(vf) }
            .inSmartMode(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any()) { events -> if (events.isNotEmpty()) sink(events) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Reverse resource lookup: finds project sources that reference a touched resource by
     * `R.<type>.<name>` or `@<type>/<name>`, emitting [StructuralRelationKind.REFERENCED_BY]. The word
     * index bounds the scan to files that mention the resource name; each candidate is then confirmed to
     * contain a *real* reference (not a coincidental identifier), so an edge is never guessed.
     */
    private fun computeResourceReferrers(
        ref: AndroidResourceParser.ResourceRef, resourcePath: String, now: Instant,
        fileIndex: ProjectFileIndex, self: VirtualFile,
    ): List<AgentActivityEvent> {
        val referrers = LinkedHashSet<VirtualFile>()
        val psiManager = PsiManager.getInstance(project)
        PsiSearchHelper.getInstance(project).processCandidateFilesForText(
            GlobalSearchScope.projectScope(project), UsageSearchContext.ANY, true, ref.name,
        ) { vf ->
            if (vf != self && fileIndex.isInContent(vf) && vf.length <= MAX_BYTES) {
                val text = psiManager.findFile(vf)?.text
                if (text != null && AndroidResourceParser.isReferencedIn(text, ref)) referrers.add(vf)
            }
            referrers.size < MAX_LINKS
        }
        return referrers.map { StructuralRelation(resourcePath, it.path, it.nameWithoutExtension, StructuralRelationKind.REFERENCED_BY, now) }
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

    /** An Android navigation graph resource: an XML file under `res/navigation/`. */
    private fun isNavResource(path: String): Boolean =
        path.endsWith(".xml") && (path.contains("/navigation/") || path.contains("\\navigation\\"))

    private fun isEnrichable(path: String): Boolean = isSource(path) || AndroidResourceParser.resourceRef(path) != null

    private companion object {
        val EXTS = listOf("kt", "kts", "java")
        const val MAX_LINKS = 30
        const val MAX_BYTES = 400_000L
    }
}
