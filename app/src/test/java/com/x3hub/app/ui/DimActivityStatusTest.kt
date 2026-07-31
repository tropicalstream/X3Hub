package com.x3hub.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DimActivityStatusTest {
    @Test fun idleHasNoGlyph() = assertEquals("", DimActivityStatus.glyphs(false, false))

    @Test fun geminiHasSessionGlyph() =
        assertEquals("✦", DimActivityStatus.glyphs(true, false))

    @Test fun pageAgentHasWorkingGlyph() =
        assertEquals("⚙", DimActivityStatus.glyphs(false, true))

    @Test fun simultaneousWorkShowsBothInStableOrder() =
        assertEquals("✦⚙", DimActivityStatus.glyphs(true, true))

    @Test fun osMediaMuteHasAVisibleDimMarker() =
        assertEquals("M", DimActivityStatus.glyphs(false, false, mediaMuted = true))

    @Test fun muteFollowsActivityGlyphsInStableOrder() =
        assertEquals(
            "✦⚙M",
            DimActivityStatus.glyphs(true, true, mediaMuted = true)
        )
}
