package io.mp.sightline.ui.state

/**
 * What the panel offers a user when the CLI **fails** — as opposed to what it says happened.
 *
 * Until now a failed turn rendered as one red italic line of the CLI's own text ("Failed to
 * authenticate: OAuth session expired and could not be refreshed"). That is true, and it is a dead
 * end: the panel is the only place the user is looking, the conversation is over, and nothing in the
 * UI says what would make it work again. This turns the statement into a card with the two or three
 * things that are actually worth doing next.
 *
 * The rules it is written under, all of them the house rules applied to error text:
 * - **An unmatched message is [Kind.UNKNOWN]**, whose headline is the CLI's own wording and whose
 *   explanation is null. A guessed cause is at its most expensive exactly here, where the user has no
 *   way to check it — the same reason `BuildFailureClassifier` refuses to suggest a fix it can't
 *   evidence. Do not extend the patterns below from something that "looks close enough": every match
 *   should come from a message someone has actually seen the CLI print.
 * - **An action is only offered when it can be carried out.** Retry is gated by the caller
 *   ([classify]'s `canRetry`), which knows whether there is a message to re-send and whether the
 *   failed turn already ran tools — a turn that edited three files before dying must not offer a
 *   one-click way to do it all again.
 * - **The sign-in command is `claude auth login`** — the real shell subcommand, verified against
 *   2.1.235's `claude auth --help`. `/login` is the in-REPL form and would do nothing pasted into a
 *   shell; [SIGN_IN_HINT] states both so a CLI too old for the subcommand still leaves the user
 *   somewhere to go. Sightline cannot sign in itself: the CLI owns auth, holds the credentials, and
 *   the login flow is interactive.
 */
object SessionFailure {

    /** The shell command that signs the CLI in. */
    const val SIGN_IN_COMMAND = "claude auth login"

    /** Said beside the sign-in action, wherever it is offered. */
    const val SIGN_IN_HINT =
        "Sightline can't sign in for you — the CLI owns your login, and the flow is interactive. " +
            "Run $SIGN_IN_COMMAND in a terminal (on an older CLI, start claude and use /login), " +
            "then check it here."

    enum class Kind { AUTH, RATE_LIMIT, CLI_MISSING, BAD_ARGUMENT, NETWORK, UNKNOWN }

    /**
     * A thing the card can offer to do. Typed rather than a label the panel matches on, so a copy
     * tweak can never silently detach a button from its handler.
     */
    enum class Action(val label: String) {
        SIGN_IN("Copy sign-in command"),
        CHECK_SIGN_IN("Check sign-in"),
        RETRY("Retry that message"),
        HEALTH("Health check…"),
        SETTINGS("Settings…"),
        COPY("Copy details"),
    }

    /**
     * [headline] is the one line shown in the error colour; [explanation] is the plain-language "what
     * this means" underneath, and is null whenever there is nothing evidenced to say. [detail] is the
     * CLI's own text, shown verbatim below both and copied by [Action.COPY] — never a truncation of
     * it, since "Copy details" is itself a claim about completeness.
     */
    data class Advice(
        val kind: Kind,
        val headline: String,
        val explanation: String?,
        val actions: List<Action>,
        val detail: String,
    )

    /**
     * Classifies a failure into the card's content.
     *
     * [raw] is the fullest text available — the result's `result`, a `system`/`error` payload, or the
     * captured stderr tail behind a non-zero exit, **not** the one-line summary built from it.
     * [exitCode] is the process exit code where the failure was an exit, else null. [canRetry] is the
     * caller's answer to "is there a message to send again, and is sending it again safe?".
     */
    fun classify(raw: String, exitCode: Int? = null, canRetry: Boolean = false): Advice {
        val detail = raw.trim()
        val hay = detail.lowercase()
        val kind = kindOf(hay, exitCode)
        val actions = actionsFor(kind, canRetry, detail.isNotEmpty())
        return when (kind) {
            Kind.AUTH -> Advice(
                kind,
                "Claude isn't signed in.",
                "The CLI's saved login has expired or was rejected, so nothing was sent. $SIGN_IN_HINT",
                actions, detail,
            )
            Kind.RATE_LIMIT -> Advice(
                kind,
                "Claude is rate limited.",
                "The limit is on your account, not on this message — the same message works once the " +
                    "limit resets. The CLI's own wording is below; it is the only thing that knows when.",
                actions, detail,
            )
            Kind.CLI_MISSING -> Advice(
                kind,
                "The `claude` command could not be run.",
                "Sightline drives the CLI you have installed; it can't work without one. The health " +
                    "check shows which path was resolved and where it came from, and Settings lets you " +
                    "point at a specific binary.",
                actions, detail,
            )
            Kind.BAD_ARGUMENT -> Advice(
                kind,
                "Claude rejected one of its arguments.",
                "The CLI didn't recognise an argument it was started with. If you have set the extra " +
                    "CLI arguments in Settings, that is the first place to look — they are passed through " +
                    "verbatim, including to a CLI version that has since dropped the flag.",
                actions, detail,
            )
            Kind.NETWORK -> Advice(
                kind,
                "Claude couldn't reach the network.",
                "The connection failed before there was an answer, so the message was not refused — it " +
                    "was never delivered. Retrying once the connection is back is the whole fix.",
                actions, detail,
            )
            Kind.UNKNOWN -> Advice(
                kind,
                unknownHeadline(detail, exitCode),
                null,
                actions, detail,
            )
        }
    }

