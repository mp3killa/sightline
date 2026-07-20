package io.mp.claudecodepanel.ui

import com.google.gson.JsonPrimitive
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.settings.ClaudeSettings
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Headless **component gallery**: renders one long transcript containing every block type the chat can
 * show — rich Markdown, routine/failed tool cards, an edit diff, an approval card and both
 * AskUserQuestion variants — to `build/chat-gallery-{light,dark}.png`.
 *
 * Everything is driven through the **production event path** (`renderProtocolLineForPreview` →
 * `handleEvent`), including `control_request`, so the approval and question blocks are the real ones a
 * live session builds — not hand-constructed lookalikes.
 *
 * This is what retires the "needs eyes" backlog items that are purely about *static rendering*
 * (does a table/callout/fence/approval card look right, in both themes). It does **not** cover
 * anything needing a click, hover, focus traversal, drag or a live CLI session — see BACKLOG.md.
 */
class ChatGalleryPreviewTest : BasePlatformTestCase() {

    private val richMarkdown = """
        I traced the layout problem to a guard that was never wired up.

        ## What changed

        - Wired `ResponsiveLayout.allowSplitDefault()` into the panel
        - Demoted a cramped `SPLIT` to **CHAT**, never to the graph
        - [x] Reading width measured from the chat column
        - [ ] Composer alignment inside SPLIT (Tier 2)

        | Width | Before | After |
        |---|---|:-:|
        | Narrow | Graph only | Conversation |
        | Medium | Split | Conversation |
        | Wide | Split | Split |

        ```kotlin
        fun effectiveMode(preferred: WorkspaceMode, profile: LayoutProfile): WorkspaceMode =
            if (preferred == WorkspaceMode.SPLIT && !allowSplitDefault(profile)) {
                WorkspaceMode.CHAT
            } else {
                preferred
            }
        ```

        > The guard existed, was documented, and was tested — but nothing called it.

        > [!WARNING]
        > A transient narrow layout must never rewrite the persisted preference.

        See `ui/state/WorkspaceModes.kt` for the pure logic.
    """.trimIndent()

    private fun quote(s: String): String = JsonPrimitive(s).toString()

    private fun seed(p: ClaudePanel) {
        fun feed(line: String) = p.renderProtocolLineForPreview(line)

        p.addUserMessageForPreview("Stop the activity map from eating half the chat view.")

        feed("""{"type":"assistant","message":{"content":[{"type":"text","text":${quote(richMarkdown)}}]}}""")

        // A routine success — the case Tier 2 wants demoted to a compact row.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"src/main/kotlin/io/mp/claudecodepanel/ui/ClaudePanel.kt"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"1767 lines","is_error":false}]}}""")

        // A failure — must stay visually louder than the routine rows above.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"./gradlew verifyPlugin","description":"Verify plugin"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"FAILURE: Could not resolve idea:ideaIC:2025.3\nBUILD FAILED in 4s","is_error":true}]}}""")

        // An edit with a diff.
        feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"src/main/kotlin/io/mp/claudecodepanel/ui/state/WorkspaceModes.kt","old_string":"fun toViewMode(mode: WorkspaceMode): String = when (mode) {","new_string":"fun effectiveMode(preferred: WorkspaceMode, profile: LayoutProfile): WorkspaceMode =\n    if (preferred == WorkspaceMode.SPLIT) WorkspaceMode.CHAT else preferred"}}]}}""")
        feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t3","content":"Applied","is_error":false}]}}""")

        // A permission prompt — the real ApprovalBlock, via the control channel.
        feed(
            """{"type":"control_request","request_id":"req-1","request":{"subtype":"can_use_tool",
            "tool_name":"Bash","tool_use_id":"t4","title":"Allow Bash?",
            "input":{"command":"git push origin main"},
            "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"git push *"}],"behavior":"allow","destination":"localSettings"}]}}"""
        )

        // Structured input: single-select with descriptions, then a multi-select.
        feed(
            """{"type":"control_request","request_id":"req-2","request":{"subtype":"can_use_tool",
            "tool_name":"AskUserQuestion","tool_use_id":"t5","input":{"questions":[
              {"question":"Which layout should be the default on a wide panel?","header":"Layout",
               "options":[
                 {"label":"Split","description":"Conversation and activity graph side by side"},
                 {"label":"Chat only","description":"Graph stays available on its own tab"}]}]}}}"""
        )
        feed(
            """{"type":"control_request","request_id":"req-3","request":{"subtype":"can_use_tool",
            "tool_name":"AskUserQuestion","tool_use_id":"t6","input":{"questions":[
              {"question":"Which milestones should ship next?","header":"Milestones","multiSelect":true,
               "options":[
                 {"label":"Compact tool rows","description":"M2"},
                 {"label":"File edit blocks","description":"M3"},
                 {"label":"Hover actions","description":"M4"}]}]}}}"""
        )
    }

    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) {
            if (x is Container) { x.doLayout(); x.components.forEach { walk(it) } }
        }
        c.setSize(w, h); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        c.setSize(w, h); walk(c)
        UIUtil.dispatchAllInvocationEvents()
        walk(c)
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

    /**
     * Switch the platform's colour scheme. Both halves matter: `JBColor.setDark` picks the light/dark
     * side of every `JBColor` pair, while the editor scheme drives `ClaudeUiTokens.surface()` and the
     * code-fence highlighting. Setting only the former leaves a dark surface under light text.
     *
     * Returns false when no matching scheme is registered in the test platform, so the caller can skip
     * rather than silently render the wrong theme and call it verified.
     */
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
        // Guard against "I set the flag but the surface is still the other theme" — the exact trap
        // that made the first light render come out dark.
        val bg = io.mp.claudecodepanel.theme.ClaudeUiTokens.surface()
        val luminance = (bg.red * 0.299 + bg.green * 0.587 + bg.blue * 0.114) / 255.0
        return if (dark) luminance < 0.5 else luminance > 0.5
    }

    private fun renderGallery(name: String) {
        val settings = ClaudeSettings.getInstance().state
        settings.showDetails = true          // tool cards visible — their weight is the point
        settings.showActivityMap = false     // gallery is about the conversation column
        settings.activityViewMode = "chat"

        val p = ClaudePanel(project, testRootDisposable)
        seed(p)
        val w = 900
        val h = 1750
        p.component.preferredSize = Dimension(w, h)
        layoutTree(p.component, w, h)

        val out = File("build").apply { mkdirs() }.resolve("chat-gallery-$name.png")
        render(p.component, w, h, out)
        println("[chat-gallery] wrote ${out.absolutePath}")
        assertTrue("gallery not written for $name", out.length() > 5000)
    }

    fun testRendersTheGalleryInLightTheme() {
        if (!applyTheme(dark = false)) {
            println("[chat-gallery] no light scheme resolved in the test platform — skipping light render")
            return
        }
        try { renderGallery("light") } finally { JBColor.setDark(false) }
    }

    fun testRendersTheGalleryInDarkTheme() {
        if (!applyTheme(dark = true)) {
            println("[chat-gallery] no dark scheme resolved in the test platform — skipping dark render")
            return
        }
        try { renderGallery("dark") } finally { JBColor.setDark(false) }
    }
}
