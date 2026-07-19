package io.mp.claudecodepanel.activity

/**
 * Semantic colour roles for node states. Kept platform-free so the state→role mapping can be unit
 * tested for totality; the UI layer resolves each role to a theme-aware `JBColor`.
 *
 * Palette intent (resolved per-theme in the renderer):
 *  GREY idle/historical · CYAN discovered/searched · BLUE read/inspected · PURPLE current focus ·
 *  AMBER editing/proposed · GREEN created/passed/completed · RED error/failed · YELLOW warning ·
 *  PINK generated suggestion/patch · WHITE central task.
 */
enum class ActivityColorRole { IDLE, DISCOVERED, READING, FOCUS, EDITING, SUCCESS, ERROR, WARNING, SUGGESTION, TASK }

object ActivityColorRoles {

    /** Total mapping from every [ActivityNodeState] to a colour role. */
    fun roleForState(state: ActivityNodeState): ActivityColorRole = when (state) {
        ActivityNodeState.IDLE -> ActivityColorRole.IDLE
        ActivityNodeState.DISCOVERED, ActivityNodeState.SEARCHING -> ActivityColorRole.DISCOVERED
        ActivityNodeState.READING, ActivityNodeState.ANALYSING -> ActivityColorRole.READING
        ActivityNodeState.SELECTED -> ActivityColorRole.FOCUS
        ActivityNodeState.EDITING -> ActivityColorRole.EDITING
        ActivityNodeState.CREATED, ActivityNodeState.PASSED, ActivityNodeState.COMPLETED -> ActivityColorRole.SUCCESS
        ActivityNodeState.DELETED, ActivityNodeState.FAILED -> ActivityColorRole.ERROR
        ActivityNodeState.WARNING -> ActivityColorRole.WARNING
        ActivityNodeState.TESTING -> ActivityColorRole.READING
    }

    /** A node's colour role, giving node type precedence for TASK/PATCH which are type-driven. */
    fun roleForNode(node: ActivityNode): ActivityColorRole = when (node.type) {
        ActivityNodeType.TASK -> ActivityColorRole.TASK
        ActivityNodeType.PATCH -> ActivityColorRole.SUGGESTION
        else -> roleForState(node.state)
    }
}
