package com.x3hub.app.core.session

/**
 * Enforces exactly-once behavior around Gemini Live tool turns.
 *
 * Native-audio Live can speak a complete success sentence, emit the tool
 * call, receive its result, then speak the same sentence again — all before
 * the single turnComplete. Prompting helps but cannot enforce a transport
 * invariant, so the host keeps the invariant here.
 *
 * The gate is structural rather than phrase-based:
 *  - duplicate call IDs are dispatched once;
 *  - if substantial speech preceded a successful tool call, the remainder is
 *    buffered until the turn is complete;
 *  - only a repeated remainder is dropped; a distinct explanation is replayed;
 *  - a tiny acknowledgement does not buffer the actual result;
 *  - a failed tool always gets to explain the failure.
 *
 * Buffer-then-compare matters: blindly dropping everything after a tool would
 * also erase useful results after a preamble such as "Let me check that."
 *
 * This class has no Android dependencies so all event orderings can be unit
 * tested without a device or a model connection.
 */
internal class ToolTurnCoordinator(
    private val minPreToolChars: Int = DEFAULT_MIN_PRE_TOOL_CHARS,
    private val minPreToolAudioBytes: Int = DEFAULT_MIN_PRE_TOOL_AUDIO_BYTES,
    private val maxRememberedCallIds: Int = DEFAULT_MAX_CALL_IDS
) {

    data class ToolCallDecision(
        val shouldDispatch: Boolean,
        val hadSubstantialPreToolOutput: Boolean
    )

    data class TurnCompletion(
        val wasRemainderBuffered: Boolean,
        val suppressAsDuplicate: Boolean,
        val bufferedTranscript: String
    ) {
        val deliverBufferedRemainder: Boolean
            get() = wasRemainderBuffered && !suppressAsDuplicate
    }

    private val seenToolCallIds = LinkedHashSet<String>()

    private var deliveredTranscriptChars = 0
    private var deliveredAudioBytes = 0
    private val deliveredTokens = ArrayList<String>()
    private val bufferedTokens = ArrayList<String>()
    private val bufferedTranscript = StringBuilder()
    private var bufferRemainder = false

    /**
     * Register a transcription fragment and say whether it should reach the
     * chat card. Count non-whitespace characters so chunk boundaries and
     * punctuation do not influence the threshold.
     */
    @Synchronized
    fun shouldDeliverTranscript(text: String): Boolean {
        if (bufferRemainder) {
            bufferedTranscript.append(text)
            bufferedTokens.addAll(tokenize(text))
            return false
        }
        deliveredTranscriptChars += text.count { !it.isWhitespace() }
        deliveredTokens.addAll(tokenize(text))
        return true
    }

    /**
     * Register one PCM output chunk and say whether it should be played now.
     * False means the caller must retain it until [onTurnComplete] decides
     * whether the remainder is repeated or distinct.
     */
    @Synchronized
    fun shouldDeliverAudio(byteCount: Int): Boolean {
        if (bufferRemainder) return false
        deliveredAudioBytes += byteCount.coerceAtLeast(0)
        return true
    }

    /**
     * Idempotency decision for a Live function call.
     *
     * The call ID is the API's identity. Arguments are deliberately not part
     * of dedupe: two separate user requests may legitimately have identical
     * names and arguments, and different IDs must both run.
     */
    @Synchronized
    fun onToolCall(callId: String): ToolCallDecision {
        if (callId.isNotBlank() && !seenToolCallIds.add(callId)) {
            return ToolCallDecision(
                shouldDispatch = false,
                hadSubstantialPreToolOutput = false
            )
        }
        trimRememberedCallIds()
        val substantial = hasSubstantialDeliveredOutput()
        return ToolCallDecision(
            shouldDispatch = true,
            hadSubstantialPreToolOutput = substantial
        )
    }

    /**
     * Buffer the remainder only after the tool actually succeeds. Failures
     * remain live even when Gemini spoke before trying the action.
     */
    @Synchronized
    fun onToolResult(callId: String, succeeded: Boolean) {
        if (succeeded && hasSubstantialDeliveredOutput()) {
            bufferRemainder = true
        }
    }

    /**
     * End one model turn and classify any buffered remainder.
     *
     * Call IDs stay remembered for the session so a late retransmission
     * cannot run a tool twice. All speech state is turn-local.
     */
    @Synchronized
    fun onTurnComplete(): TurnCompletion {
        val wasBuffered = bufferRemainder
        val postToolText = bufferedTranscript.toString()
        val duplicate = wasBuffered && isNearDuplicate(deliveredTokens, bufferedTokens)
        val completion = TurnCompletion(
            wasRemainderBuffered = wasBuffered,
            suppressAsDuplicate = duplicate,
            bufferedTranscript = postToolText
        )
        resetTurn()
        return completion
    }

    /** A server interruption also ends the partial output turn. */
    fun onInterrupted() {
        onTurnComplete()
    }

    /** New websocket/session: no event identity crosses this boundary. */
    @Synchronized
    fun resetSession() {
        seenToolCallIds.clear()
        resetTurn()
    }

    private fun hasSubstantialDeliveredOutput(): Boolean =
        deliveredTranscriptChars >= minPreToolChars ||
            deliveredAudioBytes >= minPreToolAudioBytes

    private fun resetTurn() {
        deliveredTranscriptChars = 0
        deliveredAudioBytes = 0
        deliveredTokens.clear()
        bufferedTokens.clear()
        bufferedTranscript.setLength(0)
        bufferRemainder = false
    }

    private fun trimRememberedCallIds() {
        while (seenToolCallIds.size > maxRememberedCallIds.coerceAtLeast(1)) {
            val oldest = seenToolCallIds.firstOrNull() ?: return
            seenToolCallIds.remove(oldest)
        }
    }

    /**
     * Transcriptions can differ in punctuation, contractions, or one filler
     * word even when the wearer hears the same sentence twice. Compare token
     * multisets, requiring both high overlap and similar lengths. This avoids
     * suppressing a result that merely starts with the same preamble.
     */
    private fun isNearDuplicate(firstTokens: List<String>, secondTokens: List<String>): Boolean {
        if (firstTokens.size < MIN_DUPLICATE_TOKENS ||
            secondTokens.size < MIN_DUPLICATE_TOKENS
        ) return false
        if (firstTokens == secondTokens) return true

        val smaller = minOf(firstTokens.size, secondTokens.size)
        val larger = maxOf(firstTokens.size, secondTokens.size)
        val lengthRatio = smaller.toDouble() / larger.toDouble()
        if (lengthRatio < MIN_DUPLICATE_LENGTH_RATIO) return false

        val remaining = secondTokens.groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (token in firstTokens) {
            val count = remaining[token] ?: 0
            if (count > 0) {
                common++
                if (count == 1) remaining.remove(token) else remaining[token] = count - 1
            }
        }
        val overlap = common.toDouble() / smaller.toDouble()
        return overlap >= MIN_DUPLICATE_TOKEN_OVERLAP
    }

    private fun tokenize(text: String): List<String> =
        TOKEN_REGEX.findAll(text.lowercase())
            .map { it.value }
            .toList()

    private companion object {
        const val DEFAULT_MIN_PRE_TOOL_CHARS = 12
        const val DEFAULT_MIN_PRE_TOOL_AUDIO_BYTES = 33_600
        const val DEFAULT_MAX_CALL_IDS = 128
        const val MIN_DUPLICATE_TOKENS = 3
        const val MIN_DUPLICATE_LENGTH_RATIO = 0.70
        const val MIN_DUPLICATE_TOKEN_OVERLAP = 0.80
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
    }

}
