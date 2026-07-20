package io.mp.claudecodepanel.ide.android

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.mp.claudecodepanel.android.ComposeFinding
import io.mp.claudecodepanel.android.ComposeSourceAnalyzer
import io.mp.claudecodepanel.android.DeviceActions
import io.mp.claudecodepanel.android.DumpsysParser
import io.mp.claudecodepanel.android.ScreenState
import io.mp.claudecodepanel.settings.ClaudeSettings
import java.io.File

/**
 * Screen inspection and Compose analysis — the `android.inspectScreen` / `android.analyzeCompose` tools.
 *
 * Both are built around being explicit about a limit rather than papering over it.
 *
 * `inspectScreen` reports the **Activity** level, plus window configuration. It cannot see which
 * composable or navigation route is on screen: those live in the app's own memory, and `dumpsys` has no
 * view of them. The response says so, so a model doesn't infer a route from an activity name and present
 * it as observed. Where a Compose route *can* be established — from source — that is a separate tool
 * whose answer is labelled as coming from source.
 *
 * A **screenshot is never captured here.** It is a picture of whatever real data is on that screen, and
 * the roadmap's §1.4 rule is that capture requires per-capture human consent: the image is shown before
 * it is attached, with attach/discard. That flow belongs to the UI, so the MCP surface exposes only the
 * *request*, and the panel decides.
 */
class AndroidScreenTools(private val project: Project) {

    private val names = setOf("android.inspectScreen", "android.analyzeCompose")

    fun handles(name: String): Boolean = name in names

