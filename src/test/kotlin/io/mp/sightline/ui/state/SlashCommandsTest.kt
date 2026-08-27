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
}
