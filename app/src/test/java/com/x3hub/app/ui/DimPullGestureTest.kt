package com.x3hub.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DimPullGestureTest {
    private fun tracker() = DimPullGesture()

    @Test
    fun arrivingAtBottomDoesNotCountAsOverscroll() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 180f, overscrollPx = 0f, eligible = true, nowMs = 0))
        assertNull(g.update(0f, 20f, overscrollPx = 20f, eligible = true, nowMs = 50))
    }

    @Test
    fun twoSeparateOrdinarySwipesNeverCombine() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 50f, 50f, true, 0))
        g.endGesture()
        g.beginGesture()
        assertNull(g.update(0f, 50f, 50f, true, 60))
        assertNull(g.update(0f, 5f, 5f, true, 100))
    }

    @Test
    fun slowContinuousPullTriggersAfterDistanceAndDuration() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 30f, 30f, true, 0))
        assertNull(g.update(0f, 30f, 30f, true, 100))
        assertNull(g.update(0f, 30f, 30f, true, 200))
        assertEquals(
            DimPullGesture.Trigger.SUSTAINED_PULL,
            g.update(0f, 30f, 30f, true, 300)
        )
    }

    @Test
    fun fastFlickTriggersWithLessTravel() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(2f, 40f, 40f, true, 0))
        assertEquals(
            DimPullGesture.Trigger.FAST_FLICK,
            g.update(1f, 40f, 40f, true, 40)
        )
    }

    @Test
    fun oneLargeSampleCannotBeMistakenForVelocity() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 100f, 100f, true, 0))
    }

    @Test
    fun aHesitationBreaksContinuity() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 60f, 60f, true, 0))
        assertNull(g.update(0f, 60f, 60f, true, 250))
        assertNull(g.update(0f, 10f, 10f, true, 300))
    }

    @Test
    fun sidewaysTravelDoesNotAccumulate() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(50f, 20f, 20f, true, 0))
        assertNull(g.update(50f, 40f, 40f, true, 40))
        assertNull(g.update(0f, 20f, 20f, true, 80))
    }

    @Test
    fun reversalCancelsProgress() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 50f, 50f, true, 0))
        assertNull(g.update(0f, -5f, 0f, true, 30))
        assertNull(g.update(0f, 30f, 30f, true, 60))
        assertNull(g.update(0f, 30f, 30f, true, 90))
    }

    @Test
    fun anotherSurfaceOwningPadCancelsProgress() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 50f, 50f, true, 0))
        assertNull(g.update(0f, 50f, 50f, false, 30))
        assertNull(g.update(0f, 30f, 30f, true, 60))
        assertNull(g.update(0f, 30f, 30f, true, 90))
    }

    @Test
    fun triggerFiresOnlyOncePerTouch() {
        val g = tracker()
        g.beginGesture()
        assertNull(g.update(0f, 40f, 40f, true, 0))
        assertEquals(
            DimPullGesture.Trigger.FAST_FLICK,
            g.update(0f, 40f, 40f, true, 40)
        )
        assertNull(g.update(0f, 80f, 80f, true, 80))
    }
}
