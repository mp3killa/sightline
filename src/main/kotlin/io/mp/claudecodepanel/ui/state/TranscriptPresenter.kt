package io.mp.claudecodepanel.ui.state

/**
 * Tracks whether the transcript should show its deliberate empty state. The empty state is shown
 * until the first user message of the conversation and returns after a clear/new conversation.
 * Platform-free/testable.
 */
class TranscriptPresenter {

    var userMessageCount: Int = 0
        private set

    val showEmptyState: Boolean get() = userMessageCount == 0

    fun onUserMessage() { userMessageCount++ }

    fun reset() { userMessageCount = 0 }
}
