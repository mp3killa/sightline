package io.mp.sightline.ui.state

/**
 * The one-time notice shown before a user's first session — what Sightline can do on their behalf.
 *
 * Worth its own tested class rather than a string in a Swing file, because the wording *is* the
 * feature. This is the only point at which someone learns that an agent is about to be able to read
 * their files and run commands, and it has to say so plainly enough to be a real disclosure rather
 * than a dialog people click past. It also has to be short enough that they read it.
 *
 * The mode matters to the wording. "Every tool call is approved first" and "nothing is approved" are
 * opposite promises, and showing the same sentence for both would make the disclosure actively
 * misleading for whichever mode it didn't describe.
 */
object FirstRunDisclosure {

    const val TITLE = "Before you start"

    /**
     * Shown once, then never again. Deliberately **not** re-shown on upgrade: a notice people have
     * dismissed and then see again teaches them to dismiss it faster, which costs more than the
     * reminder is worth.
     */
    fun shouldShow(alreadyAcknowledged: Boolean): Boolean = !alreadyAcknowledged

    /** The body, in the order someone needs it: what it can do, what stops it, what leaves. */
    fun body(permissionMode: String): List<String> = listOf(
        "Sightline lets Claude Code inspect files in this project, run development commands, and " +
            "propose or apply changes to your code.",
        modeSentence(permissionMode),
        "What you send — your messages, attached files, and whatever Claude reads while working — is " +
            "processed by the Claude Code CLI under your own Anthropic account. Sightline collects no " +
            "analytics and never sees your credentials.",
    )

    /**
     * What the current mode actually promises. `bypassPermissions` gets a blunt sentence rather than a
     * softened one — if the disclosure is going to be worth anything, it is here.
     */
    fun modeSentence(permissionMode: String): String = when (permissionMode) {
        "default" -> "You will be asked to approve every tool call before it runs."
        "acceptEdits" -> "Commands are approved by you before running. File edits apply without asking " +
            "— review them in the transcript."
        "plan" -> "Claude will propose a plan rather than making changes."
        "bypassPermissions" ->
            "Your current mode is Unrestricted: tools run immediately with no approval. Only use this " +
                "in a project you can afford to lose."
        else -> "Claude decides which tool calls need your approval. You can change this from the mode " +
            "chip beside the send button at any time."
    }

    /** True when the selected mode warrants a warning treatment rather than plain text. */
    fun isDangerousMode(permissionMode: String): Boolean =
        PermissionModes.byValue(permissionMode).dangerous

    /** The reassurance that belongs last: this is reversible and discoverable. */
    const val FOOTER =
        "You can change the permission mode at any time from the composer, and review what Claude " +
            "touched in the Activity Map."

    const val CONTINUE = "Start using Sightline"
    const val SETTINGS = "Open settings"
}
