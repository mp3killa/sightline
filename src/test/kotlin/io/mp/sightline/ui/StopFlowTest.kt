package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.sightline.ui.state.StopPolicy

/**
 * **Stop**, driven through the production event path on a real [ClaudePanel].
 *
 * [StopPolicyTest] pins the decision; this pins the *wiring* — that a `Bash` seen on the wire really
 * does reach the in-flight set, that a `Task` does too, and that a tool result really does clear it.
 * Those three are what decide whether the notice tells a developer the truth about the Gradle build
 * they just tried to stop, and none of them is visible to a unit test of the policy alone.
 *
 * With no CLI process behind the panel the interrupt cannot be written, so every press here falls
 * back to FORCE. That is deliberate and is itself worth pinning: a Stop whose polite path cannot be
 * delivered must still stop, not silently do nothing.
 *
 * BasePlatformTestCase runs on the EDT, so the Swing tree is exercised on the correct thread.
 */
class StopFlowTest : BasePlatformTestCase() {

    private fun panel(): ClaudePanel = ClaudePanel(project, testRootDisposable)

    /** Puts the panel in the running state the way the CLI does. */
    private fun ClaudePanel.startTurn() =
        renderProtocolLineForPreview("""{"type":"system","subtype":"init","session_id":"s1","model":"claude-sonnet-5"}""")

    private fun ClaudePanel.toolUse(id: String, name: String, input: String) =
        renderProtocolLineForPreview(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}""",
        )

    private fun ClaudePanel.toolResult(id: String) =
        renderProtocolLineForPreview(
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"done"}]}}""",
        )

    fun `test a running Bash makes Stop report that the command keeps going`() {
        val p = panel()
        p.startTurn()
        p.toolUse("toolu_1", "Bash", """{"command":"./gradlew assembleDebug"}""")

        val (action, commandInFlight) = p.stopForTest()
        assertTrue("a command was in flight and Stop did not notice", commandInFlight)
        // No process behind the panel, so the polite path cannot be written and Stop still stops.
        assertEquals(StopPolicy.StopAction.FORCE, action)

        val notice = StopPolicy.notice(action, commandInFlight)!!
        assertTrue(notice, notice.contains("keep running"))
    }

    fun `test a finished Bash is no longer claimed to be running`() {
        val p = panel()
        p.startTurn()
        p.toolUse("toolu_1", "Bash", """{"command":"git status"}""")
        p.toolResult("toolu_1")

        val (_, commandInFlight) = p.stopForTest()
        // Saying "a command keeps running" about a command that finished sends the reader hunting for
        // a process that is not there.
        assertFalse("a completed command was still counted as in flight", commandInFlight)
    }

    fun `test a live Task counts because its subagent may be running a command`() {
        // A subagent's own Bash calls carry the subagent's tool ids, which are deliberately not
        // correlated across the boundary — so the Task itself has to stand in for them.
        val p = panel()
        p.startTurn()
        p.toolUse("toolu_9", "Task", """{"description":"Run the build","subagent_type":"general-purpose"}""")

        val (_, commandInFlight) = p.stopForTest()
        assertTrue("a live Task was not treated as possibly-still-running", commandInFlight)
    }

    fun `test a read leaves nothing behind`() {
        val p = panel()
        p.startTurn()
        p.toolUse("toolu_2", "Read", """{"file_path":"/tmp/a.kt"}""")

        val (_, commandInFlight) = p.stopForTest()
        // If every tool counted, the caveat would appear on every stop and stop meaning anything.
        assertFalse(commandInFlight)
    }

    fun `test Stop does nothing when nothing is running`() {
        val p = panel()
        val (action, _) = p.stopForTest()
        assertEquals(StopPolicy.StopAction.NONE, action)
        assertNull(StopPolicy.notice(action, false))
    }

    fun `test the in-flight set does not survive the end of a turn`() {
        val p = panel()
        p.startTurn()
        p.toolUse("toolu_1", "Bash", """{"command":"sleep 40"}""")
        // A turn that ends without the tool ever reporting — an interrupted turn does exactly this.
        p.renderProtocolLineForPreview("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        p.startTurn()

        val (_, commandInFlight) = p.stopForTest()
        // Left uncleared, every later Stop in the session would carry the caveat whether or not it
        // applied — the set is per-turn, not per-session.
        assertFalse("the in-flight set leaked across a turn boundary", commandInFlight)
    }
}
