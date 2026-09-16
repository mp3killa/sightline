package io.mp.sightline.ui.state

import com.google.gson.JsonObject

/**
 * The CLI's own commands — built-ins, project commands, plugin skills — offered in the panel.
 *
 * Sending `/context` on the stream-json stdin **executes the local command**: verified against CLI
 * 2.1.235, which answered with a `<synthetic>` assistant message and `num_turns: 0`, i.e. no API call
 * at all (docs/PROTOCOL.md §6). So a panel that never surfaces them is hiding a whole half of the tool
 * from its user, several parts of which — `/context`, `/cost`, `/compact` — are *free*.
 *
 * The catalogue is **only ever what the CLI reports**. Two sources carry it, in order of preference:
 *
 *  1. the `initialize` control_response, which carries `name`, `description` and `argumentHint`;
 *  2. `system/init`'s `slash_commands`, which is names only.
 *
 * Nothing is inferred and nothing is hardcoded — the same reasoning as [ModelCatalog]: the set depends
 * on this user's project, plugins and skills, so a built-in list would be wrong for everyone.
 *
 * Platform-free and unit-tested; the Swing half only renders the result.
 */
object SlashCommands {

    /**
     * One offerable command. [argumentHint] is the CLI's own, so an empty one genuinely means "takes
     * no arguments" rather than "we didn't look".
     */
    data class Command(val name: String, val description: String = "", val argumentHint: String = "") {
        /** True when the CLI says this command expects something after it. */
        val takesArguments: Boolean get() = argumentHint.isNotBlank()
    }

    /**
     * Commands about the **terminal UI**, which a Swing panel has no way to honour and no business
     * offering. Kept deliberately short and explicit rather than clever: an unknown future command is
     * *offered*, and the worst that does is nothing, whereas a rule that guessed at what to hide would
     * quietly suppress the next genuinely useful one.
     */
    private val TERMINAL_ONLY = setOf(
        "vim", "terminal-setup", "statusline", "keybindings", "exit", "quit", "ide", "fullscreen",
    )

    /**
     * Commands the **panel already does itself**, where offering the CLI's version would give the user
     * two controls that behave differently — the panel's New button forgets the session id, `/clear`
     * does not.
     */
    private val PANEL_OWNS = setOf("clear", "resume", "model")

    /** Parses the `initialize` control_response's `commands` array. Unknown shapes yield nothing. */
    fun fromInitializeReply(response: JsonObject?): List<Command> {
        val arr = response?.get("commands")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val name = o.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Command(name, o.string("description")?.trim().orEmpty(), o.string("argumentHint")?.trim().orEmpty())
        }
    }

    /** Parses `system/init`'s `slash_commands` — names only, and the weaker of the two sources. */
    fun fromInitEvent(event: JsonObject?): List<Command> {
        val arr = event?.get("slash_commands")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            el.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Command(it) }
        }
    }

    /**
     * Merges the two sources: the richer entry wins per name, and a name only `system/init` knows about
     * is still offered — with no description, which is honest, rather than an invented one.
     */
    fun merge(rich: List<Command>, names: List<Command>): List<Command> {
        val byName = LinkedHashMap<String, Command>()
        for (c in names) byName[c.name] = c
        for (c in rich) byName[c.name] = c
        return byName.values.toList()
    }

    /** What the panel should actually list: offerable, de-duplicated, alphabetical. */
    fun offerable(all: List<Command>): List<Command> =
        all.asSequence()
            .filter { it.name.isNotBlank() }
            .filterNot { it.name in TERMINAL_ONLY }
            .filterNot { it.name in PANEL_OWNS }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
            .toList()

    /**
     * The text to put in the composer for [command].
     *
     * Picking a command **fills the composer; it does not send.** One that takes arguments is incomplete
     * until the user types them, and a menu where some entries send and some don't is a menu you have to
     * read twice — so both behave the same, and the trailing space is the only difference. Enter is one
     * keystroke; a command fired before its arguments were typed costs a turn.
     */
    fun insertion(command: Command): String = if (command.takesArguments) "/${command.name} " else "/${command.name}"

    /**
     * Whether [text] is a slash command rather than a prompt that happens to start with a slash.
     *
     * This decides whether the composer may prepend anything to it, and getting it wrong is expensive
     * in both directions. **A slash command only executes when it is the first thing in the message** —
     * verified against 2.1.235: sent alone, `/context` came back as a local command with `num_turns: 0`
     * and zero cost; sent after an Android context block it became an ordinary prompt with
     * `num_turns: 1` and a real bill, and after an `@mention` it cost three turns of the model guessing
     * what was wanted. So anything prepended to a command silently converts it into a paid conversation
     * that does not do what was asked.
     *
     * The rule is narrow on purpose: one leading `/`, then a command-shaped name, then whitespace or
     * nothing. A POSIX path (`/Users/me/x`) has a `/` where the whitespace should be and is therefore
     * still an ordinary prompt — which is right, because it is one.
     */
    fun isCommand(text: String): Boolean = COMMAND.matches(text.trim().lineSequence().firstOrNull() ?: "")

    private val COMMAND = Regex("^/[A-Za-z][A-Za-z0-9_:-]*(\\s.*)?$")

    /** Menu label: the name, with the CLI's own argument hint when it gave one. */
    fun label(command: Command): String =
        if (command.takesArguments) "/${command.name} ${command.argumentHint}" else "/${command.name}"

    /**
     * A one-line description for the menu. Truncated because a skill's description can be a paragraph —
     * `deep-research`'s is 180 characters — and a popup row that wraps to five lines is unreadable.
     */
    fun shortDescription(command: Command, max: Int = 80): String {
        val first = command.description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.length <= max) first else first.take(max - 1).trimEnd() + "…"
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
