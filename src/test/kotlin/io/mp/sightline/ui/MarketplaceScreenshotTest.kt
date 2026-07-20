package io.mp.sightline.ui

import com.google.gson.JsonPrimitive
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import io.mp.sightline.android.AndroidContext
import io.mp.sightline.android.DeviceContext
import io.mp.sightline.android.DeviceState
import io.mp.sightline.android.EditorContext
import io.mp.sightline.android.Fact
import io.mp.sightline.android.FactTier
import io.mp.sightline.android.ModuleContext
import io.mp.sightline.settings.ClaudeSettings
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Renders the **Marketplace listing screenshots** to `build/marketplace/`, at the 1280×800 JetBrains
 * asks for (their floor is 1200×760).
 *
 * Separate from `ChatGalleryPreviewTest` because the two have opposite goals. The gallery is a
 * regression harness: it crams every block type into one image so a rendering defect is visible. These
 * are a *pitch* — one idea per image, realistic density, nothing crowded.
 *
 * ### The content rule
 *
 * Everything here is a **fictional delivery-routing app** (`com.example.routes`). No real project,
 * client, repository, path or address appears in any of it. That is not fussiness: a listing screenshot
 * is public permanently and is the one asset where a leaked client name cannot be quietly corrected
 * later. Anything recognisable must stay out at the source, not be cropped out afterwards.
 *
 * Everything is driven through the **production event path**, so these show what the plugin actually
 * renders rather than a mock-up of it.
 */
class MarketplaceScreenshotTest : BasePlatformTestCase() {

    private val width = 1280
    private val height = 800

    private fun outDir(): File = File("build/marketplace").apply { mkdirs() }

    // ---------- 1. chat, Markdown and tool cards ----------

    fun testScreenshot1Conversation() {
        if (!applyTheme(dark = true)) return
        val p = panel(showMap = false)

        user(p, "The route list is showing stale data after a refresh. Any idea why?")
        assistant(
            p,
            """
            Found it. `RouteRepository.refresh()` writes to the cache but never invalidates the
            in-memory `StateFlow`, so collectors keep the old value.

            ## What's happening

            1. `refresh()` fetches and writes to Room
            2. The `StateFlow` was built with `stateIn(started = Eagerly)` over a **one-shot** query
            3. Nothing re-emits, so the UI keeps the first result

            ```kotlin
            // before — one-shot, never re-emits
            val routes: StateFlow<List<Route>> =
                flowOf(dao.getRoutes()).stateIn(scope, Eagerly, emptyList())

            // after — observes the table
            val routes: StateFlow<List<Route>> =
                dao.observeRoutes().stateIn(scope, WhileSubscribed(5_000), emptyList())
            ```

            | Approach | Re-emits on write | Survives config change |
            |---|:-:|:-:|
            | `flowOf(dao.getRoutes())` | no | yes |
            | `dao.observeRoutes()` | **yes** | yes |
            """.trimIndent(),
        )
        toolUse(p, "t1", "Read", """{"file_path":"data/src/main/java/com/example/routes/RouteRepository.kt"}""")
        toolResult(p, "t1", "184 lines read")
        toolUse(p, "t2", "Bash", """{"command":"./gradlew :data:testDebugUnitTest","description":"Run the data-layer tests"}""")
        toolResult(p, "t2", "BUILD SUCCESSFUL in 6s\n24 tests, 0 failures")
        assistant(p, "The repository tests still pass with the observable query in place.")
        result(p)

        write(p, "01-conversation.png")
    }

    // ---------- 2. reviewing a change before it applies ----------

    fun testScreenshot2ReviewADiff() {
        if (!applyTheme(dark = true)) return
        val p = panel(showMap = false)

        user(p, "Fix it — use the observable query.")
        assistant(
            p,
            """
            Switching the flow to observe the table so a write re-emits. One line in the repository,
            and the `WhileSubscribed` window means it stops collecting when nothing is listening.
            """.trimIndent(),
        )
        toolUse(p, "t0", "Read", """{"file_path":"data/src/main/java/com/example/routes/RouteDao.kt"}""")
        toolResult(p, "t0", "48 lines read")
        assistant(p, "`observeRoutes()` already exists on the DAO, so this is a one-line change.")
        toolUse(
            p, "t1", "Edit",
            """{"file_path":"data/src/main/java/com/example/routes/RouteRepository.kt","old_string":"val routes: StateFlow<List<Route>> =\n        flowOf(dao.getRoutes()).stateIn(scope, Eagerly, emptyList())","new_string":"val routes: StateFlow<List<Route>> =\n        dao.observeRoutes().stateIn(scope, WhileSubscribed(5_000), emptyList())"}""",
        )
        toolResult(p, "t1", "Applied")
        // A real approval card, built by the production control-request path.
        approval(
            p, "req-1", "Bash",
            """{"command":"./gradlew :app:assembleDebug","description":"Rebuild the app"}""",
        )

        write(p, "02-review-a-change.png")
    }

    // ---------- 3. the Activity Map ----------

