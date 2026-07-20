package io.mp.claudecodepanel.ui.state

/**
 * The rule for "stick to the bottom" auto-scrolling. Platform-free so it is unit-tested; the panel feeds
 * it live scrollbar metrics. Auto-follow stays on only while the viewport is within [threshold] px of the
 * bottom, so streaming output follows when the user is at the end but never yanks them away while they
 * scroll back to read.
 */
object ScrollFollow {

    fun isNearBottom(value: Int, visibleAmount: Int, maximum: Int, threshold: Int): Boolean =
        maximum - (value + visibleAmount) <= threshold

    /**
     * Whether to show the "Jump to latest" affordance. Only once following is paused *and* there is
     * something below the viewport to jump to — an un-scrollable transcript must never offer it, or the
     * button would sit there permanently on a short conversation.
     */
    fun shouldOfferJumpToLatest(following: Boolean, visibleAmount: Int, maximum: Int): Boolean =
        !following && maximum > visibleAmount
}
