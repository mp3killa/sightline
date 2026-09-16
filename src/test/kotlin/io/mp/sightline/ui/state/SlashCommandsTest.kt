package io.mp.sightline.ui.state

import com.google.gson.JsonParser
import io.mp.sightline.ui.state.SlashCommands.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are captured from CLI 2.1.235's own `initialize` reply and `system/init` (docs/PROTOCOL.md
 * §6), including the 180-character `deep-research` description that motivates the truncation.
 */
class SlashCommandsTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    // ---- parsing ----

    @Test fun readsNameDescriptionAndHintFromTheInitializeReply() {
        val cmds = SlashCommands.fromInitializeReply(
            obj(
                """{"commands":[
                   {"name":"design","description":"Create a design canvas","argumentHint":"[what to design]"},
                   {"name":"context","description":"Show token usage","argumentHint":""}]}""",
            ),
        )
        assertEquals(2, cmds.size)
        assertEquals(Command("design", "Create a design canvas", "[what to design]"), cmds[0])
        assertTrue(cmds[0].takesArguments)
        assertFalse(cmds[1].takesArguments)
    }

    @Test fun readsBareNamesFromSystemInit() {
        val cmds = SlashCommands.fromInitEvent(obj("""{"slash_commands":["deep-research","design","code-review"]}"""))
        assertEquals(listOf("deep-research", "design", "code-review"), cmds.map { it.name })
        // No description invented for a source that carries none.
        assertTrue(cmds.all { it.description.isEmpty() && it.argumentHint.isEmpty() })
    }

    @Test fun survivesShapesItDoesNotRecognise() {
        assertEquals(emptyList<Command>(), SlashCommands.fromInitializeReply(null))
        assertEquals(emptyList<Command>(), SlashCommands.fromInitializeReply(obj("""{}""")))
        assertEquals(emptyList<Command>(), SlashCommands.fromInitializeReply(obj("""{"commands":"nope"}""")))
        assertEquals(emptyList<Command>(), SlashCommands.fromInitializeReply(obj("""{"commands":[{"description":"no name"}]}""")))
        assertEquals(emptyList<Command>(), SlashCommands.fromInitEvent(obj("""{"slash_commands":[1,2]}""")))
    }

    // ---- merging the two sources ----

    @Test fun theRicherSourceWinsPerName() {
        val merged = SlashCommands.merge(
            rich = listOf(Command("design", "Create a design canvas", "[what]")),
            names = listOf(Command("design"), Command("verify")),
        )
        assertEquals("Create a design canvas", merged.first { it.name == "design" }.description)
        // A name only the weaker source knows is still offered — with nothing made up for it.
        assertEquals("", merged.first { it.name == "verify" }.description)
    }

    // ---- what is offered ----

    @Test fun hidesTerminalOnlyCommandsAPanelCannotHonour() {
        val offered = SlashCommands.offerable(
            listOf("vim", "terminal-setup", "statusline", "keybindings", "exit", "quit", "ide", "fullscreen")
                .map { Command(it) },
        )
        assertEquals(emptyList<Command>(), offered)
    }

    @Test fun hidesCommandsThePanelAlreadyOwns() {
        // Two controls that behave differently is worse than one — the panel's New forgets the session
        // id, /clear does not.
        val offered = SlashCommands.offerable(listOf("clear", "resume", "model").map { Command(it) })
        assertEquals(emptyList<Command>(), offered)
    }

    @Test fun offersAnythingItDoesNotRecognise() {
        // The failure mode we want: a new command shows up and does nothing useful, rather than a clever
        // rule quietly suppressing the next genuinely good one.
        val offered = SlashCommands.offerable(listOf(Command("some-future-command")))
        assertEquals(listOf("some-future-command"), offered.map { it.name })
    }

    @Test fun deDuplicatesAndSortsCaseInsensitively() {
        val offered = SlashCommands.offerable(
            listOf(Command("Zebra"), Command("apple"), Command("apple"), Command("Mango")),
        )
        assertEquals(listOf("apple", "Mango", "Zebra"), offered.map { it.name })
    }

    // ---- presentation ----

    @Test fun aCommandWithArgumentsLandsInTheComposerReadyToTypeInto() {
        assertEquals("/design ", SlashCommands.insertion(Command("design", "", "[what to design]")))
        assertEquals("/context", SlashCommands.insertion(Command("context")))
    }

    @Test fun theLabelShowsTheClisOwnArgumentHint() {
        assertEquals("/design [what to design]", SlashCommands.label(Command("design", "", "[what to design]")))
        assertEquals("/context", SlashCommands.label(Command("context")))
    }

    @Test fun aParagraphLongDescriptionIsCutToOneReadableLine() {
        val real = "Deep research harness — fan-out web searches, fetch sources, adversarially verify " +
            "claims, synthesize a cited report. (dynamic workflow)"
        val short = SlashCommands.shortDescription(Command("deep-research", real))
        assertEquals(80, short.length)
        assertTrue(short.endsWith("…"))
    }

    @Test fun aShortDescriptionIsLeftAlone() {
        assertEquals("Show token usage", SlashCommands.shortDescription(Command("context", "Show token usage")))
        assertEquals("", SlashCommands.shortDescription(Command("context")))
    }

    // ---- what makes a slash command a command ----

    /**
     * A command only executes when it is the first thing in the message. Verified against 2.1.235:
     * `/context` sent alone came back as a local command (`num_turns: 0`, zero cost); the same text
     * after an Android context block became an ordinary prompt with a real bill, and after an
     * `@mention` it cost three turns of the model guessing. So this predicate is what stands between a
     * command and a silently-paid conversation that does nothing the user asked for.
     */
    @Test
    fun `a leading slash command is recognised`() {
        assertTrue(SlashCommands.isCommand("/context"))
        assertTrue(SlashCommands.isCommand("  /context  "))
        assertTrue(SlashCommands.isCommand("/model haiku"))
        assertTrue(SlashCommands.isCommand("/mcp"))
        assertTrue(SlashCommands.isCommand("/user:my-command arg"))
        // Only the first line decides; a command with a multi-line argument is still a command.
        assertTrue(SlashCommands.isCommand("/review\nsecond line"))
    }

    @Test
    fun `text that merely starts with a slash is not a command`() {
        assertFalse("a POSIX path is a prompt, not a command", SlashCommands.isCommand("/Users/me/project/Foo.kt"))
        assertFalse(SlashCommands.isCommand("/usr/local/bin/claude is where it lives"))
        assertFalse(SlashCommands.isCommand("/ leading space"))
        assertFalse(SlashCommands.isCommand("//comment"))
        assertFalse(SlashCommands.isCommand("/123"))
    }

    @Test
    fun `a command anywhere but the start is not a command`() {
        assertFalse(SlashCommands.isCommand("please run /context for me"))
        assertFalse(SlashCommands.isCommand(""))
        assertFalse(SlashCommands.isCommand("   "))
    }
}
