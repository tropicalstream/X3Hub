package com.x3hub.app.ui

/** Small, stable status vocabulary shared by the normal HUD and dim readout. */
object DimActivityStatus {
    const val GEMINI = "✦"
    const val PAGE_AGENT = "⚙"
    const val MEDIA_MUTED = "M"

    fun glyphs(
        geminiActive: Boolean,
        pageAgentBusy: Boolean,
        mediaMuted: Boolean = false
    ): String = buildString {
        if (geminiActive) append(GEMINI)
        if (pageAgentBusy) append(PAGE_AGENT)
        if (mediaMuted) append(MEDIA_MUTED)
    }
}
