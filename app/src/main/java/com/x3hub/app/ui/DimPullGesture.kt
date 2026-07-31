package com.x3hub.app.ui

import kotlin.math.abs

/**
 * Recognises deliberate travel beyond the display's bottom edge.
 *
 * Progress exists for one physical touch only. The caller supplies actual
 * overscroll (the part of a delta beyond the clamped cursor boundary), which
 * keeps ordinary aiming at the bottom from contributing. Two distinct intents
 * are accepted: a long continuous pull, or a shorter but decisively fast flick.
 */
class DimPullGesture(
    private val sustainedDistancePx: Float = 120f,
    private val sustainedMinDurationMs: Long = 260L,
    private val flickDistancePx: Float = 80f,
    private val flickVelocityPxPerSecond: Float = 900f,
    private val maxContinuousGapMs: Long = 180L,
    private val verticalDominance: Float = 1.1f
) {
    enum class Trigger(val logLabel: String) {
        SUSTAINED_PULL("sustained pull-through"),
        FAST_FLICK("fast flick-through")
    }

    private var touchActive = false
    private var triggered = false
    private var accumulatedPx = 0f
    private var firstSampleMs = UNSET_TIME
    private var lastSampleMs = UNSET_TIME
    private var sampleCount = 0

    fun beginGesture() {
        touchActive = true
        triggered = false
        clearProgress()
    }

    fun endGesture() {
        touchActive = false
        triggered = false
        clearProgress()
    }

    /** Returns a trigger once at most for the active physical touch. */
    fun update(
        deltaX: Float,
        deltaY: Float,
        overscrollPx: Float,
        eligible: Boolean,
        nowMs: Long
    ): Trigger? {
        if (!touchActive || triggered) return null
        if (!eligible ||
            overscrollPx <= 0f ||
            deltaY <= 0f ||
            deltaY < abs(deltaX) * verticalDominance
        ) {
            clearProgress()
            return null
        }

        if (lastSampleMs != UNSET_TIME && nowMs - lastSampleMs > maxContinuousGapMs) {
            clearProgress()
        }
        if (firstSampleMs == UNSET_TIME) firstSampleMs = nowMs
        lastSampleMs = nowMs
        accumulatedPx += overscrollPx
        sampleCount++

        val durationMs = (nowMs - firstSampleMs).coerceAtLeast(0L)
        val velocity = if (durationMs > 0L) accumulatedPx * 1_000f / durationMs else 0f
        val fastFlick = sampleCount >= 2 &&
            accumulatedPx >= flickDistancePx &&
            velocity >= flickVelocityPxPerSecond
        val sustainedPull = accumulatedPx >= sustainedDistancePx &&
            durationMs >= sustainedMinDurationMs
        val result = when {
            fastFlick -> Trigger.FAST_FLICK
            sustainedPull -> Trigger.SUSTAINED_PULL
            else -> null
        }
        if (result != null) triggered = true
        return result
    }

    private fun clearProgress() {
        accumulatedPx = 0f
        firstSampleMs = UNSET_TIME
        lastSampleMs = UNSET_TIME
        sampleCount = 0
    }

    private companion object {
        const val UNSET_TIME = Long.MIN_VALUE
    }
}
