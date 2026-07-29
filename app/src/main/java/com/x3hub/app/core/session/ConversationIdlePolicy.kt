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

    @Synchronized
    fun reset(nowMs: Long) {
        lastActivityMs = nowMs
        heardUser = false
        awaitingModelResponse = false
    }

    /** Real microphone speech, input transcription, or a debug voice turn. */
    @Synchronized
    fun onUserActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        heardUser = true
        awaitingModelResponse = true
    }

    /** Text, audio, or a tool call produced for the pending user turn. */
    @Synchronized
    fun onModelActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        awaitingModelResponse = false
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
