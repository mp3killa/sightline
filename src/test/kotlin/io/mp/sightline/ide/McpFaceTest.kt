package io.mp.sightline.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class McpFaceTest {

    @Test fun theSightlinePathSelectsTheModelFacingServer() {
        assertEquals(McpFace.SIGHTLINE, McpFace.of("/sightline"))
        assertEquals(McpFace.SIGHTLINE, McpFace.of("/sightline/"))
        assertEquals(McpFace.SIGHTLINE, McpFace.of("/Sightline"))
    }

    @Test fun aQueryOrFragmentDoesNotHideThePath() {
        assertEquals(McpFace.SIGHTLINE, McpFace.of("/sightline?x=1"))
        assertEquals(McpFace.SIGHTLINE, McpFace.of("/sightline#frag"))
    }

    @Test fun everythingElseIsTheIdeServer() {
        assertEquals(McpFace.IDE, McpFace.of("/"))
        assertEquals(McpFace.IDE, McpFace.of(""))
        assertEquals(McpFace.IDE, McpFace.of(null))
        assertEquals(McpFace.IDE, McpFace.of("/ide"))
        assertEquals(McpFace.IDE, McpFace.of("/sightline/extra"))
        assertEquals(McpFace.IDE, McpFace.of("/sightlines"))
    }

    @Test fun anUnrecognisedPathFailsTowardsTheIdeServerNotTheOtherWay() {
        // Defaulting the other way would put the android tools back on the connection the CLI drives,
        // where its hardcoded allowlist filters them out — the exact bug this split exists to fix.
        assertEquals(McpFace.IDE, McpFace.of("/something-new"))
    }
}
