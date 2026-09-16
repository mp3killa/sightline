package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.sightline.ui.state.SessionFailure
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.SwingUtilities

/**
 * The **failure card**, wired up on a real [ClaudePanel].
 *
 * [io.mp.sightline.ui.state.SessionFailureTest] pins what the advice says; this pins the two things
 * only the panel knows — that a failed turn really produces a card with buttons rather than a red
 * line, and that **Retry is withheld once the turn has run tools**. The second is the one with teeth:
 * re-sending a message whose turn already edited files or ran commands replays them.
 */
class FailureCardTest : BasePlatformTestCase() {

    private val APPROVAL_LABELS = setOf("Allow", "Allow always", "Deny", "Deny with reason…")

    private fun panel(): ClaudePanel = ClaudePanel(project, testRootDisposable)

    private fun ClaudePanel.fail(text: String) = renderProtocolLineForPreview(
        """{"type":"result","is_error":true,"result":"$text","num_turns":1}""",
    )

    private fun ClaudePanel.toolUse(id: String, name: String, input: String) = renderProtocolLineForPreview(
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}""",
    )

    /** Lays a real hierarchy out the way the preview harnesses do — invalidate, walk, repeat. */
    private fun layoutTree(c: Component, w: Int, h: Int) {
        fun walk(x: Component) { if (x is Container) { x.doLayout(); x.components.forEach { walk(it) } } }
        fun invalidateAll(x: Component) { x.invalidate(); if (x is Container) x.components.forEach { invalidateAll(it) } }
        c.preferredSize = java.awt.Dimension(w, h)
        repeat(3) { c.setSize(w, h); invalidateAll(c); walk(c) }
    }

    private fun buttons(root: Component): List<JButton> {
        val out = ArrayList<JButton>()
        fun walk(c: Component) {
            if (c is JButton) out.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    fun `test an auth failure renders a card with recovery actions, not a bare line`() {
        val p = panel()
        p.addUserMessageForPreview("Check the tests")
        p.fail("Failed to authenticate: OAuth session expired and could not be refreshed")

        val labels = buttons(p.component).map { it.text }
        assertTrue("no sign-in action on an auth failure: $labels", labels.contains(SessionFailure.Action.SIGN_IN.label))
        assertTrue("no way to check the sign-in: $labels", labels.contains(SessionFailure.Action.CHECK_SIGN_IN.label))
        assertTrue("the CLI's own text is not copyable: $labels", labels.contains(SessionFailure.Action.COPY.label))
    }

    fun `test retry is offered for a message that failed before any tool ran`() {
        val p = panel()
        p.addUserMessageForPreview("Check the tests")

        val advice = p.failureAdviceForTest("Failed to authenticate: OAuth session expired")
        assertTrue(advice.actions.contains(SessionFailure.Action.RETRY))
    }

    fun `test retry is withheld once the turn has run a tool`() {
        val p = panel()
        p.addUserMessageForPreview("Check the tests")
        p.toolUse("toolu_1", "Edit", """{"file_path":"build.gradle.kts"}""")

        val advice = p.failureAdviceForTest("Failed to authenticate: OAuth session expired")
        assertFalse(
            "a turn that already edited a file must not offer to replay it",
            advice.actions.contains(SessionFailure.Action.RETRY),
        )
        // The rest of the card is unaffected — the failure is still explained and still actionable.
        assertEquals(SessionFailure.Kind.AUTH, advice.kind)
        assertTrue(advice.actions.contains(SessionFailure.Action.SIGN_IN))
    }

    /**
     * The approval card's four buttons must all stay reachable on a narrow docked panel. They live in
     * a WrapLayout for this reason; a FlowLayout reports one row's height however many it lays out, so
     * the last button — Deny, or Deny with reason — would be laid out below the card's own height and
     * clipped away. An approval you cannot deny is the worst possible thing to lose off-screen.
     */
    fun `test every approval button stays inside the card at a narrow width`() {
        val p = panel()
        p.addUserMessageForPreview("Push the branch")
        p.renderProtocolLineForPreview(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Pushing now."}]}}""",
        )
        p.renderProtocolLineForPreview(
            """{"type":"control_request","request_id":"req-n","request":{"subtype":"can_use_tool",
            "tool_name":"Bash","tool_use_id":"t1","title":"Allow Bash?",
            "input":{"command":"git push origin main"},
            "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"git push *"}],"behavior":"allow","destination":"localSettings"}]}}""",
        )
        layoutTree(p.component, 400, 900)

        val all = buttons(p.component).filter { it.text in APPROVAL_LABELS }
        assertEquals("all four approval buttons should exist", 4, all.size)
        // The card is what clips: its button row is laid out at its own preferred width inside a
        // BorderLayout, so a row too wide for a narrow panel simply runs off the card's right edge and
        // the last button — Deny, or Deny with reason — becomes unclickable. Measured in the card's
        // own coordinate space, which is where that actually shows up.
        val card = generateSequence(all.first().parent as Component) { it.parent }
            .first { it.javaClass.simpleName.contains("ApprovalBlock") }
        for (b in all) {
            val right = SwingUtilities.convertPoint(b, b.width, 0, card).x
            assertTrue(
                "${b.text} runs past the card's right edge ($right > ${card.width}) — it would be clipped away",
                right <= card.width,
            )
        }
    }

    fun `test a non-zero exit renders the card with the exit code`() {
        val p = panel()
        p.renderProtocolLineForPreview("""{"type":"__panel","subtype":"exited","code":1,"text":"claude: command not found"}""")
        val labels = buttons(p.component).map { it.text }
        assertTrue("a failed exit offered nothing: $labels", labels.contains(SessionFailure.Action.HEALTH.label))
    }
}
