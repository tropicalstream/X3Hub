package com.x3hub.app.core.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.x3hub.app.core.config.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Speaks the page agent's answer aloud.
 *
 * A wearer looking at a 170px window cannot read a paragraph off it, and
 * the HUD notice strip holds about one line — so an answer that is only
 * shown is, in practice, an answer that is lost. SmartView spoke its
 * results and that is the half that makes the agent usable hands-free.
 *
 * Groq's Orpheus rather than the Gemini Live voice: the Live session owns
 * the microphone and its own speaker for as long as it is up, and an
 * agent run usually outlives the session that started it. This is a
 * separate, short-lived AudioTrack that does not contend for either.
 */
object AgentSpeech {

    private const val TAG = "X3HubAgentSpeech"

    /**
     * Gemini rather than Groq's Orpheus, which this used to use. Orpheus is
     * metered per DAY at 3600 tokens — small enough that a wearer runs it out
     * in an afternoon, and every answer after that was silent. Gemini shares
     * the key the assistant already uses, so there is one credential to keep
     * working instead of two.
     *
     * Two models on the same key: a preview endpoint can be withdrawn or
     * wobble, and on glasses the answer must still be spoken.
     */
    private val TTS_MODELS = listOf(
        // Ordered by measured latency, not by version. For the same 223-char
        // answer 2.5-flash took 7.9s against 3.1-flash's 14.7s for near
        // identical audio — and the wearer is standing there waiting.
        "gemini-2.5-flash-preview-tts",
        "gemini-3.1-flash-tts-preview"
    )
    private const val TTS_VOICE = "Kore"
    /** Both models return raw 16-bit mono PCM at this rate — no container. */
    private const val SAMPLE_RATE = 24_000
    private const val BYTES_PER_FRAME = 2          // 16-bit mono
    /** Slack over the clip's own length before we stop waiting for it. */
    private const val DRAIN_GRACE_MS = 3_000L

    /** Long answers are wanted; endless ones are not. */
    private const val MAX_CHARS = 700

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * True while the agent's own voice is actually sounding.
     *
     * The readout draws her at a keyboard while the agent works, and lights
     * the keys while it SPEAKS its result — so this has to mean audio is
     * leaving the speaker, not merely that speak() was called. There is a
     * real gap between the two: the Gemini voice is synthesised over the
     * network first, which is seconds of silence during which lit keys
     * would be a lie.
     */
    @Volatile
    var speaking: Boolean = false
        private set

    /**
     * Loudness of what is sounding RIGHT NOW, 0..1 — or [LEVEL_UNKNOWN]
     * when the voice cannot report it.
     *
     * Measured at the playback head, not at the write cursor: writes run
     * far ahead of the speaker (the whole clip is copied into the track in
     * a few milliseconds), so amplitude taken where we WRITE would lead
     * the audio by the length of the buffer and the keyboard would flash
     * to syllables the wearer has not heard yet. The drain loop already
     * polls playbackHeadPosition to know when the answer ends; sampling
     * the PCM around that frame is what makes the flashing actually
     * synchronous with the voice.
     */
    @Volatile
    var speechLevel: Float = 0f
        private set

    /** The device voice hands back no samples, so there is nothing to measure. */
    const val LEVEL_UNKNOWN = -1f

