package io.mp.claudecodepanel.ide.android

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import io.mp.claudecodepanel.android.AdbOutputParsers
import io.mp.claudecodepanel.android.AndroidContext
import io.mp.claudecodepanel.android.DeviceContext
import io.mp.claudecodepanel.android.EditorContext
import io.mp.claudecodepanel.android.Fact
import io.mp.claudecodepanel.android.FactTier
import io.mp.claudecodepanel.android.GradleModuleInfo
import io.mp.claudecodepanel.android.GradleModuleParser
import io.mp.claudecodepanel.android.ModuleContext
import io.mp.claudecodepanel.android.OutputMetadata
import io.mp.claudecodepanel.android.OutputMetadataParser
import io.mp.claudecodepanel.android.VariantName
import io.mp.claudecodepanel.android.VersionCatalogParser
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Walks the fact ladder (docs/ANDROID.md §1.2) and produces an [AndroidContext].
 *
 * The ladder in practice, per fact, strongest first:
 *  1. **IDE** — [AndroidStudioFactProvider], the only source that knows the *currently selected* variant.
 *  2. **Build output** — AGP's `output-metadata.json` and the `merged_manifest/<variant>/` directory
 *     names. Accurate, but describes the last build.
 *  3. **Static parse** — `build.gradle(.kts)` and `libs.versions.toml`. Declared, not resolved.
 *  4. **Device** — `adb shell getprop`, for facts that are only true of a running device.
 *
 * Everything here does file IO or shells out, so **[resolve] must be called off the EDT**. Results are
 * cached with a short TTL: the strip re-reads on every send and every editor change, and re-walking the
 * build tree at that rate would be a real cost for facts that change on the order of minutes.
 */
@Service(Service.Level.PROJECT)
class AndroidContextResolver(private val project: Project) {

    private data class Cached(val context: AndroidContext, val at: Long)

    private val cache = AtomicReference<Cached?>()

    /** Long enough that typing is free, short enough that starting an emulator shows up on its own. */
    private val ttlMs = 15_000L

    fun cachedOrNull(): AndroidContext? = cache.get()?.context

    fun invalidate() = cache.set(null)

    /**
     * Off-EDT by contract. [force] skips the TTL — what the Refresh affordance uses.
     *
     * The editor fact is re-read on **every** call, including a cache hit. It costs one `ReadAction`
     * where the rest of the context costs a build-tree walk and an adb round-trip, and it is the fact
     * that changes most often: caching it for 15 seconds would leave the "Current file" chip naming a
     * file the user had already navigated away from, which is precisely the stale-fact failure this
     * whole design exists to avoid.
     */
    fun resolve(force: Boolean = false, now: Long = System.currentTimeMillis()): AndroidContext {
        if (!force) {
            cache.get()?.let { c ->
                if (now - c.at < ttlMs) return c.context.withFreshEditor()
            }
        }
        val resolved = try {
            compute()
        } catch (e: Exception) {
            thisLogger().info("android: context resolution failed (${e.javaClass.simpleName})")
            AndroidContext.NOT_ANDROID
        }
        cache.set(Cached(resolved, now))
        return resolved
    }

    /**
     * Re-reads the open file and recomputes which module owns it. Both have to move together — a chip
     * naming a file in `:core` beside a variant belonging to `:app` describes no state that exists.
     */
    private fun AndroidContext.withFreshEditor(): AndroidContext {
        if (notAndroid) return this
        val base = project.basePath?.let { File(it) } ?: return this
        val editor = readEditorContext(base)
        if (editor?.relativePath == this.editor?.relativePath &&
            editor?.selectionLines == this.editor?.selectionLines
        ) {
            return this
        }
        return copy(editor = editor, activeModuleName = pickActiveModule(modules, editor)?.name ?: activeModuleName)
    }

    /** The module owning the open file, else the one that produces an app, else the first. */
    private fun pickActiveModule(modules: List<ModuleContext>, editor: EditorContext?): ModuleContext? =
        modules.firstOrNull { m ->
            editor?.relativePath?.startsWith(GradleModuleParser.gradlePathToDirectory(m.gradlePath) + "/") == true
        } ?: modules.firstOrNull { it.applicationId.isKnown } ?: modules.firstOrNull()

    private fun compute(): AndroidContext {
        val env = AndroidEnvironment.getInstance(project)
        if (!env.looksLikeAndroidProject()) return AndroidContext.NOT_ANDROID
        val base = project.basePath?.let { File(it) } ?: return AndroidContext.NOT_ANDROID

        val studio = AndroidStudioFactProvider.facts(project)
        val modules = discoverModules(base).map { (gradlePath, dir) ->
            buildModule(gradlePath, dir, studio?.modules?.firstOrNull { it.name == GradleModuleParser.gradlePathToName(gradlePath) })
        }.filter { it.isAndroid }.map { it.context }

        val editor = readEditorContext(base)
        val active = pickActiveModule(modules, editor)

        return AndroidContext(
            modules = modules,
            activeModuleName = active?.name,
            device = resolveDevice(env, active?.applicationId?.value),
            editor = editor,
            versionCatalog = readVersionCatalog(base),
        )
    }

