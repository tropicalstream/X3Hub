package com.x3hub.app.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTurnCoordinatorTest {

    @Test
    fun ordinaryConversationPassesThrough() {
        val coordinator = coordinator()

        assertTrue(coordinator.shouldDeliverTranscript("The answer is five."))
        assertTrue(coordinator.shouldDeliverAudio(2_000))

        val completion = coordinator.onTurnComplete()
        assertFalse(completion.wasRemainderBuffered)
        assertFalse(completion.suppressAsDuplicate)
    }

    @Test
    fun toolFirstResponsePassesThroughWithoutDelay() {
        val coordinator = coordinator()

        assertTrue(coordinator.onToolCall("open-1").shouldDispatch)
        coordinator.onToolResult("open-1", succeeded = true)

        assertTrue(coordinator.shouldDeliverTranscript("The window is open."))
        assertTrue(coordinator.shouldDeliverAudio(2_000))
        assertFalse(coordinator.onTurnComplete().wasRemainderBuffered)
    }

    @Test
    fun exactRepeatedSuccessIsSuppressed() {
        val coordinator = coordinator()

        assertTrue(coordinator.shouldDeliverTranscript("I opened"))
        assertTrue(coordinator.shouldDeliverTranscript("the calendar window."))
        assertTrue(coordinator.onToolCall("open-2").hadSubstantialPreToolOutput)
        coordinator.onToolResult("open-2", succeeded = true)

        // Gemini's Live transcription does not preserve whitespace across
        // fragments, and the repeated sentence can use different boundaries.
        assertFalse(coordinator.shouldDeliverTranscript("I"))
        assertFalse(coordinator.shouldDeliverTranscript("opened the calendar"))
        assertFalse(coordinator.shouldDeliverTranscript("window."))
        assertFalse(coordinator.shouldDeliverAudio(2_000))

        val completion = coordinator.onTurnComplete()
        assertTrue(completion.wasRemainderBuffered)
        assertTrue(completion.suppressAsDuplicate)
        assertFalse(completion.deliverBufferedRemainder)
    }

    @Test
    fun punctuationAndSmallFillerDifferenceStillCountsAsRepeated() {
        val coordinator = coordinator()

        coordinator.shouldDeliverTranscript("Your weather card is now pinned.")
        coordinator.onToolCall("pin-1")
        coordinator.onToolResult("pin-1", succeeded = true)
        coordinator.shouldDeliverTranscript("Okay, your weather card is now pinned!")

        assertTrue(coordinator.onTurnComplete().suppressAsDuplicate)
    }

    @Test
    fun distinctPostToolExplanationIsReplayed() {
        val coordinator = coordinator()

        coordinator.shouldDeliverTranscript("Let me check the live data source.")
        coordinator.onToolCall("live-1")
        coordinator.onToolResult("live-1", succeeded = true)
        assertFalse(
            coordinator.shouldDeliverTranscript(
                "The source is connected and will refresh every fifteen minutes."
            )
        )

        val completion = coordinator.onTurnComplete()
        assertTrue(completion.wasRemainderBuffered)
        assertFalse(completion.suppressAsDuplicate)
        assertTrue(completion.deliverBufferedRemainder)
        assertTrue(completion.bufferedTranscript.startsWith("The source is connected"))
    }

    @Test
    fun expandedResultThatSharesAStartIsNotMistakenForDuplicate() {
        val coordinator = coordinator()

        coordinator.shouldDeliverTranscript("I found the page.")
        coordinator.onToolCall("search-1")
        coordinator.onToolResult("search-1", succeeded = true)
        coordinator.shouldDeliverTranscript(
            "I found the page and opened its accessibility summary in a separate window."
        )

        assertTrue(coordinator.onTurnComplete().deliverBufferedRemainder)
    }

    @Test
    fun shortAcknowledgementDoesNotDelayActualResult() {
        val coordinator = coordinator()

        coordinator.shouldDeliverTranscript("Sure.")
        assertFalse(coordinator.onToolCall("note-1").hadSubstantialPreToolOutput)
        coordinator.onToolResult("note-1", succeeded = true)

        assertTrue(coordinator.shouldDeliverTranscript("The note is on your HUD."))
        assertTrue(coordinator.shouldDeliverAudio(2_000))
    }

    @Test
    fun failedToolAlwaysLeavesExplanationAudible() {
        val coordinator = coordinator()

        coordinator.shouldDeliverTranscript("I will take that picture now.")
        coordinator.onToolCall("camera-1")
        coordinator.onToolResult("camera-1", succeeded = false)

        assertTrue(
            coordinator.shouldDeliverTranscript(
                "The camera is off, so I could not take the picture."
            )
        )
        assertTrue(coordinator.shouldDeliverAudio(2_000))
        assertFalse(coordinator.onTurnComplete().wasRemainderBuffered)
    }

    @Test
    fun audioOnlyPreOutputNeverCausesAnUnverifiableDrop() {
        val coordinator = coordinator(minAudioBytes = 1_000)

        coordinator.shouldDeliverAudio(1_200)
        coordinator.onToolCall("audio-1")
        coordinator.onToolResult("audio-1", succeeded = true)
        assertFalse(coordinator.shouldDeliverAudio(2_000))

        val completion = coordinator.onTurnComplete()
        assertTrue(completion.wasRemainderBuffered)
        assertFalse(completion.suppressAsDuplicate)
        assertTrue(completion.deliverBufferedRemainder)
    }

    @Test
    fun duplicateCallIdDispatchesOnlyOnce() {
        val coordinator = coordinator()

        assertTrue(coordinator.onToolCall("same-id").shouldDispatch)
        assertFalse(coordinator.onToolCall("same-id").shouldDispatch)
    }

    @Test
    fun identicalRequestsWithDifferentCallIdsBothDispatch() {
        val coordinator = coordinator()

        assertTrue(coordinator.onToolCall("request-1").shouldDispatch)
        assertTrue(coordinator.onToolCall("request-2").shouldDispatch)
    }

    @Test
    fun turnCompletionClearsSpeechGateForNextTurn() {
        val coordinator = coordinator()

        coordinator.shouldDeliverTranscript("I opened the first window.")
        coordinator.onToolCall("first")
        coordinator.onToolResult("first", succeeded = true)
        coordinator.shouldDeliverTranscript("I opened the first window.")
        assertTrue(coordinator.onTurnComplete().suppressAsDuplicate)

        assertTrue(coordinator.shouldDeliverTranscript("A new conversation starts here."))
        assertTrue(coordinator.shouldDeliverAudio(2_000))
    }

    @Test
    fun interruptionClearsBufferedRemainder() {
        val coordinator = coordinator()

        coordinator.shouldDeliverTranscript("I opened the interrupted window.")
        coordinator.onToolCall("interrupted")
        coordinator.onToolResult("interrupted", succeeded = true)
        coordinator.shouldDeliverTranscript("I opened the interrupted window.")
        coordinator.onInterrupted()

        assertTrue(coordinator.shouldDeliverTranscript("The user changed the request."))
    }

    @Test
    fun boundedCallHistoryEvictsOldestIdWithoutDedupeByArguments() {
        val coordinator = ToolTurnCoordinator(
            minPreToolChars = 12,
            minPreToolAudioBytes = 1_000,
            maxRememberedCallIds = 2
        )

        assertTrue(coordinator.onToolCall("one").shouldDispatch)
        assertTrue(coordinator.onToolCall("two").shouldDispatch)
        assertTrue(coordinator.onToolCall("three").shouldDispatch)
        assertTrue(coordinator.onToolCall("one").shouldDispatch)
        assertFalse(coordinator.onToolCall("three").shouldDispatch)
    }

    private fun coordinator(minAudioBytes: Int = 1_000) = ToolTurnCoordinator(
        minPreToolChars = 12,
        minPreToolAudioBytes = minAudioBytes
    )
}
