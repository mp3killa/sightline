package io.mp.claudecodepanel.ide

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Decouples user-decision handling (tool-permission approvals, diff reviews) from the Swing widgets
 * that render them. Both the real UI **and** the sandbox test bridge resolve a pending interaction
 * through the same coordinator, which runs the one real handler — so automation drives identical
 * production logic and can never become a hidden approval-bypass path.
 *
 * These are plain classes (no IntelliJ-platform imports) registered as project services in plugin.xml,
 * so the dispatch logic is covered by ordinary JUnit tests like the rest of the tested cores.
 */

enum class ApprovalDecision { ALLOW, ALLOW_ALWAYS, DENY }

sealed interface ApprovalOutcome {
    /** The decision was applied and the interaction resolved. */
    object Applied : ApprovalOutcome
    /** No pending approval had that id (already resolved, or never existed). */
    data class NotFound(val id: String) : ApprovalOutcome
    /** ALLOW_ALWAYS on a request that offered no permission suggestions — left pending. */
    data class Unsupported(val id: String, val decision: ApprovalDecision) : ApprovalOutcome
}

/**
 * A pending `can_use_tool` request. [handler] performs the real response (CLI control response +
 * activity update + UI refresh); the coordinator invokes it exactly once when resolved.
 */
class PendingApproval(
    val id: String,          // the control-protocol request_id
    val toolUseId: String?,
    val toolName: String,
    val title: String,
    val targetPath: String?,
    val canAllowAlways: Boolean,
    private val handler: (ApprovalDecision) -> Unit,
) {
    internal fun invoke(decision: ApprovalDecision) = handler(decision)
}

class ApprovalCoordinator {

    private val pending = ConcurrentHashMap<String, PendingApproval>()
    private val listeners = CopyOnWriteArrayList<Runnable>()

    /** UI/state observers refreshed whenever the pending set changes. */
    fun addListener(l: Runnable) { listeners.add(l) }

    fun register(approval: PendingApproval) { pending[approval.id] = approval; notifyListeners() }

    fun listPending(): List<PendingApproval> = pending.values.toList()
    fun get(id: String): PendingApproval? = pending[id]
    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Resolve a pending approval (human button or test bridge). Runs its handler exactly once. */
    fun respond(id: String, decision: ApprovalDecision): ApprovalOutcome {
        val approval = pending[id] ?: return ApprovalOutcome.NotFound(id)
        if (decision == ApprovalDecision.ALLOW_ALWAYS && !approval.canAllowAlways) {
            return ApprovalOutcome.Unsupported(id, decision)
        }
        pending.remove(id)
        approval.invoke(decision)
        notifyListeners()
        return ApprovalOutcome.Applied
    }

    /** Drop a pending approval without running its handler (e.g. the session was cleared). */
    fun cancel(id: String) { if (pending.remove(id) != null) notifyListeners() }

    fun clear() { if (pending.isNotEmpty()) { pending.clear(); notifyListeners() } }

    private fun notifyListeners() { listeners.forEach { runCatching { it.run() } } }
}

enum class DiffDecision { ACCEPT, REJECT }

/**
 * A pending diff review opened via the `ide` `openDiff` tool. The MCP handler thread waits on
 * [future]; the human's Accept/Reject dialog or the test bridge completes it — whichever comes first.
 */
class PendingDiffReview(
    val id: String,
    val path: String,
    val currentText: String,
    val proposedText: String,
) {
    val future: CompletableFuture<DiffDecision> = CompletableFuture()
}

class DiffReviewCoordinator {

    private val pending = ConcurrentHashMap<String, PendingDiffReview>()
    private val listeners = CopyOnWriteArrayList<Runnable>()

    fun addListener(l: Runnable) { listeners.add(l) }

    fun create(id: String, path: String, currentText: String, proposedText: String): PendingDiffReview {
        val review = PendingDiffReview(id, path, currentText, proposedText)
        pending[id] = review
        notifyListeners()
        return review
    }

    fun listPending(): List<PendingDiffReview> = pending.values.toList()
    fun get(id: String): PendingDiffReview? = pending[id]
    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Resolve a pending diff (human dialog or test bridge). Completes its future once. */
    fun respond(id: String, decision: DiffDecision): Boolean {
        val review = pending.remove(id) ?: return false
        review.future.complete(decision)
        notifyListeners()
        return true
    }

    /** Drop without completing (the handler timed out and gave up waiting). */
    fun remove(id: String) { if (pending.remove(id) != null) notifyListeners() }

    fun clear() {
        pending.values.forEach { it.future.complete(DiffDecision.REJECT) }
        if (pending.isNotEmpty()) { pending.clear(); notifyListeners() }
    }

    private fun notifyListeners() { listeners.forEach { runCatching { it.run() } } }
}
