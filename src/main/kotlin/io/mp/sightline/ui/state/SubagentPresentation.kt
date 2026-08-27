package io.mp.sightline.ui.state

import com.google.gson.JsonObject

/**
 * What a **subagent**'s forwarded output contributes to the `Task` card that owns it.
 *
 * With `--forward-subagent-text`, a subagent's own assistant and user messages arrive on the same
 * stream as the main agent's, distinguished only by a top-level `parent_tool_use_id` (verified against
 * CLI 2.1.235 — docs/PROTOCOL.md §6). Rendered as ordinary transcript blocks they would interleave
 * with the main agent's reply and read as one confused voice, so they are folded into the card for the
 * `Task` that spawned them instead.
 *
 * Three things are deliberately dropped, because a subagent produces far more output than its caller:
 *
 * - **Thinking.** The panel shows the main agent's reasoning in a collapsible block; nesting a
 *   subagent's inside a tool card would bury the one thing the card is for — what it did and what it
 *   concluded.
 * - **Tool results.** The activity line already names the tool and its target. A subagent's `Grep`
 *   result can be thousands of lines, and a card is not a place to put them.
 * - **Empty text.** A blank block is not an utterance.
 *
 * Activity lines are capped, and the cap is **stated** rather than silently applied — a card that
 * quietly stops listing steps reads as a subagent that stopped working.
 *
 * Platform-free and unit-tested; the Swing half only applies the result.
 */
object SubagentPresentation {

    /**
     * How many "→ tool target" lines a single Task card lists before it starts counting instead. An
     * Explore agent routinely runs dozens; past a dozen the list stops being scannable, which is the
     * only thing it is good for.
     */
    const val MAX_ACTIVITY = 12

    sealed interface Entry {
        /**
         * One tool the subagent used. [input] is carried through verbatim so the activity map can be
         * fed the same structured event the main agent's tools produce — the map's rule is that a node
         * comes from a tool's own arguments, never from prose about them.
         */
        data class Activity(val tool: String, val summary: String, val input: JsonObject? = null) : Entry

        /** Something the subagent said — normally its conclusion. */
        data class Say(val text: String) : Entry
    }

    /** The `tool_use_id` of the `Task` this event belongs to, or null when it is not a subagent's. */
    fun parentOf(event: JsonObject): String? =
        event.get("parent_tool_use_id")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() }

    /** What this forwarded event contributes, in order. Empty is the common and correct answer. */
    fun entries(event: JsonObject): List<Entry> {
        val content = event.get("message")
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        val out = ArrayList<Entry>(2)
        for (el in content) {
            if (!el.isJsonObject) continue
            val b = el.asJsonObject
            when (b.string("type")) {
                "tool_use" -> {
                    val name = b.string("name") ?: continue
                    val input = b.get("input")?.takeIf { it.isJsonObject }?.asJsonObject
                    out.add(Entry.Activity(name, summarize(name, input), input))
                }
                "text" -> b.string("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(Entry.Say(it)) }
                // "thinking" and "tool_result" fall through by design — see the class doc.
            }
        }
        return out
    }

    /** Said once when the activity cap is reached, so a truncated list never looks like a finished one. */
    fun overflowNote(hidden: Int): String? = when {
        hidden <= 0 -> null
        hidden == 1 -> "…and 1 more step"
        else -> "…and $hidden more steps"
    }

    /**
     * A one-line target for a tool call: the thing a reader scanning the list actually wants. An
     * unrecognised tool gets an empty summary rather than a guessed one — the tool's *name* is already
     * on the line, and inventing a target from an unknown schema is how a card ends up confidently wrong.
     */
    fun summarize(tool: String, input: JsonObject?): String {
        if (input == null) return ""
        fun s(key: String) = input.string(key)?.trim().orEmpty()
        return when (tool) {
            "Bash" -> oneLine(s("command"))
            "Read", "Edit", "MultiEdit", "Write", "NotebookEdit" -> fileName(s("file_path"))
            "Grep", "Glob" -> oneLine(s("pattern"))
            "WebFetch" -> oneLine(s("url"))
            "WebSearch" -> oneLine(s("query"))
            "Task" -> oneLine(s("description"))
            "Skill" -> oneLine(s("skill"))
            "TodoWrite" -> ""
            else -> ""
        }
    }

    /** The file's own name. A subagent's paths are absolute and identical up to the last segment. */
    private fun fileName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    private fun oneLine(text: String, max: Int = 80): String {
        val flat = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
