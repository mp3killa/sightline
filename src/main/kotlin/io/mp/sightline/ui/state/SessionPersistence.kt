package io.mp.sightline.ui.state

/**
 * Whether Sightline may remember which CLI session belonged to this project, so a conversation can be
 * picked up after the IDE closes.
 *
 * **This is a deliberate exception to a standing decision, and it is written to stay one.** Sightline
 * persists nothing but settings (plus the opt-in Android cache); losing a conversation to an IDE
 * restart is the price of that, and it is the complaint people raise most often about panels like this
 * one. The exception is drawn as narrowly as it can usefully be:
 *
 * - **One id per project. Nothing else.** Not messages, not prompts, not file contents, not paths, not
 *   reasoning — a session id and the moment it was saved. [describe] is what the consent dialog shows,
 *   and it is generated from the stored value so it cannot drift from what is actually kept.
 * - **The transcript is not restored, and the UI must not imply it is.** The CLI holds the
 *   conversation; resuming hands it back to *Claude*, so Claude remembers and the panel starts empty.
 *   Saying "resume your conversation" without that sentence would be the kind of promise this codebase
 *   refuses to make.
 * - **Off until the user says otherwise, once, explicitly.** Enabling is a decision with a privacy
 *   consequence, and a feature that quietly starts recording identifiers because it would be convenient
 *   is precisely what the standing decision exists to prevent.
 *
 * Note what is *not* new risk: the CLI already writes the full transcript of every session under
 * `~/.claude/projects/`, with or without this. The id is a pointer into a store the user already has.
 */
object SessionPersistence {

    /** The consent dialog's title. */
    const val TITLE = "Remember this conversation?"

    /**
     * The consent text. States what is stored, what is not, where the real transcript already lives,
     * and what resuming will and will not bring back — in that order, because the last point is the
     * one a user will otherwise assume wrongly.
     */
    val CONSENT: String = """
        Sightline can remember which Claude session belongs to this project, so you can pick the
        conversation up after closing the IDE.

        What gets saved: a session id and the date — nothing else. No messages, no prompts, no file
        contents, no paths. It is stored with this project's IDE settings.

        Where the conversation itself lives: the Claude Code CLI already keeps the full transcript of
        every session under ~/.claude/projects/, whether or not you turn this on. The id is only a
        pointer into what is already there.

        What resuming does: Claude gets the conversation back, so it remembers what you were doing.
        The panel starts empty — Sightline does not store the transcript and cannot redraw it.

        You can turn this off at any time in Settings, which also forgets the saved id.
    """.trimIndent()

    const val ACCEPT = "Remember it"
    const val DECLINE = "No, don't save anything"

    /** Shown beside the setting and in the resume action's tooltip. */
    fun describe(sessionId: String?, savedIsoDate: String?): String = when {
        sessionId.isNullOrBlank() -> "Nothing saved for this project."
        savedIsoDate.isNullOrBlank() -> "A session id is saved for this project."
        else -> "A session id is saved for this project, from $savedIsoDate."
    }

    /** The resume action's label, which must never promise a transcript it cannot draw. */
    fun resumeLabel(savedIsoDate: String?): String =
        if (savedIsoDate.isNullOrBlank()) "Resume last conversation" else "Resume conversation from $savedIsoDate"

    /** Said in the transcript when a resume starts, so the empty panel is explained rather than odd. */
    const val RESUMED_NOTICE =
        "Resumed the last conversation for this project. Claude has its history back; this panel does " +
            "not — Sightline stores no transcript, so what you see starts here."

    /** A session id is a UUID from the CLI. Anything else is not something to hand back to `--resume`. */
    fun isValidId(id: String?): Boolean =
        id != null && Regex("^[0-9a-fA-F-]{8,64}$").matches(id)
}
