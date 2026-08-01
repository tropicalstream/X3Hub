package com.x3hub.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DimTapSequenceTest {

    @Test
    fun singleWaitsForEntireDimSequenceWindow() {
        val taps = DimTapSequence(maxGapMs = 550)
        val first = taps.onTap(1_000)

        assertEquals(1, first.tapCount)
        assertFalse(first.isTripleTap)
        assertEquals(550, first.resolveAfterMs)
    }

    @Test
    fun doubleAlsoWaitsForPossibleThirdTap() {
        val taps = DimTapSequence(maxGapMs = 550)
        taps.onTap(1_000)
        val second = taps.onTap(1_500)

        assertEquals(2, second.tapCount)
        assertFalse(second.isTripleTap)
        assertEquals(550, second.resolveAfterMs)
    }

    @Test
    fun thirdTapAtInclusiveBoundaryCompletesAtomically() {
        val taps = DimTapSequence(maxGapMs = 550)
        taps.onTap(1_000)
        taps.onTap(1_550)
        val third = taps.onTap(2_100)

        assertEquals(3, third.tapCount)
        assertTrue(third.isTripleTap)
        assertEquals(0, third.resolveAfterMs)
    }

    @Test
    fun thirdTapOutsideBoundaryStartsANewSequence() {
        val taps = DimTapSequence(maxGapMs = 550)
        taps.onTap(1_000)
        taps.onTap(1_300)
        val late = taps.onTap(1_851)

        assertEquals(1, late.tapCount)
        assertFalse(late.isTripleTap)
    }

    @Test
    fun recognizedTripleResetsBeforeNextTap() {
        val taps = DimTapSequence(maxGapMs = 550)
        taps.onTap(0)
        taps.onTap(100)
        assertTrue(taps.onTap(200).isTripleTap)
        val next = taps.onTap(250)

        assertEquals(1, next.tapCount)
        assertFalse(next.isTripleTap)
    }

    @Test
    fun explicitResetCancelsAPartialSequence() {
        val taps = DimTapSequence(maxGapMs = 550)
        taps.onTap(0)
        taps.onTap(300)
        taps.reset()
        val next = taps.onTap(400)

        assertEquals(1, next.tapCount)
    }

    @Test
    fun backwardsClockCannotChain() {
        val taps = DimTapSequence(maxGapMs = 550)
        taps.onTap(1_000)
        val next = taps.onTap(900)

        assertEquals(1, next.tapCount)
    }

    @Test
    fun variedHumanRhythmsAllProduceOneTriple() {
        val rhythms = listOf(
            80L to 90L,
            180L to 260L,
            300L to 300L,
            500L to 500L,
            120L to 540L,
            540L to 120L
        )

        rhythms.forEach { (firstGap, secondGap) ->
            val taps = DimTapSequence(maxGapMs = 550)
            val first = taps.onTap(10_000)
            val second = taps.onTap(10_000 + firstGap)
            val third = taps.onTap(10_000 + firstGap + secondGap)

            assertEquals(1, first.tapCount)
            assertEquals(2, second.tapCount)
            assertTrue("rhythm $firstGap/$secondGap", third.isTripleTap)
        }
    }
}
