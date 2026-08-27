package io.mp.sightline.ui.state

import com.google.gson.JsonParser
import io.mp.sightline.ui.state.SubagentPresentation.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event fixtures here are shaped from a real `--forward-subagent-text` run against CLI 2.1.235
 * (docs/PROTOCOL.md §6), not invented to match the parser.
 */
class SubagentPresentationTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    // ---- ownership ----

    @Test fun recognisesAForwardedEventByItsParent() {
        assertEquals(
            "toolu_013zuPjywu",
            SubagentPresentation.parentOf(obj("""{"type":"assistant","parent_tool_use_id":"toolu_013zuPjywu"}""")),
        )
    }

    @Test fun aMainAgentEventHasNoParent() {
        // The main agent's own messages carry the key as null — treating that as a parent id would
        // divert the entire conversation into a tool card.
        assertNull(SubagentPresentation.parentOf(obj("""{"type":"assistant","parent_tool_use_id":null}""")))
        assertNull(SubagentPresentation.parentOf(obj("""{"type":"assistant"}""")))
        assertNull(SubagentPresentation.parentOf(obj("""{"type":"assistant","parent_tool_use_id":""}""")))
    }

    // ---- what is kept ----

    @Test fun keepsToolCallsWithTheirTarget() {
        val entries = SubagentPresentation.entries(
            obj(
                """{"type":"assistant","parent_tool_use_id":"toolu_1","message":{"role":"assistant","content":[
                   {"type":"tool_use","id":"toolu_01RD","name":"Bash","input":{"command":"grep -r \"Jump to latest\" src/"}}]}}""",
            ),
        )
        assertEquals(1, entries.size)
        val a = entries[0] as Entry.Activity
        assertEquals("Bash", a.tool)
        assertEquals("""grep -r "Jump to latest" src/""", a.summary)
        // Carried through verbatim so the activity map is fed the tool's own arguments, not prose.
        assertEquals("""grep -r "Jump to latest" src/""", a.input!!.get("command").asString)
    }

    @Test fun keepsWhatTheSubagentSaid() {
        val entries = SubagentPresentation.entries(
            obj(
                """{"type":"assistant","parent_tool_use_id":"toolu_1","message":{"role":"assistant","content":[
                   {"type":"text","text":"The string appears in ClaudePanel.kt.\n"}]}}""",
            ),
        )
        assertEquals(listOf(Entry.Say("The string appears in ClaudePanel.kt.")), entries)
    }

    @Test fun preservesOrderWithinOneMessage() {
        val entries = SubagentPresentation.entries(
            obj(
                """{"type":"assistant","parent_tool_use_id":"t","message":{"role":"assistant","content":[
                   {"type":"text","text":"Looking now."},
                   {"type":"tool_use","name":"Glob","input":{"pattern":"**/*.kt"}}]}}""",
            ),
        )
        assertTrue(entries[0] is Entry.Say)
        assertTrue(entries[1] is Entry.Activity)
    }

    // ---- what is dropped, and why ----

    @Test fun dropsThinking() {
        // A subagent's reasoning nested in a tool card buries what the card exists to show.
        val entries = SubagentPresentation.entries(
            obj(
                """{"type":"assistant","parent_tool_use_id":"t","message":{"role":"assistant","content":[
                   {"type":"thinking","thinking":"The user wants me to find where the string appears..."}]}}""",
            ),
        )
        assertEquals(emptyList<Entry>(), entries)
    }

    @Test fun dropsToolResults() {
        // A subagent's Grep result is thousands of lines; the activity line above it already said what ran.
        val entries = SubagentPresentation.entries(
            obj(
                """{"type":"user","parent_tool_use_id":"t","message":{"role":"user","content":[
                   {"tool_use_id":"toolu_01RD","type":"tool_result","content":"AGENTS.md:| ClaudePanel.kt | ..."}]}}""",
            ),
        )
        assertEquals(emptyList<Entry>(), entries)
    }

    @Test fun dropsBlankText() {
        val entries = SubagentPresentation.entries(
            obj("""{"type":"assistant","parent_tool_use_id":"t","message":{"role":"assistant","content":[{"type":"text","text":"   \n"}]}}"""),
        )
        assertEquals(emptyList<Entry>(), entries)
    }

    @Test fun survivesShapesItDoesNotRecognise() {
        assertEquals(emptyList<Entry>(), SubagentPresentation.entries(obj("""{"type":"assistant"}""")))
        assertEquals(emptyList<Entry>(), SubagentPresentation.entries(obj("""{"type":"assistant","message":{"content":"a string"}}""")))
        assertEquals(emptyList<Entry>(), SubagentPresentation.entries(obj("""{"type":"assistant","message":{"content":[1,2]}}""")))
        assertEquals(emptyList<Entry>(), SubagentPresentation.entries(obj("""{"type":"assistant","message":{"content":[{"type":"tool_use"}]}}""")))
    }

    // ---- summaries ----

    @Test fun namesTheFileNotThePath() {
        // Every path a subagent reports is absolute and identical up to the last segment.
        assertEquals(
            "ClaudePanel.kt",
            SubagentPresentation.summarize("Read", obj("""{"file_path":"/Users/x/proj/src/main/ui/ClaudePanel.kt"}""")),
        )
    }

    @Test fun flattensAndTrimsALongCommand() {
        val long = "a".repeat(200)
        val out = SubagentPresentation.summarize("Bash", obj("""{"command":"$long"}"""))
        assertEquals(80, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun takesTheFirstRealLineOfAMultiLineCommand() {
        assertEquals("./gradlew test", SubagentPresentation.summarize("Bash", obj("""{"command":"\n./gradlew test\ngit status"}""")))
    }

    @Test fun refusesToGuessAtAnUnknownToolsArguments() {
        // The tool's name is already on the line; inventing a target from a schema we don't know is how
        // a card ends up confidently wrong.
        assertEquals("", SubagentPresentation.summarize("mcp__something__do_thing", obj("""{"target":"prod","force":true}""")))
        assertEquals("", SubagentPresentation.summarize("Bash", null))
    }

    // ---- the cap is stated, never silent ----

    @Test fun theOverflowNoteCountsWhatIsHidden() {
        assertNull(SubagentPresentation.overflowNote(0))
        assertNull(SubagentPresentation.overflowNote(-1))
        assertEquals("…and 1 more step", SubagentPresentation.overflowNote(1))
        assertEquals("…and 7 more steps", SubagentPresentation.overflowNote(7))
    }
}