    /** Fired whenever [speaking] flips, so a HUD can repaint. */
    @Volatile
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private fun requestSpeechFocus() {
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        ).setAudioAttributes(attrs).build()
        audioFocusRequest = req
        val res = runCatching { am.requestAudioFocus(req) }.getOrDefault(
            android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED
        )
        Log.i(TAG, "speech focus request -> $res")
    }

    private fun abandonSpeechFocus() {
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val req = audioFocusRequest
        audioFocusRequest = null
        if (am != null && req != null) {
            runCatching { am.abandonAudioFocusRequest(req) }
        }
    }

    private fun setSpeaking(value: Boolean) {
        if (!value) speechLevel = 0f
        if (speaking == value) return
        speaking = value
        runCatching { onSpeakingChanged?.invoke(value) }
    }

    /**
     * RMS of a short window of PCM centred on the frame now leaving the
     * speaker. Strided rather than exhaustive — this runs every 40ms on a
     * playback thread and a tenth of the samples describes a syllable's
     * envelope just as well as all of them.
     */
    private fun levelAt(wav: ByteArray, frame: Int): Float {
        val windowFrames = SAMPLE_RATE / 25          // 40ms
        val startFrame = (frame - windowFrames / 2).coerceAtLeast(0)
        var i = startFrame * BYTES_PER_FRAME
        val end = minOf(i + windowFrames * BYTES_PER_FRAME, wav.size - 1)
        if (i >= end) return 0f
        var sum = 0.0
        var n = 0
        val stride = BYTES_PER_FRAME * 8
        while (i < end - 1) {
            val sample = ((wav[i + 1].toInt() shl 8) or (wav[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            n++
            i += stride
        }
        if (n == 0) return 0f
        val rms = kotlin.math.sqrt(sum / n) / 32768.0
        // Speech RMS rarely passes ~0.25 even when loud, so scale it into a
        // usable range instead of leaving the keyboard permanently dim.
        return (rms * 4.0).coerceIn(0.0, 1.0).toFloat()
    }

    @Volatile private var track: AudioTrack? = null

    /**
     * The application context, captured on [speak] so the streamed-audio
     * path can reach the AudioManager. play() runs on its own thread with
     * no context of its own, and this is a process singleton, so a volatile
     * field is the honest way to hand it over.
     */
    @Volatile private var appCtx: Context? = null

    @Volatile private var audioFocusRequest: android.media.AudioFocusRequest? = null
    /** Bumped on every new utterance so a late one cannot talk over a newer. */
    @Volatile private var generation = 0

    fun stop() {
        generation++
        // Silence it now, but do NOT release. The thread that built this
        // track is sitting in the drain loop reading playbackHeadPosition,
        // and freeing the native object under it throws
        // IllegalStateException mid-answer. Pause+flush stops the sound
        // immediately; the owning thread sees the generation change and
        // tears down its own track.
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        track = null
        runCatching { deviceTts?.stop() }
        abandonSpeechFocus()
        // Whatever was sounding is not sounding now.
        setSpeaking(false)
    }

    fun speak(context: Context, text: String) {
        val clipped = clean(text)
        Log.i(TAG, "speak() asked for ${clipped.length} chars")
        if (clipped.isEmpty()) return
        appCtx = context.applicationContext
        stop()
        val myGen = ++generation
        val key = geminiKey(context)
        if (key.isBlank()) {
            Log.i(TAG, "no Gemini key — falling back to the device voice")
            speakOnDevice(context, clipped, myGen)
            return
        }
        Thread {
            val wav = runCatching { synth(key, clipped) }.getOrElse {
                Log.w(TAG, "TTS failed", it); null
            }
            if (myGen != generation) return@Thread
            if (wav == null) {
                // Orpheus is metered per DAY and the allowance is small
                // (3600 tokens), so on a wearable it runs out mid-afternoon
                // and every answer after that is silent. Silence is the one
                // outcome this feature cannot have: the HUD holds about a
                // line, so an answer that is not spoken is an answer lost.
                speakOnDevice(context, clipped, myGen)
                return@Thread
            }
            runCatching { play(wav, myGen) }.onFailure {
                Log.w(TAG, "playback failed", it)
                speakOnDevice(context, clipped, myGen)
            }
        }.start()
    }

    // ── Device voice fallback ────────────────────────────────────────
    // Android's own engine: worse than Orpheus, always available, and
    // needs no network — which also makes it the only half of this that
    // works when the glasses are off Wi-Fi.

    @Volatile private var deviceTts: android.speech.tts.TextToSpeech? = null
    @Volatile private var deviceTtsReady = false

    private fun speakOnDevice(context: Context, text: String, gen: Int) {
        // Superseded by a newer answer — staying quiet IS the correct
        // outcome, and warming up an engine to say it anyway is noise.
        if (gen != generation) return
        val app = context.applicationContext
        val existing = deviceTts
        if (existing != null && deviceTtsReady) {
            if (gen != generation) return
            utter(existing, text)
            return
        }
        if (existing != null) return   // an init is already in flight
        val engine = android.speech.tts.TextToSpeech(app) { status ->
            deviceTtsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (!deviceTtsReady) {
                Log.w(TAG, "device TTS unavailable (status=$status) — answer stays on the HUD")
                return@TextToSpeech
            }
            val t = deviceTts ?: return@TextToSpeech
            runCatching {
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_MEDIA for the same routing reason as the
                        // streamed path — the assistant volume group is
                        // silent on these glasses.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            // The answer that triggered the init is only still wanted if
            // nothing newer has been queued while the engine warmed up.
            if (gen == generation) utter(t, text)
        }
        deviceTts = engine
    }

    private fun utter(t: android.speech.tts.TextToSpeech, text: String) {
        // The keyboard must light on this path too — the device voice is
        // what speaks whenever the network TTS is unreachable, which is
        // exactly when the wearer most needs to see that something is
        // happening. The engine reports its own start and end, so this is
        // real audio rather than an optimistic guess.
        runCatching {
            t.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        setSpeaking(true)
                        // Nothing to measure on this path; the readout
                        // falls back to its own rhythm rather than
                        // pretending to know the waveform.
                        speechLevel = LEVEL_UNKNOWN
                    }
                    override fun onDone(utteranceId: String?) = setSpeaking(false)
                    @Deprecated("required by the base class")
                    override fun onError(utteranceId: String?) = setSpeaking(false)
                    override fun onError(utteranceId: String?, errorCode: Int) =
                        setSpeaking(false)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) =
                        setSpeaking(false)
                }
            )
        }
        runCatching {
            t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "x3hub-agent")
        }.onFailure { Log.w(TAG, "device TTS speak failed", it); setSpeaking(false) }
    }

    /**
     * The answer is prose meant for a screen; spoken, its punctuation and
     * bracketed asides land badly. Strip the worst of it rather than
     * reading "open paren Augmented Reality close paren" aloud.
     */
    private fun clean(text: String): String =
        text.replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CHARS)

    private fun geminiKey(context: Context): String =
        ApiKeyStore.resolve(context).orEmpty().trim()

    private fun synth(key: String, text: String): ByteArray? {
        for (model in TTS_MODELS) {
            val pcm = runCatching { synthWith(model, key, text) }.getOrElse {
                Log.w(TAG, "TTS $model failed: ${it.message}"); null
            }
            if (pcm != null && pcm.isNotEmpty()) {
                // Success used to log nothing, which made a silent answer
                // impossible to tell apart from one that never got here.
                Log.i(TAG, "TTS ok via $model (${pcm.size} pcm bytes)")
                return pcm
            }
        }
        return null
    }

    private fun synthWith(model: String, key: String, text: String): ByteArray? {
        val payload = JSONObject()
            .put(
                "contents",
                org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", TTS_VOICE)
                            )
                        )
                    )
            )
            .toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            // Header auth, not ?key= — the current AQ.* keys 404 on the query
            // form for generateContent even though it works for listing models.
            .header("x-goog-api-key", key)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "TTS $model HTTP ${resp.code}: ${body.take(200)}")
                return null
            }
            val part = JSONObject(body)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)
                ?: return null
            val b64 = (part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data"))
                ?.optString("data").orEmpty()
            if (b64.isEmpty()) return null
            return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }
    }

    private fun play(wav: ByteArray, myGen: Int) {
        // Gemini hands back bare PCM (audio/L16), not a container, so every
        // byte is sample data — skipping a header here would clip the first
        // syllable.
        val start = 0
        val pcmLen = wav.size - start
        if (pcmLen <= 0) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA, not USAGE_ASSISTANT. The intuitive choice is
                    // ASSISTANT — this IS the assistant talking — but on this
                    // hardware that usage routes to a STREAM_ASSISTANT volume
                    // group the wearer never turns up, so the speech played
                    // into silence: frames delivered, playback head advancing,
                    // keyboard flashing, nothing audible. The model voice
                    // (GeminiAudioPlayer) is heard precisely because it uses
                    // USAGE_MEDIA -> STREAM_MUSIC, the volume the wearer
                    // actually sets. Match it. Ducking still works: the
                    // transient focus request below asks other media to drop
                    // while she speaks.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, pcmLen.coerceAtMost(1 shl 20)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        // Request audio focus before playing. Without it the model voice
        // (GeminiAudioPlayer, which DOES request focus) was audible and the
        // agent's own voice was not: on this hardware an ASSISTANT-usage
        // track that holds no focus is delivered to the HAL — the frames
        // count and the playback head advances, so it LOOKS like it played
        // and the keyboard flashed — but it is never routed to the speaker.
        // Transient, and it ducks rather than stops other audio, because an
        // errand result is a short interjection over whatever is playing,
        // not a takeover.
        requestSpeechFocus()
        t.play()
        // Audio is leaving the speaker from here — light the keys.
        setSpeaking(true)
        var off = start
        while (off < wav.size && myGen == generation) {
            val n = t.write(wav, off, minOf(minBuf, wav.size - off))
            if (n <= 0) break
            off += n
        }
        // write() returns when the data has been COPIED into the track, not
        // when it has been heard — and the buffer is sized to hold the whole
        // answer, so every write returns almost immediately. Sleeping a fixed
        // 250ms and releasing therefore cut the speech off mid-sentence: it
        // destroyed the track with ~17s of audio still queued inside it.
        // Wait for the playback head to actually reach the end instead.
        val totalFrames = pcmLen / BYTES_PER_FRAME
        val audioMs = totalFrames * 1000L / SAMPLE_RATE
        val deadline = System.currentTimeMillis() + audioMs + DRAIN_GRACE_MS
        var played = 0
        while (myGen == generation && System.currentTimeMillis() < deadline) {
            // Read defensively: an interrupting utterance can retire this
            // track between the generation check above and this call.
            val pos = runCatching { t.playbackHeadPosition }.getOrNull() ?: break
            played = pos
            speechLevel = levelAt(wav, start / BYTES_PER_FRAME + pos)
            if (pos >= totalFrames) break
            Thread.sleep(40)
        }
        if (myGen == generation) runCatching { t.stop() }
        runCatching { t.release() }
        if (track === t) track = null
        abandonSpeechFocus()
        setSpeaking(false)
        Log.i(
            TAG,
            "spoke ${played * 1000L / SAMPLE_RATE}ms of ${audioMs}ms" +
                when {
                    myGen != generation -> " (interrupted)"
                    played < totalFrames -> " (CUT SHORT)"
                    else -> ""
                }
        )
    }

}
