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

    @Test
    fun phantomSpeechBlipsCannotHoldTheSessionOpen() {
        // The measured failure: seventeen "local speech detected" resumes in
        // ninety-five seconds, zero transcriptions, and a session that could
        // not die because each blip re-armed the 20s response window.
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(500)
        policy.onModelActivity(1_000)

        policy.onTentativeUserActivity(2_000)   // a door, a breath, the radio
        policy.onTentativeFizzled(3_200)        // stream idle again, nothing said

        val snapshot = policy.snapshot(8_300)
        assertEquals(5_000, snapshot.timeoutMs)  // short clock, not response grace
        assertTrue(snapshot.shouldEnd)
    }

    @Test
    fun tentativeSpeechConfirmedByTranscriptionKeepsResponseGrace() {
        val policy = policy()
        policy.reset(0)

        policy.onTentativeUserActivity(1_000)
        policy.onUserActivity(1_400)            // transcription arrived — real turn
        policy.onTentativeFizzled(2_600)        // late fizzle must not roll it back

        val snapshot = policy.snapshot(10_000)
        assertEquals(20_000, snapshot.timeoutMs)
        assertFalse(snapshot.shouldEnd)
    }

    @Test
    fun blipDuringAGenuinelyPendingTurnLeavesItPending() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)            // real question, answer pending

        policy.onTentativeUserActivity(3_000)
        policy.onTentativeFizzled(4_200)

        assertTrue(policy.snapshot(4_300).awaitingModelResponse)
    }

    @Test
    fun blipOnAFreshSessionPreservesTheOpeningGrace() {
        val policy = policy()
        policy.reset(0)

        policy.onTentativeUserActivity(1_000)
        policy.onTentativeFizzled(2_200)

        // heardUser must be restored, or a door slam would cut the wearer's
        // 20s to form their first question down to the 5s idle clock.
        val snapshot = policy.snapshot(9_000)
        assertEquals(20_000, snapshot.timeoutMs)
        assertFalse(snapshot.shouldEnd)
    }

    @Test
    fun ambientMusicTranscribedOverAndOverCannotHoldTheSessionOpen() {
        // The reported failure: music in the room is transcribed as a
        // follow-up every second or two. Each fragment was real activity, so
        // it refreshed the 20s response window and the session never died.
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)
        policy.onModelActivity(2_000)
        policy.onTurnComplete(2_500)

        // Replayed as the device actually logged it: a resume every second or
        // two with the stream flushed in between, each fragment counting as
        // real activity and re-arming the whole 20s window.
        var t = 3_000L
        policy.onUserActivity(t)
        policy.onPendingAudioFlushed(t + 1_200)   // first flush starts the clock
        repeat(20) {
            t += 1_500
            policy.onUserActivity(t)              // more music
            policy.onPendingAudioFlushed(t + 1_200)
        }
        // The flush clock started at 4_200 and nothing the music does
        // afterwards may push it. Under the old rule this ran forever.
        assertTrue(policy.snapshot(9_300).shouldEnd)
    }

    @Test
    fun runawayInputThatNeverFlushesStillHitsABackstop() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)
        var t = 1_000L
        repeat(80) { t += 1_000; policy.onUserActivity(t) }   // never flushes

        // The refreshable window alone would never end this. 3 x 20s from
        // the moment the turn began.
        assertTrue(policy.snapshot(61_001).shouldEnd)
    }

    @Test
    fun languageStoppedAndModelSaidNothingClosesOnTheShortClock() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)
        policy.onPendingAudioFlushed(2_200)   // stream given up on

        assertFalse(policy.snapshot(6_000).shouldEnd)
        assertTrue(policy.snapshot(7_200).shouldEnd)   // 5s after the flush
    }

    @Test
    fun aRealAnswerClearsThePendingTurnAndItsDeadlines() {
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)
        policy.onPendingAudioFlushed(2_200)
        policy.onModelActivity(2_500)         // the model DID answer

        // No longer a pending turn: back on the ordinary idle clock, and the
        // flush deadline must not fire behind it. Idle now runs from the
        // model's activity at 2_500, so the 5s rule lands at 7_500 — later
        // than the flush clock's 7_200, which proves it was cleared.
        assertFalse(policy.snapshot(7_400).shouldEnd)
        val s = policy.snapshot(7_600)
        assertFalse(s.awaitingModelResponse)
        assertTrue(s.shouldEnd)               // via the plain 5s idle rule
        assertEquals(5_000, s.timeoutMs)
    }

    @Test
    fun aGenuineFollowUpStillGetsTimeToBeAnswered() {
        // Guard against overcorrecting: the wearer speaks, the model takes a
        // couple of seconds. Nothing here may close the session.
        val policy = policy()
        policy.reset(0)
        policy.onUserActivity(1_000)
        policy.onModelActivity(2_000)
        policy.onTurnComplete(2_400)

        policy.onUserActivity(4_000)          // real follow-up
        assertFalse(policy.snapshot(5_500).shouldEnd)
        policy.onModelActivity(6_000)         // answered 2s later
        assertFalse(policy.snapshot(6_100).shouldEnd)
    }

    private fun policy() = ConversationIdlePolicy(
        openingTimeoutMs = 20_000,
        idleTimeoutMs = 5_000,
        responseTimeoutMs = 20_000
    )
}
