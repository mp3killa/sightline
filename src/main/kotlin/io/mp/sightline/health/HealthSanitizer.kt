package io.mp.sightline.health

/**
 * Scrubs a health report before it leaves the machine. The "Copy sanitised report" action exists so users
 * can paste diagnostics into a public bug tracker, so the bar here is: **nothing that identifies the user
 * or authorises anything may survive.** Platform-free and heavily unit-tested, because a leak here is a
 * real one.
 *
 * The approach is allow-nothing-sensitive rather than deny-a-blocklist: redact aggressively and accept the
 * occasional over-redaction, since a report that is slightly less useful is a fine price for one that never
 * carries a token or a home directory. Order matters — longer, more specific patterns run before the
 * general home-directory rule so `~/.ssh/id_rsa` doesn't first collapse to `~/…` and lose the tell.
 */
object HealthSanitizer {

    private const val HOME_PLACEHOLDER = "~"
    private const val REDACTED = "[redacted]"

    private val userName: String? = System.getProperty("user.name")?.takeIf { it.isNotBlank() }
    private val homeDir: String? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }

    /** A single line's worth of scrubbing. Exposed for testing; [sanitize] applies it to a whole report. */
    fun sanitizeText(input: String): String {
        var s = input

        // 1. Auth material first — a token embedded in a URL must go before the URL is otherwise touched.
        //    Bearer/Basic before the key=value rule: for "Authorization: Bearer TOKEN" the header rule's
        //    value stops at the first space (it would catch "Bearer" and leave the token), so scrub the
        //    credential form first.
        s = BEARER.replace(s) { m -> m.groupValues[1] + " " + REDACTED }
        s = TOKEN_HEADER.replace(s) { m -> m.groupValues[1] + REDACTED }
        s = KEYISH.replace(s, REDACTED)
        s = LONG_HEX.replace(s, REDACTED)

        // 2. The user's own home path → ~, before the bare-username rule so we don't shred the path first.
        homeDir?.let { s = s.replace(it, HOME_PLACEHOLDER) }

        // 3. Any other absolute /Users|/home path → keep only its final segment, which is rarely identifying.
        s = ABSOLUTE_USER_PATH.replace(s) { m -> HOME_PLACEHOLDER + "/…/" + m.groupValues.last() }

        // 4. The bare username anywhere it still survives (e.g. a git email, a hostname).
        userName?.let { name ->
            if (name.length >= 3) s = Regex("\\b" + Regex.escape(name) + "\\b").replace(s, REDACTED)
        }

        // 5. Emails and IPs — identifying, and never needed to diagnose a config problem.
        s = EMAIL.replace(s, REDACTED)
        s = IPV4.replace(s, REDACTED)

        return s
    }

    fun sanitize(report: HealthReport): HealthReport =
        HealthReport(report.checks.map { it.copy(detail = sanitizeText(it.detail), hint = it.hint?.let(::sanitizeText)) })

    /** The copyable plain-text form, already sanitised. */
    fun toReportText(report: HealthReport): String {
        val safe = sanitize(report)
        return buildString {
            appendLine("Sightline health report")
            appendLine("Overall: ${safe.overall} — ${safe.headline}")
            appendLine()
            for (c in safe.checks) {
                appendLine("[${c.status}] ${c.name}: ${c.detail}")
                c.hint?.takeIf { c.status != HealthStatus.OK }?.let { appendLine("    → $it") }
            }
        }.trimEnd() + "\n"
    }

    // --- patterns ---

    // "x-...-authorization: VALUE" / "authToken":"VALUE" / token=VALUE  → keep the key, drop the value.
    private val TOKEN_HEADER =
        Regex("""((?:"?[\w-]*(?:authorization|authToken|api[_-]?key|token|secret|password)"?)\s*[:=]\s*"?)[^"\s,}]+""", RegexOption.IGNORE_CASE)
    private val BEARER = Regex("""\b(Bearer|Basic)\s+[A-Za-z0-9._~+/-]{8,}=*""", RegexOption.IGNORE_CASE)
    // Provider-style keys (sk-..., anthropic keys) — long, high-entropy, prefixed.
    private val KEYISH = Regex("""\b(?:sk|pk|rk|ghp|gho|github_pat)[-_][A-Za-z0-9-_]{16,}""")
    // A bare long hex run (>=24) is almost always a token/hash, never a legitimate diagnostic value.
    private val LONG_HEX = Regex("""\b[0-9a-fA-F]{24,}\b""")

    private val ABSOLUTE_USER_PATH = Regex("""(?:/Users|/home)/[^/\s]+(?:/[^/\s]+)*/([^/\s]+)""")
    private val EMAIL = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
}
