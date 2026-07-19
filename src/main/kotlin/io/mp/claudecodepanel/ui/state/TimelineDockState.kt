package io.mp.claudecodepanel.ui.state

/**
 * State for the collapsible activity-log dock. Auto-scroll only follows new events when the dock is
 * open, following is on, and the user is already near the bottom — so inspecting an earlier event is
 * never yanked away. Platform-free/testable.
 */
class TimelineDockState(
    var expanded: Boolean = false,
    var following: Boolean = true,
) {
    fun shouldAutoScroll(userNearBottom: Boolean): Boolean = expanded && following && userNearBottom

    fun collapsedSummary(eventCount: Int, latest: String?): String {
        val base = "Activity log · $eventCount event" + (if (eventCount == 1) "" else "s")
        return if (latest.isNullOrBlank()) base else "$base · Latest: $latest"
    }
}
