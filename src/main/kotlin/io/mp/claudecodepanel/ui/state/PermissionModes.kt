package io.mp.claudecodepanel.ui.state

/**
 * The permission modes offered by the composer chip, with concise display names (the long
 * explanation lives in the chip's popup). Platform-free/testable; the CLI values are unchanged.
 */
object PermissionModes {

    data class Info(
        val value: String,
        val shortName: String,
        val description: String,
        val dangerous: Boolean,
    )

    val all: List<Info> = listOf(
        Info("default", "Ask", "Claude asks for approval before each tool", false),
        Info("acceptEdits", "Auto-edit", "Claude applies edits automatically; asks for commands", false),
        Info("plan", "Plan", "Claude explores and presents a plan before editing", false),
        Info("auto", "Auto", "Claude approves safe actions and pauses for risky ones (needs Sonnet/Opus)", false),
        Info("bypassPermissions", "Unrestricted", "Claude never asks before running commands — use with care", true),
    )

    val default: Info = all.first { it.value == "auto" }

    fun byValue(value: String?): Info = all.firstOrNull { it.value == value } ?: default
}
