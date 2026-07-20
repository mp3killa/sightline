package io.mp.sightline.ui.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowTest {

    @Test fun atBottomFollows() {
        // value + visible == maximum → exactly at the end.
        assertTrue(ScrollFollow.isNearBottom(value = 900, visibleAmount = 100, maximum = 1000, threshold = 48))
    }

    @Test fun withinThresholdFollows() {
        assertTrue(ScrollFollow.isNearBottom(value = 860, visibleAmount = 100, maximum = 1000, threshold = 48))
    }

    @Test fun scrolledUpDoesNotFollow() {
        assertFalse(ScrollFollow.isNearBottom(value = 200, visibleAmount = 100, maximum = 1000, threshold = 48))
    }

    @Test fun shortContentAlwaysFollows() {
        // Content fits the viewport (maximum == visible): always "at the bottom".
        assertTrue(ScrollFollow.isNearBottom(value = 0, visibleAmount = 500, maximum = 500, threshold = 48))
    }

    @Test fun offersJumpWhenPausedWithContentBelow() {
        assertTrue(ScrollFollow.shouldOfferJumpToLatest(following = false, visibleAmount = 100, maximum = 1000))
    }

    @Test fun noJumpWhileFollowing() {
        assertFalse(ScrollFollow.shouldOfferJumpToLatest(following = true, visibleAmount = 100, maximum = 1000))
    }

    @Test fun noJumpWhenNothingToScroll() {
        // A transcript that fits the viewport must never show the affordance, even if `following` went false.
        assertFalse(ScrollFollow.shouldOfferJumpToLatest(following = false, visibleAmount = 500, maximum = 500))
    }
}