    // ---- modules ----

    private data class ModuleResult(val context: ModuleContext, val isAndroid: Boolean)

    /** Gradle paths from `settings.gradle(.kts)`, falling back to a shallow directory scan. */
    private fun discoverModules(base: File): List<Pair<String, File>> {
        val settings = listOf("settings.gradle.kts", "settings.gradle")
            .map { File(base, it) }.firstOrNull { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }

        val paths = settings?.let { GradleModuleParser.parseIncludes(it) }.orEmpty()
        if (paths.isNotEmpty()) {
            return paths.mapNotNull { p ->
                val dir = File(base, GradleModuleParser.gradlePathToDirectory(p))
                if (dir.isDirectory) p to dir else null
            }
        }
        // No settings file we could read — fall back to top-level directories that have a build file.
        return base.listFiles()?.filter { it.isDirectory && hasBuildFile(it) }
            ?.map { ":${it.name}" to it }.orEmpty()
    }

    private fun hasBuildFile(dir: File) =
        File(dir, "build.gradle.kts").isFile || File(dir, "build.gradle").isFile

    private fun buildModule(gradlePath: String, dir: File, studio: StudioModule?): ModuleResult {
        val name = GradleModuleParser.gradlePathToName(gradlePath)
        val declared = readBuildFile(dir)?.let { GradleModuleParser.parse(it) } ?: GradleModuleInfo()
        val isAndroid = declared.isApplication != null || declared.namespace != null || studio != null

        val built = builtVariants(dir)
        val metadata = readOutputMetadata(dir)

        // Variant: the one fact with no tier-2 substitute for "currently selected" — tier 2 can only say
        // what was last built, which is why the qualifier matters so much here.
        val variant = Fact.ladder(
            { studio?.selectedVariant?.let { Fact.known(it, FactTier.IDE) } ?: Fact.unknown("no IDE model") },
            {
                metadata?.variantName?.let { Fact.known(it, FactTier.BUILD_OUTPUT) }
                    ?: built.singleOrNull()?.let { Fact.known(it, FactTier.BUILD_OUTPUT) }
                    ?: Fact.unknown(if (built.size > 1) "several variants built, none selected" else "nothing built yet")
            },
        )

        val flavorsInOrder = studio?.flavors?.takeIf { it.isNotEmpty() } ?: declared.flavorsInDimensionOrder
        val parts = variant.value?.let { VariantName.decompose(it, flavorsInOrder) }

        return ModuleResult(
            ModuleContext(
                name = name,
                gradlePath = gradlePath,
                variant = variant,
                flavors = parts?.flavors?.takeIf { it.isNotEmpty() }
                    ?.let { Fact.known(it, variant.tier) } ?: Fact.unknown(),
                buildType = parts?.buildType?.let { Fact.known(it, variant.tier) } ?: Fact.unknown(),
                applicationId = Fact.ladder(
                    { studio?.applicationId?.let { Fact.known(it, FactTier.IDE) } ?: Fact.unknown() },
                    // Tier 2 is *better* than the build file here: the recorded id already has the
                    // flavour's applicationIdSuffix applied, which the declared defaultConfig does not.
                    { metadata?.applicationId?.let { Fact.known(it, FactTier.BUILD_OUTPUT) } ?: Fact.unknown() },
                    {
                        declared.applicationId?.let {
                            Fact.known(it, FactTier.STATIC_PARSE, "defaultConfig, before any flavour suffix")
                        } ?: Fact.unknown()
                    },
                ),
                minSdk = intLadder(studio?.minSdk, declared.minSdk),
                targetSdk = intLadder(studio?.targetSdk, declared.targetSdk),
                compileSdk = declared.compileSdk?.let { Fact.known(it, FactTier.STATIC_PARSE) } ?: Fact.unknown(),
                versionName = metadata?.versionName?.let { Fact.known(it, FactTier.BUILD_OUTPUT) } ?: Fact.unknown(),
                versionCode = metadata?.versionCode?.let { Fact.known(it, FactTier.BUILD_OUTPUT) } ?: Fact.unknown(),
                usesCompose = declared.usesCompose?.let { Fact.known(it, FactTier.STATIC_PARSE) } ?: Fact.unknown(),
                builtVariants = built,
            ),
            isAndroid,
        )
    }

    private fun intLadder(ide: Int?, declared: Int?): Fact<Int> = Fact.ladder(
        { ide?.let { Fact.known(it, FactTier.IDE) } ?: Fact.unknown() },
        { declared?.let { Fact.known(it, FactTier.STATIC_PARSE) } ?: Fact.unknown() },
    )

