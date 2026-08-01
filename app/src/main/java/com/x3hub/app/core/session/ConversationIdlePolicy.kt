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
        val pendingTurnExpired: Boolean = false,
        /**
         * How long since the microphone last heard ANYTHING over the speech
         * gate — tentative or confirmed. This is a different question from
         * [idleForMs], and conflating them broke the app in both directions
         * at once. idleForMs answers "has the wearer INTERACTED", and only
         * confirmed evidence may refresh it, or noise keeps a dead session
         * open forever. This answers "is sound arriving RIGHT NOW", and it
         * is the only clock allowed to gate what happens to live audio:
         * measured on the glasses, judging the audio by the interaction
         * clock flushed the stream every watchdog tick while the wearer was
         * mid-sentence — their question reached Gemini diced into
         * quarter-second fragments, each flush's resume re-sent a stale
         * prefix the server heard as barge-in, and the replies died to
         * phantom interruptions.
         */
        val soundQuietForMs: Long = Long.MAX_VALUE / 2
    ) {
        val shouldEnd: Boolean
            get() = pendingTurnExpired ||
                // Idle past the deadline closes the session — but never
                // UNDER live sound: a wearer mid-question is not idle,
                // whatever the confirmation clock says, and speech takes
                // seconds to come back as a transcription. Sound defers
                // the close only up to the noise cap, so a room that never
                // goes quiet still cannot hold an uninteracted session
                // open past a bounded multiple of its deadline.
                (idleForMs >= timeoutMs &&
                    (soundQuietForMs >= SOUND_CLOSE_GRACE_MS ||
                        idleForMs >= timeoutMs * NOISE_CAP_MULTIPLIER))

        /**
         * Flush = "the audio went quiet"; only actual quiet may say so.
         */
        fun shouldFlushPendingAudio(afterMs: Long): Boolean =
            awaitingModelResponse &&
                soundQuietForMs >= afterMs.coerceAtLeast(0L)
    }

    private var lastActivityMs: Long = 0L
    private var openedAtMs: Long = 0L
    private var lastSoundMs: Long = 0L
    private var heardUser = false
    private var confirmedUserActivity = false
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
        openedAtMs = nowMs
        lastSoundMs = nowMs
        heardUser = false
        confirmedUserActivity = false
        awaitingModelResponse = false
        tentativeSpeech = false
        awaitingSinceMs = 0L
        pendingAudioFlushedAtMs = 0L
    }

    /** Real microphone speech, input transcription, or a debug voice turn. */
    @Synchronized
    fun onUserActivity(nowMs: Long) {
        lastActivityMs = maxOf(lastActivityMs, nowMs)
        lastSoundMs = maxOf(lastSoundMs, nowMs)
        heardUser = true
        confirmedUserActivity = true
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
        // The SOUND clock, never the interaction clock: this is what keeps
        // live audio from being flushed mid-word without letting a blip
        // masquerade as the wearer having spoken.
        lastSoundMs = maxOf(lastSoundMs, nowMs)
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
     * clock is deliberately not touched: an unconfirmed level crossing is
     * room noise, not voice interaction, and repeated noise must not keep an
     * unused microphone open beyond its five-second deadline.
     */
    @Synchronized
    fun onTentativeFizzled(nowMs: Long) {
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
        // Connection callbacks, UI work, and raw level-gate blips are not user
        // interaction. Before a transcription/debug turn confirms the wearer,
        // the opening deadline is absolute from session-ready. Otherwise a
        // quiet room with occasional noise can keep a never-used microphone
        // open forever by continually refreshing lastActivityMs.
        val waitingForFirstInteraction = !confirmedUserActivity
        val timeout = when {
            waitingForFirstInteraction -> openingTimeoutMs
            awaitingModelResponse -> responseTimeoutMs
            else -> idleTimeoutMs
        }
        val idleFor = if (waitingForFirstInteraction) {
            nowMs - openedAtMs
        } else {
            nowMs - lastActivityMs
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
            idleForMs = idleFor.coerceAtLeast(0L),
            timeoutMs = timeout,
            heardUser = heardUser,
            awaitingModelResponse = awaitingModelResponse,
            pendingTurnExpired = expired,
            soundQuietForMs = (nowMs - lastSoundMs).coerceAtLeast(0L)
        )
    }

    internal companion object {
        /**
         * How many response windows a single pending turn may span before it
         * is declared runaway. Generous on purpose: this is a backstop for
         * input that never stops and never flushes, not the mechanism that
         * ends a normal turn.
         */
        const val MAX_PENDING_WINDOWS = 3

        /**
         * The mic must have been quiet this long for an expired idle clock
         * to actually close the session. Long enough to bridge the gaps
         * inside a sentence; far shorter than any deadline it defers.
         */
        const val SOUND_CLOSE_GRACE_MS = 2_000L

        /**
         * Sound alone can stretch a deadline at most this many times over.
         * A never-quiet room closes at 4× instead of never — for the 5s
         * opening clock that is 20s, exactly the opening grace the app
         * shipped with before any of this. Real speech confirms itself via
         * transcription long before any cap; only sound that never becomes
         * language ever meets one.
         */
        const val NOISE_CAP_MULTIPLIER = 4
    }
}
