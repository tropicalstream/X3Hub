package com.x3hub.app.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdlePolicyTest {

    @Test
    fun openingGraceAllowsTimeToFormFirstQuestion() {
        val policy = policy()
        policy.reset(1_000)

        assertFalse(policy.snapshot(20_999).shouldEnd)
        assertTrue(policy.snapshot(21_000).shouldEnd)
    }

    @Test
    fun completedExchangeUsesShortBetweenTurnTimeout() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)
        policy.onModelActivity(2_000)
        policy.onTurnComplete(3_000)

        val snapshot = policy.snapshot(8_000)
        assertEquals(5_000, snapshot.timeoutMs)
        assertTrue(snapshot.shouldEnd)
    }

    @Test
    fun followupStartingNearIdleDeadlineGetsResponseGrace() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(500)
        policy.onModelActivity(1_000)
        policy.onTurnComplete(2_000)

        policy.onUserActivity(6_900)

        val snapshot = policy.snapshot(7_100)
        assertTrue(snapshot.awaitingModelResponse)
        assertEquals(20_000, snapshot.timeoutMs)
        assertFalse(snapshot.shouldEnd)
    }

    @Test
    fun stalePriorTurnCompleteCannotCancelPendingFollowup() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(4_900)

        policy.onTurnComplete(5_000)

        val snapshot = policy.snapshot(10_100)
        assertTrue(snapshot.awaitingModelResponse)
        assertFalse(snapshot.shouldEnd)
    }

    @Test
    fun incrementalInputActivityExtendsPendingDeadline() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(4_000)
        policy.onUserActivity(8_000)

        assertFalse(policy.snapshot(27_999).shouldEnd)
        assertTrue(policy.snapshot(28_000).shouldEnd)
    }

    @Test
    fun firstModelOutputReturnsToNormalIdlePolicy() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(4_000)

        policy.onModelActivity(12_000)

        val snapshot = policy.snapshot(12_001)
        assertFalse(snapshot.awaitingModelResponse)
        assertEquals(5_000, snapshot.timeoutMs)
    }

    @Test
    fun pendingTurnEventuallyTimesOutIfGeminiNeverAnswers() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(2_000)

        assertFalse(policy.snapshot(21_999).shouldEnd)
        assertTrue(policy.snapshot(22_000).shouldEnd)
    }

    @Test
    fun genericActivityDoesNotPretendAResponseArrived() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)

        policy.onConversationActivity(10_000)

        val snapshot = policy.snapshot(10_001)
        assertTrue(snapshot.awaitingModelResponse)
        assertEquals(20_000, snapshot.timeoutMs)
    }

    @Test
    fun unansweredUserAudioRequestsAFlushAfterOneSecond() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(4_000)

        assertFalse(policy.snapshot(4_999).shouldFlushPendingAudio(afterMs = 1_000))
        assertTrue(policy.snapshot(5_000).shouldFlushPendingAudio(afterMs = 1_000))
    }

    @Test
    fun completedModelTurnNeverRequestsAPendingAudioFlush() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)
        policy.onModelActivity(2_000)

        assertFalse(policy.snapshot(20_000).shouldFlushPendingAudio(afterMs = 1_000))
    }

    private fun policy() = ConversationIdlePolicy(
        openingTimeoutMs = 20_000,
        idleTimeoutMs = 5_000,
        responseTimeoutMs = 20_000
    )
}