    /**
     * The one place a message becomes a kind. Ordered most-specific first, and every pattern is a
     * literal the CLI or its runtime prints — nothing inferred from a word that merely tends to appear.
     */
    private fun kindOf(hay: String, exitCode: Int?): Kind = when {
        exitCode == 127 -> Kind.CLI_MISSING
        hay.contains("oauth") || hay.contains("authenticat") || hay.contains("unauthorized") ||
            hay.contains("/login") || hay.contains("invalid api key") -> Kind.AUTH
        hay.contains("rate limit") || hay.contains("usage limit") ||
            hay.contains("too many requests") -> Kind.RATE_LIMIT
        hay.contains("command not found") || hay.contains("no such file or directory") ||
            hay.contains("enoent") -> Kind.CLI_MISSING
        hay.contains("unknown option") || hay.contains("unknown argument") ||
            hay.contains("unknown command") -> Kind.BAD_ARGUMENT
        hay.contains("econnrefused") || hay.contains("econnreset") || hay.contains("enotfound") ||
            hay.contains("etimedout") || hay.contains("getaddrinfo") || hay.contains("socket hang up") ||
            hay.contains("network error") || hay.contains("fetch failed") -> Kind.NETWORK
        else -> Kind.UNKNOWN
    }

    /**
     * Sign-in first for [Kind.AUTH]: retry is offered there too, but *after* it, because retrying
     * before signing in reproduces the failure exactly. [Action.COPY] is dropped when there is nothing
     * to copy, rather than offering a button that puts an empty string on the clipboard.
     */
    private fun actionsFor(kind: Kind, canRetry: Boolean, hasDetail: Boolean): List<Action> {
        val actions = when (kind) {
            Kind.AUTH -> listOf(Action.SIGN_IN, Action.CHECK_SIGN_IN, Action.RETRY)
            Kind.RATE_LIMIT -> listOf(Action.RETRY)
            Kind.CLI_MISSING -> listOf(Action.HEALTH, Action.SETTINGS)
            Kind.BAD_ARGUMENT -> listOf(Action.SETTINGS, Action.HEALTH)
            Kind.NETWORK -> listOf(Action.RETRY)
            Kind.UNKNOWN -> listOf(Action.RETRY, Action.HEALTH)
        }
        return actions.filter { it != Action.RETRY || canRetry } + listOfNotNull(Action.COPY.takeIf { hasDetail })
    }

    /**
     * An unrecognised failure says what the CLI said, and where there was no text, what little is
     * known. The first non-blank line only — the rest is shown verbatim below the headline anyway, so
     * a headline running to twenty lines of stack trace would push every action off-screen.
     */
    private fun unknownHeadline(detail: String, exitCode: Int?): String {
        val first = detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val exit = exitCode?.takeIf { it != 0 }
        return when {
            first != null && exit != null -> "Claude exited (code $exit): ${clip(first)}"
            first != null -> clip(first)
            exit != null -> "Claude exited (code $exit), with nothing on stderr to say why."
            else -> "Claude reported an error with no message."
        }
    }

    private fun clip(line: String) = if (line.length <= MAX_HEADLINE) line else line.take(MAX_HEADLINE - 1) + "…"

    private const val MAX_HEADLINE = 180
}
