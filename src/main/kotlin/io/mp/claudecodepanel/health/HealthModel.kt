package io.mp.claudecodepanel.health

/**
 * The Health / preflight model — platform-free so it is unit-tested; [HealthChecker] fills it from the
 * live IDE and `ui/HealthDialog` renders it.
 *
 * The panel exists to answer "why isn't this working?" without a support round-trip, so its guiding rule
 * is that it must never overstate what it knows: a check it cannot actually perform reports [UNKNOWN] and
 * says why, rather than guessing [OK]. A confidently wrong green light is worse than an honest "not
 * checked" — it sends the user hunting in the wrong place.
 */
enum class HealthStatus {
    /** Verified working. */
    OK,

    /** Working, but something will bite later (or is merely degraded). */
    WARN,

    /**
     * Genuinely not determinable from here. Never a stand-in for "probably fine". Ranked between [WARN]
     * and [FAIL] deliberately: not knowing is more concerning than a mere warning, but it is *not* a
     * confirmed failure — declaration order is this severity order, which [worseOf] and sorting both use.
     */
    UNKNOWN,

    /** Verified broken — this is very likely the user's problem. */
    FAIL,
    ;

    /** Worst-of, for rolling checks up into one headline. */
    fun worseOf(other: HealthStatus): HealthStatus = if (other.ordinal > ordinal) other else this
}

/**
 * One line of the report. [detail] is already sanitised (see [HealthSanitizer]) because it may be copied
 * to a bug report; [hint] is the "so do this" for anything not [HealthStatus.OK].
 */
data class HealthCheck(
    val id: String,
    val name: String,
    val status: HealthStatus,
    val detail: String,
    val hint: String? = null,
)

data class HealthReport(val checks: List<HealthCheck>) {

    /**
     * The headline. [HealthStatus.UNKNOWN] deliberately does **not** drag the headline down to FAIL — not
     * knowing something is not the same as it being broken — but it does prevent a clean "all good", so the
     * worst-of ordering puts it between WARN and FAIL.
     */
    val overall: HealthStatus
        get() = checks.fold(HealthStatus.OK) { acc, c -> acc.worseOf(c.status) }

    fun byId(id: String): HealthCheck? = checks.firstOrNull { it.id == id }

    /** The checks a user should act on, worst first, then in declared order. */
    val actionable: List<HealthCheck>
        get() = checks.filter { it.status != HealthStatus.OK }
            .sortedByDescending { it.status.ordinal }

    /** A one-line summary for the dialog header. */
    val headline: String
        get() = when (overall) {
            HealthStatus.OK -> "Everything checks out"
            HealthStatus.WARN -> "Working, with ${count(HealthStatus.WARN)} warning${plural(count(HealthStatus.WARN))}"
            HealthStatus.UNKNOWN -> "${count(HealthStatus.UNKNOWN)} check${plural(count(HealthStatus.UNKNOWN))} could not be run"
            HealthStatus.FAIL -> "${count(HealthStatus.FAIL)} problem${plural(count(HealthStatus.FAIL))} found"
        }

    private fun count(s: HealthStatus) = checks.count { it.status == s }
    private fun plural(n: Int) = if (n == 1) "" else "s"
}
