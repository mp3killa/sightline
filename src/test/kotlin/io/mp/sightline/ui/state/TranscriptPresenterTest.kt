package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPresenterTest {

    @Test fun emptyStateShowsUntilFirstUserMessage() {
        val p = TranscriptPresenter()
        assertTrue(p.showEmptyState)
        p.onUserMessage()
        assertFalse(p.showEmptyState)
        assertEquals(1, p.userMessageCount)
    }

    @Test fun resetRestoresEmptyState() {
        val p = TranscriptPresenter()
        p.onUserMessage()
        p.reset()
        assertTrue(p.showEmptyState)
    }
}