    fun testScreenshot3ActivityMap() {
        if (!applyTheme(dark = true)) return
        val p = panel(showMap = true, viewMode = "split")

        user(p, "Why is the delivery screen crashing on open?")
        assistant(p, "Reading the screen and its view model, then reproducing on the emulator.")
        toolUse(p, "t1", "Read", """{"file_path":"app/src/main/java/com/example/routes/ui/DeliveryScreen.kt"}""")
        toolResult(p, "t1", "212 lines read")
        toolUse(p, "t2", "Read", """{"file_path":"app/src/main/java/com/example/routes/ui/DeliveryViewModel.kt"}""")
        toolResult(p, "t2", "96 lines read")
        toolUse(p, "t3", "Read", """{"file_path":"data/src/main/java/com/example/routes/RouteRepository.kt"}""")
        toolResult(p, "t3", "184 lines read")
        toolUse(p, "t4", "Bash", """{"command":"./gradlew :app:assembleDebug","description":"Build the app"}""")
        toolResult(p, "t4", "BUILD SUCCESSFUL in 21s")
        toolUse(p, "t5", "Bash", """{"command":"adb logcat -d","description":"Read the crash"}""")
        toolResult(
            p, "t5",
            "E AndroidRuntime: FATAL EXCEPTION: main\n" +
                "E AndroidRuntime: Process: com.example.routes, PID: 8123\n" +
                "E AndroidRuntime: java.lang.IllegalStateException: Route was not loaded\n" +
                "E AndroidRuntime: \tat com.example.routes.ui.DeliveryViewModel.load(DeliveryViewModel.kt:42)\n",
            isError = true,
        )
        toolUse(p, "t6", "Bash", """{"command":"./gradlew :app:testDebugUnitTest","description":"Run the tests"}""")
        toolResult(p, "t6", "BUILD SUCCESSFUL in 9s\n41 tests, 0 failures")
        assistant(p, "The view model reads `route` before the load completes — that's the crash.")
        result(p)

        // Lay out first so the canvas has real dimensions, then settle the force simulation and frame
        // it. Without this the render catches frame one, with every node stacked near the origin —
        // which reads as a layout defect rather than an unsettled simulation.
        p.component.preferredSize = Dimension(width, height)
        layoutTree(p.component, width, height)
        p.settleActivityMapForPreview()

        write(p, "03-activity-map.png")
    }

    // ---------- 4. Android context + a structured question ----------

    fun testScreenshot4AndroidContext() {
        if (!applyTheme(dark = true)) return
        val p = panel(showMap = false)
        p.setAndroidContextForPreview(androidContext())

        user(p, "Run the tests for what I just changed.")
        assistant(p, "Checking what changed since your last commit.")
        toolUse(p, "t1", "Bash", """{"command":"git diff --name-only HEAD","description":"List changed files"}""")
        toolResult(
            p, "t1",
            "app/src/main/java/com/example/routes/ui/DeliveryViewModel.kt\n" +
                "data/src/main/java/com/example/routes/RouteRepository.kt",
        )
        assistant(
            p,
            "Two modules were touched, and the instrumented test needs the emulator that's already " +
                "running. Which would you like?",
        )
        question(p, "req-2")

        write(p, "04-android-context.png")
    }