    private fun readBuildFile(dir: File): String? = listOf("build.gradle.kts", "build.gradle")
        .map { File(dir, it) }.firstOrNull { it.isFile }
        ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }

    /**
     * Variant names from `build/intermediates/merged_manifest/<variant>/`. The directory *names* are
     * themselves a fact — they are the variants this module has actually built.
     */
    private fun builtVariants(dir: File): List<String> {
        val roots = listOf("merged_manifest", "merged_manifests")
            .map { File(dir, "build/intermediates/$it") }
        return roots.filter { it.isDirectory }
            .flatMap { it.listFiles()?.filter { f -> f.isDirectory }?.map { f -> f.name }.orEmpty() }
            .distinct()
            .sorted()
    }

    /** The most recently written `output-metadata.json` under this module's APK outputs. */
    private fun readOutputMetadata(dir: File): OutputMetadata? {
        val apkRoot = File(dir, "build/intermediates/apk")
        if (!apkRoot.isDirectory) return null
        val newest = apkRoot.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name == "output-metadata.json" }
            .maxByOrNull { it.lastModified() } ?: return null
        val text = runCatching { newest.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return OutputMetadataParser.parse(text)
    }

    private fun readVersionCatalog(base: File): Map<String, String> {
        val toml = File(base, "gradle/libs.versions.toml").takeIf { it.isFile }
            ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() } ?: return emptyMap()
        return VersionCatalogParser.highlights(VersionCatalogParser.parseVersions(toml))
    }

    // ---- device (tier 4) ----

    private fun resolveDevice(env: AndroidEnvironment, applicationId: String?): DeviceContext? {
        val result = env.adb("devices", "-l", timeoutMs = 6000) ?: return null
        if (!result.ok) return null
        val devices = AdbOutputParsers.parseDevices(result.stdout)
        // Prefer a device that can actually do something; otherwise show the problem one, because
        // "unauthorised" is the answer to "why isn't anything working".
        val device = devices.firstOrNull { it.state.isUsable } ?: devices.firstOrNull() ?: return null

        val props = if (device.state.isUsable) readProps(env, device.serial) else emptyMap()
        return DeviceContext(
            serial = device.serial,
            name = device.displayName,
            state = device.state,
            apiLevel = props["ro.build.version.sdk"]?.toIntOrNull()
                ?.let { Fact.known(it, FactTier.DEVICE) } ?: Fact.unknown(),
            androidRelease = props["ro.build.version.release"]
                ?.let { Fact.known(it, FactTier.DEVICE) } ?: Fact.unknown(),
            appRunning = if (applicationId == null || !device.state.isUsable) Fact.unknown()
            else isRunning(env, device.serial, applicationId),
        )
    }

    /** One `getprop` round-trip for both properties — two would double the latency of every strip refresh. */
    private fun readProps(env: AndroidEnvironment, serial: String): Map<String, String> {
        val r = env.adb(
            "-s", serial, "shell",
            "getprop ro.build.version.sdk; getprop ro.build.version.release",
            timeoutMs = 5000,
        ) ?: return emptyMap()
        if (!r.ok) return emptyMap()
        val lines = r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return buildMap {
            lines.getOrNull(0)?.let { put("ro.build.version.sdk", it) }
            lines.getOrNull(1)?.let { put("ro.build.version.release", it) }
        }
    }

    private fun isRunning(env: AndroidEnvironment, serial: String, applicationId: String): Fact<Boolean> {
        val r = env.adb("-s", serial, "shell", "pidof", applicationId, timeoutMs = 4000)
            ?: return Fact.unknown()
        // `pidof` exits non-zero when nothing matches — that is a real "not running", not a failure.
        return when {
            r.timedOut -> Fact.unknown("the device didn't answer")
            r.stdout.trim().any { it.isDigit() } -> Fact.known(true, FactTier.DEVICE)
            else -> Fact.known(false, FactTier.DEVICE)
        }
    }

    // ---- editor ----

    private fun readEditorContext(base: File): EditorContext? = try {
        ReadAction.compute<EditorContext?, RuntimeException> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@compute null
            val relative = relativize(file.path, base.path) ?: return@compute null
            val selection = editor.selectionModel.takeIf { it.hasSelection() }?.let { sel ->
                val start = editor.offsetToLogicalPosition(sel.selectionStart).line + 1
                val end = editor.offsetToLogicalPosition(sel.selectionEnd).line + 1
                start..end
            }
            EditorContext(relativePath = relative, selectionLines = selection)
        }
    } catch (e: Exception) {
        null
    }

    private fun relativize(path: String, root: String): String? {
        val p = path.replace('\\', '/')
        val r = root.replace('\\', '/').trimEnd('/')
        return if (p.startsWith("$r/")) p.removePrefix("$r/") else null
    }

    companion object {
        fun getInstance(project: Project): AndroidContextResolver =
            project.getService(AndroidContextResolver::class.java)
    }
}
