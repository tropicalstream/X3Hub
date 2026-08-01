package com.x3hub.app.ui

/**
 * Counts dim-mode taps without exposing a single or double prefix early.
 *
 * Dim has no target and every count is global: one opens Gemini, two toggle
 * system media mute, and three wake the display. A single shared gap makes a
 * recognized triple atomic. The caller replaces its pending resolution after
 * every [onTap] and acts immediately only when [Update.isTripleTap] is true.
 */
internal class DimTapSequence(
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS
) {
    data class Update(
        val tapCount: Int,
        val isTripleTap: Boolean,
        val resolveAfterMs: Long
    )

    private var tapCount = 0
    private var lastTapMs = UNSET_TIME

    init {
        require(maxGapMs >= 0L) { "maxGapMs must be non-negative" }
    }

    fun onTap(nowMs: Long): Update {
        val chained = lastTapMs != UNSET_TIME &&
            nowMs >= lastTapMs &&
            nowMs - lastTapMs <= maxGapMs
        tapCount = if (chained) tapCount + 1 else 1
        lastTapMs = nowMs

        val triple = tapCount >= TRIPLE_TAP_COUNT
        val update = Update(
            tapCount = if (triple) TRIPLE_TAP_COUNT else tapCount,
            isTripleTap = triple,
            resolveAfterMs = if (triple) 0L else maxGapMs
        )
        if (triple) reset()
        return update
    }

    fun reset() {
        tapCount = 0
        lastTapMs = UNSET_TIME
    }

    private companion object {
        const val DEFAULT_MAX_GAP_MS = 550L
        const val TRIPLE_TAP_COUNT = 3
        const val UNSET_TIME = Long.MIN_VALUE
    }
}