    /**
     * A representative Android context. **Mixed provenance on purpose**: the variant comes from a build
     * output, so it renders `(last build)` — that qualifier is the differentiating idea, and a
     * screenshot showing only clean IDE-sourced facts would hide it.
     */
    private fun androidContext() = AndroidContext(
        modules = listOf(
            ModuleContext(
                name = "app",
                gradlePath = ":app",
                variant = Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT),
                flavors = Fact.known(listOf("demo", "staging"), FactTier.BUILD_OUTPUT),
                buildType = Fact.known("debug", FactTier.BUILD_OUTPUT),
                applicationId = Fact.known("com.example.routes.staging", FactTier.BUILD_OUTPUT),
                namespace = Fact.known("com.example.routes", FactTier.STATIC_PARSE),
                minSdk = Fact.known(24, FactTier.STATIC_PARSE),
                targetSdk = Fact.known(36, FactTier.STATIC_PARSE),
                compileSdk = Fact.known("36", FactTier.STATIC_PARSE),
                usesCompose = Fact.known(true, FactTier.STATIC_PARSE),
            ),
            ModuleContext(name = "data", gradlePath = ":data"),
        ),
        activeModuleName = "app",
        device = DeviceContext(
            serial = "emulator-5554",
            name = "Pixel 8",
            state = DeviceState.ONLINE,
            apiLevel = Fact.known(35, FactTier.DEVICE),
            androidRelease = Fact.known("15", FactTier.DEVICE),
            appRunning = Fact.known(true, FactTier.DEVICE),
        ),
        editor = EditorContext("app/src/main/java/com/example/routes/ui/DeliveryViewModel.kt"),
    )

    // ---------- driving the production event path ----------

    private fun feed(p: ClaudePanel, line: String) = p.renderProtocolLineForPreview(line)

    private fun user(p: ClaudePanel, text: String) = p.addUserMessageForPreview(text)

    private fun assistant(p: ClaudePanel, text: String) = feed(
        p, """{"type":"assistant","message":{"content":[{"type":"text","text":${quote(text)}}]}}""",
    )

    private fun toolUse(p: ClaudePanel, id: String, name: String, input: String) = feed(
        p, """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}""",
    )

    private fun toolResult(p: ClaudePanel, id: String, content: String, isError: Boolean = false) = feed(
        p, """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":${quote(content)},"is_error":$isError}]}}""",
    )

    /** A real approval card, via the control-request channel the CLI actually uses. */
    private fun approval(p: ClaudePanel, requestId: String, tool: String, input: String) = feed(
        p,
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"can_use_tool","tool_name":"$tool","input":$input}}""",
    )

    /** A real AskUserQuestion card, same channel. */
    private fun question(p: ClaudePanel, requestId: String) = feed(
        p,
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Which tests should I run?","header":"Tests","multiSelect":false,"options":[{"label":"Unit tests only","description":"Fast — :app and :data JVM tests, about 15 seconds."},{"label":"Unit + instrumented","description":"Also runs the Compose UI test on the connected Pixel 8. Slower, but covers the screen you changed."},{"label":"Everything","description":"Full check across both modules, including lint."}]}]}}}""",
    )

    private fun result(p: ClaudePanel) = feed(
        p,
        """{"type":"result","result":"done","duration_ms":34200,"num_turns":9,"total_cost_usd":0.212,"is_error":false}""",
    )

    private fun quote(s: String): String = JsonPrimitive(s).toString()

    // ---------- rendering ----------

    private fun panel(showMap: Boolean, viewMode: String = "chat"): ClaudePanel {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true          // tool cards visible — they are a selling point
        settings.showActivityMap = showMap
        settings.activityViewMode = viewMode
        settings.permissionMode = "auto"
        return ClaudePanel(project, testRootDisposable)
    }

    private fun write(p: ClaudePanel, name: String) {
        p.component.preferredSize = Dimension(width, height)
        layoutTree(p.component, width, height)
        val out = outDir().resolve(name)
        render(p.component, width, height, out)
        println("[marketplace] wrote ${out.absolutePath} (${width}x$height)")
        assertTrue("screenshot not written: $name", out.length() > 10_000)
    }

    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) {
            if (x is Container) { x.doLayout(); x.components.forEach { walk(it) } }
        }
        fun invalidateAll(x: Component) {
            x.invalidate()
            if (x is Container) x.components.forEach { invalidateAll(it) }
        }
        c.setSize(w, h); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        c.setSize(w, h); invalidateAll(c); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        invalidateAll(c); walk(c)
    }

    private fun render(c: JComponent, w: Int, h: Int, out: File) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = c.background ?: java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            c.printAll(g)
        } finally { g.dispose() }
        ImageIO.write(img, "png", out)
    }

    /** See `ChatGalleryPreviewTest.applyTheme` — both the JBColor flag and the editor scheme matter. */
    private fun applyTheme(dark: Boolean): Boolean {
        val mgr = EditorColorsManager.getInstance()
        val wanted = if (dark) {
            mgr.allSchemes.firstOrNull { it.name.contains("Darcula", true) || it.name.contains("Dark", true) }
        } else {
            mgr.allSchemes.firstOrNull {
                !it.name.contains("Darcula", true) && !it.name.contains("Dark", true) &&
                    !it.name.contains("High contrast", true)
            }
        } ?: return false
        mgr.setGlobalScheme(wanted)
        JBColor.setDark(dark)
        val bg = io.mp.sightline.theme.ClaudeUiTokens.surface()
        val luminance = (bg.red * 0.299 + bg.green * 0.587 + bg.blue * 0.114) / 255.0
        return if (dark) luminance < 0.5 else luminance > 0.5
    }

    /**
     * The rule that protects the one asset that cannot be corrected after publication: no real project,
     * client, path or address may appear. Asserted rather than trusted to review.
     */
    fun testScreenshotsCarryNoRealProjectNames() {
        val forbidden = listOf("demo", "cxk", "mp3killa", "devuser", "/Users/", "claudecodepanel")
        val source = File("src/test/kotlin/io/mp/sightline/ui/MarketplaceScreenshotTest.kt").readText()

        // Scan the fixture only — everything above this guard. Including the guard would match its own
        // list of terms, which is the classic self-referential false positive: the check fails forever
        // and gets deleted rather than fixed.
        val marker = "fun testScreenshotsCarryNoRealProjectNames"
        val fixture = source.substringBefore(marker)
            .lines()
            .filterNot { it.trimStart().startsWith("package ") || it.trimStart().startsWith("import ") }
            .joinToString("\n")

        for (term in forbidden) {
            assertFalse(
                "screenshot fixture contains '$term' — listing images are public permanently",
                fixture.contains(term, ignoreCase = true),
            )
        }
    }
}