    fun addToolDefs(tools: JsonArray) {
        if (!enabled()) return
        tools.add(
            tool(
                "android.inspectScreen",
                "What is on screen right now: the resumed Activity, the back stack, and the window " +
                    "configuration (size, density, orientation, dark mode, font scale, locale). " +
                    "**This is Activity-level only** — it cannot see the current Compose route or " +
                    "composable, because those exist only in the app's memory. Do not infer a route " +
                    "from the Activity name and state it as observed.",
                JsonObject(),
            ),
        )
        tools.add(
            tool(
                "android.analyzeCompose",
                "Analyse a Kotlin file's composables: which are declared, which have @Preview and what " +
                    "those previews cover (dark theme, font scale, device size), plus findings that are " +
                    "unambiguous in the source — a screen-level composable with no preview, " +
                    "`mutableStateOf` without `remember`, an Image with no contentDescription. " +
                    "**Deliberately does not claim anything about recomposition counts or parameter " +
                    "stability** — those are not decidable from source text; read the file yourself for that.",
                JsonObject().apply {
                    add("file", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Project-relative path. Defaults to the open file.")
                    })
                },
            ),
        )
    }

    /** Off the EDT by contract (the WebSocket thread). */
    fun call(name: String, args: JsonObject): String = when (name) {
        "android.inspectScreen" -> inspectScreen()
        "android.analyzeCompose" -> analyzeCompose(args)
        else -> err("Unknown tool: $name")
    }

    // ---- screen ----

    private fun inspectScreen(): String {
        val env = AndroidEnvironment.getInstance(project)
        val context = AndroidContextResolver.getInstance(project).resolve()
        val device = context.device?.takeIf { it.state.isUsable }
            ?: return err("No usable device is connected, so nothing can be inspected.")

        val dumps = DumpsysParser.probes().associate { (key, args) ->
            key to env.adb(*(listOf("-s", device.serial) + args).toTypedArray(), timeoutMs = 8000)
                ?.takeIf { it.ok }?.stdout.orEmpty()
        }
        val state = DumpsysParser.merge(
            DumpsysParser.parseActivities(dumps["activities"].orEmpty()),
            DumpsysParser.parseWindow(dumps["window"].orEmpty()),
            DumpsysParser.parseConfiguration(dumps["uimode"].orEmpty()),
            DumpsysParser.parseConfiguration(dumps["config"].orEmpty()),
        )
        if (state.isEmpty) {
            return json {
                addProperty("available", false)
                addProperty("reason", "dumpsys returned nothing usable — the device answered but the format wasn't recognised.")
            }
        }

        val ours = state.resumedPackage != null &&
            state.resumedPackage == context.activeModule?.applicationId?.value

        return json {
            addProperty("available", true)
            addProperty("summary", state.summary())
            state.resumedActivity?.let { addProperty("resumedActivity", it) }
            state.resumedPackage?.let { addProperty("resumedPackage", it) }
            addProperty("isThisApp", ours)
            add("backStack", JsonArray().apply { state.backStack.forEach { add(it) } })
            add("window", JsonObject().apply {
                state.screenWidthPx?.let { addProperty("widthPx", it) }
                state.screenHeightPx?.let { addProperty("heightPx", it) }
                state.densityDpi?.let { addProperty("densityDpi", it) }
                state.orientation?.let { addProperty("orientation", it.name.lowercase()) }
                state.nightMode?.let { addProperty("darkMode", it) }
                state.fontScale?.let { addProperty("fontScale", it) }
                state.locale?.let { addProperty("locale", it) }
            })
            if (!ours && state.resumedPackage != null) {
                addProperty(
                    "warning",
                    "The foreground app is ${state.resumedPackage}, not this project's app. " +
                        "Whatever is on screen is not yours.",
                )
            }
            addProperty(
                "limits",
                "Activity-level only. The current Compose route, the composable on screen and the " +
                    "semantics tree are not visible to dumpsys — a semantics dump additionally requires " +
                    "`testTagsAsResourceId` to be enabled in the app. Absent fields were not determined, " +
                    "not determined to be absent.",
            )
        }
    }

    // ---- Compose ----

    private fun analyzeCompose(args: JsonObject): String {
        val context = AndroidContextResolver.getInstance(project).resolve()
        val relative = args.str("file") ?: context.editor?.relativePath
            ?: return err("No file given and no file is open.")
        if (!relative.endsWith(".kt")) return err("$relative is not a Kotlin file.")

        val base = project.basePath ?: return err("No project directory.")
        val file = File(base, relative)
        val source = runCatching {
            // Prefer the in-memory document so unsaved edits are analysed, not the last-saved text.
            ReadAction.compute<String?, RuntimeException> {
                LocalFileSystem.getInstance().findFileByIoFile(file)
                    ?.let { vf -> com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text }
            } ?: file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull() ?: return err("Could not read $relative.")

        val analysis = ComposeSourceAnalyzer.analyze(source)
        if (!analysis.isCompose) {
            return json {
                addProperty("file", relative)
                addProperty("isCompose", false)
                addProperty("note", "No @Composable functions in this file.")
            }
        }
        return json {
            addProperty("file", relative)
            addProperty("isCompose", true)
            add("composables", JsonArray().apply {
                analysis.composables.forEach { c ->
                    add(JsonObject().apply {
                        addProperty("name", c.name)
                        addProperty("line", c.line)
                        addProperty("hasPreview", c.hasPreview)
                        addProperty("screenLevel", c.isScreenLevel)
                    })
                }
            })
            add("previews", JsonArray().apply {
                analysis.previews.forEach { p ->
                    add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("line", p.line)
                        addProperty("coversDarkMode", p.coversDarkMode)
                        addProperty("coversFontScale", p.coversFontScale)
                        addProperty("coversDeviceSize", p.coversDeviceSize)
                    })
                }
            })
            add("findings", JsonArray().apply {
                analysis.findings.forEach { f ->
                    add(JsonObject().apply {
                        addProperty("kind", f.kind.label)
                        addProperty("message", f.message)
                        addProperty("line", f.line)
                        f.symbol?.let { addProperty("symbol", it) }
                    })
                }
            })
            addProperty(
                "limits",
                "Findings are only what the source text establishes beyond doubt. Recomposition counts, " +
                    "parameter stability and work done during composition are NOT analysed — they are " +
                    "not decidable from text. Read the file for those rather than assuming this covered them.",
            )
        }
    }

    private fun enabled(): Boolean = ClaudeSettings.getInstance().state.androidFeatures

    private fun err(message: String) = json { addProperty("error", message) }

    private fun tool(name: String, desc: String, props: JsonObject): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", desc)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", props)
        })
    }

    private inline fun json(build: JsonObject.() -> Unit): String = JsonObject().apply(build).toString()

    private fun JsonObject.str(key: String): String? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }.getOrNull()

    /** Exposed so the panel can build the same capture the consent card will show. */
    fun screenshotAction(serial: String) = DeviceActions.screenshot(serial)

    /** Exposed for the panel's screen strip. Off-EDT. */
    fun currentScreen(): ScreenState? {
        val env = AndroidEnvironment.getInstance(project)
        val device = AndroidContextResolver.getInstance(project).resolve().device
            ?.takeIf { it.state.isUsable } ?: return null
        val out = env.adb("-s", device.serial, "shell", "dumpsys", "activity", "activities", timeoutMs = 6000)
            ?.takeIf { it.ok }?.stdout ?: return null
        return DumpsysParser.parseActivities(out).takeUnless { it.isEmpty }
    }
}
