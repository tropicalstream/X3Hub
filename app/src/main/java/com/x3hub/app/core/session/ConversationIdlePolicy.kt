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
        val awaitingModelResponse: Boolean
    ) {
        val shouldEnd: Boolean
            get() = idleForMs >= timeoutMs

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

    @Synchronized
    fun reset(nowMs: Long) {
        lastActivityMs = nowMs
        heardUser = false
        awaitingModelResponse = false
        tentativeSpeech = false
    }

    /** Real microphone speech, input transcription, or a debug voice turn. */
    @Synchronized
    fun onUserActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        heardUser = true
        awaitingModelResponse = true
        // Real evidence — whatever tentative state preceded it is confirmed
        // and a later fizzle must not roll this back.
        tentativeSpeech = false
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
        awaitingModelResponse = true
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
    }

    /** Text, audio, or a tool call produced for the pending user turn. */
    @Synchronized
    fun onModelActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        awaitingModelResponse = false
        // An answer arrived; the turn is settled either way.
        tentativeSpeech = false
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
        return Snapshot(
            idleForMs = (nowMs - lastActivityMs).coerceAtLeast(0L),
            timeoutMs = timeout,
            heardUser = heardUser,
            awaitingModelResponse = awaitingModelResponse
        )
    }
}
