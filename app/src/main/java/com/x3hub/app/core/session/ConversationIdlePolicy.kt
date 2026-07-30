package com.x3hub.app.core.session

/**
 * Decides when a Gemini Live socket is genuinely idle.
 *
 * The ordinary between-turn timeout stays short. Once the wearer starts a
 * turn, however, server VAD/transcription/model latency is not mutual silence:
 * the app is waiting for a response. That pending state survives a late
 * turnComplete from the previous response and ends only when new model output
 * begins (or the longer response timeout expires).
 */
internal class ConversationIdlePolicy(
    private val openingTimeoutMs: Long,
    private val idleTimeoutMs: Long,
    private val responseTimeoutMs: Long
) {

    data class Snapshot(
        val idleForMs: Long,
        val timeoutMs: Long,
        val heardUser: Boolean,
        val awaitingModelResponse: Boolean,
        /** The pending turn has run out of rope regardless of recent noise. */
        val pendingTurnExpired: Boolean = false
    ) {
        val shouldEnd: Boolean
            get() = pendingTurnExpired || idleForMs >= timeoutMs

        fun shouldFlushPendingAudio(afterMs: Long): Boolean =
            awaitingModelResponse &&
                idleForMs >= afterMs.coerceAtLeast(0L)
    }

    private var lastActivityMs: Long = 0L
    private var heardUser = false
    private var awaitingModelResponse = false
    private var tentativeSpeech = false
    private var priorHeardUser = false
    private var priorAwaiting = false

    /**
     * When the CURRENT pending turn was first heard, and when the audio for
     * it was given up on. Both exist to stop a pending turn being immortal.
     *
     * The response timeout used to run from the last activity, and user
     * activity refreshed it — so a room playing music transcribed a fragment
     * every second or two, each one re-arming the full 20s window, and the
     * session outlived any amount of silence from the actual wearer. A turn
     * gets ONE window, measured from when it started; more noise inside it
     * buys nothing.
     */
    private var awaitingSinceMs: Long = 0L
    private var pendingAudioFlushedAtMs: Long = 0L

    @Synchronized
    fun reset(nowMs: Long) {
        lastActivityMs = nowMs
        heardUser = false
        awaitingModelResponse = false
        tentativeSpeech = false
        awaitingSinceMs = 0L
        pendingAudioFlushedAtMs = 0L
    }

    /** Real microphone speech, input transcription, or a debug voice turn. */
    @Synchronized
    fun onUserActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        heardUser = true
        markAwaiting(nowMs)
        // Real evidence — whatever tentative state preceded it is confirmed
        // and a later fizzle must not roll this back.
        tentativeSpeech = false
    }

    /**
     * Start the pending-turn clock, but only on the transition into waiting.
     * Re-entering while already waiting must not move it, or the ambient-noise
     * case simply moves from refreshing lastActivity to refreshing this.
     */
    private fun markAwaiting(nowMs: Long) {
        if (!awaitingModelResponse) {
            awaitingModelResponse = true
            awaitingSinceMs = nowMs
            pendingAudioFlushedAtMs = 0L
        }
    }

    /**
     * The pipeline gave up on the audio for the pending turn and closed the
     * stream — the language stopped. If the model still produces nothing
     * after that, there was no follow-up, whatever the microphone thought it
     * heard, and the session should close on the SHORT clock rather than sit
     * out the full response window.
     */
    @Synchronized
    fun onPendingAudioFlushed(nowMs: Long) {
        if (!awaitingModelResponse) return
        if (pendingAudioFlushedAtMs == 0L) pendingAudioFlushedAtMs = nowMs
    }

    /**
     * The mic level crossed the speech threshold. That is a MAYBE, not a
     * turn: on these glasses the same signal fires for a door, the wearer's
     * breath, or their own speaker. Treating it as a pending user turn set
     * the generous response timeout and RESET it on every blip — measured on
     * device, seventeen "local speech detected" resumes in ninety-five
     * seconds with not one word transcribed, and a session that would not
     * die while the room made any sound at all. So a maybe latches the
     * waiting state only until it fizzles, and [onTentativeFizzled] puts the
     * flags back exactly as they were.
     */
    @Synchronized
    fun onTentativeUserActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        if (!tentativeSpeech) {
            tentativeSpeech = true
            priorHeardUser = heardUser
            priorAwaiting = awaitingModelResponse
        }
        heardUser = true
        markAwaiting(nowMs)
    }

    /**
     * The tentative speech died with nothing said — the stream went idle
     * again and no transcription or model output confirmed it. Restore the
     * pre-blip state. A turn that was ALREADY pending stays pending; a blip
     * on a quiet session goes back to the short idle clock. The activity
     * bump is kept, because sound did arrive and the session must not close
     * in the same instant it might still be transcribing.
     */
    @Synchronized
    fun onTentativeFizzled(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        if (!tentativeSpeech) return
        tentativeSpeech = false
        heardUser = priorHeardUser
        awaitingModelResponse = priorAwaiting
        if (!awaitingModelResponse) {
            awaitingSinceMs = 0L
            pendingAudioFlushedAtMs = 0L
        }
    }

    /** Text, audio, or a tool call produced for the pending user turn. */
    @Synchronized
    fun onModelActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        awaitingModelResponse = false
        // An answer arrived; the turn is settled either way.
        tentativeSpeech = false
        awaitingSinceMs = 0L
        pendingAudioFlushedAtMs = 0L
    }

    /** Tool work, playback drain, or another event that only refreshes idle. */
    @Synchronized
    fun onConversationActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
    }

    /**
     * A completion can arrive after the next user has already started talking.
     * It refreshes activity but deliberately cannot clear that pending turn.
     */
    fun onTurnComplete(nowMs: Long) {
        onConversationActivity(nowMs)
    }

    @Synchronized
    fun snapshot(nowMs: Long): Snapshot {
        val timeout = when {
            !heardUser -> openingTimeoutMs
            awaitingModelResponse -> responseTimeoutMs
            else -> idleTimeoutMs
        }
        // Two hard stops on a pending turn, because the ordinary idle clock
        // cannot see through noise: it is refreshed by the very ambient sound
        // that is holding the session open.
        val expired = awaitingModelResponse && (
            // PRIMARY: the language stopped — the pipeline gave up on the
            // audio and closed the stream — and the model still said nothing.
            // Then there was no follow-up, whatever the microphone thought it
            // heard, and the turn dies on the short clock. This is the one
            // that catches ambient music, because the measured pattern is a
            // resume every second or two with a flush between each.
            (pendingAudioFlushedAtMs > 0L &&
                nowMs - pendingAudioFlushedAtMs >= idleTimeoutMs) ||
                // BACKSTOP: input that never stops arriving and never gets
                // flushed. The ordinary window is deliberately still
                // refreshable, because a wearer dictating a long sentence
                // emits transcription fragments the whole time and must not
                // be cut off mid-word; this only bounds the pathological
                // case where that never ends.
                nowMs - awaitingSinceMs >= responseTimeoutMs * MAX_PENDING_WINDOWS
            )
        return Snapshot(
            idleForMs = (nowMs - lastActivityMs).coerceAtLeast(0L),
            timeoutMs = timeout,
            heardUser = heardUser,
            awaitingModelResponse = awaitingModelResponse,
            pendingTurnExpired = expired
        )
    }

    private companion object {
        /**
         * How many response windows a single pending turn may span before it
         * is declared runaway. Generous on purpose: this is a backstop for
         * input that never stops and never flushes, not the mechanism that
         * ends a normal turn.
         */
        const val MAX_PENDING_WINDOWS = 3
    }
}
