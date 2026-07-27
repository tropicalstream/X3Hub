package com.x3hub.app.core.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.x3hub.app.core.config.KeyFile
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
    private const val TTS_URL = "https://api.groq.com/openai/v1/audio/speech"
    private const val TTS_MODEL = "canopylabs/orpheus-v1-english"
    private const val TTS_VOICE = "autumn"
    private const val SAMPLE_RATE = 24_000

    /** Long answers are wanted; endless ones are not. */
    private const val MAX_CHARS = 700

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var track: AudioTrack? = null
    /** Bumped on every new utterance so a late one cannot talk over a newer. */
    @Volatile private var generation = 0

    fun stop() {
        generation++
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
    }

    fun speak(context: Context, text: String) {
        val clipped = clean(text)
        if (clipped.isEmpty()) return
        val key = groqKey(context)
        if (key.isBlank()) {
            Log.i(TAG, "no Groq key — answer stays on the HUD only")
            return
        }
        stop()
        val myGen = ++generation
        Thread {
            val wav = runCatching { synth(key, clipped) }.getOrElse {
                Log.w(TAG, "TTS failed", it); null
            } ?: return@Thread
            if (myGen != generation) return@Thread
            runCatching { play(wav, myGen) }.onFailure { Log.w(TAG, "playback failed", it) }
        }.start()
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

    private fun groqKey(context: Context): String {
        KeyFile.resolveFromDir(context.getExternalFilesDir(null), "groq")
            ?.value?.takeIf { it.isNotBlank() }?.let { return it }
        return context.getSharedPreferences("x3hub_config", Context.MODE_PRIVATE)
            .getString("groq_api_key", "").orEmpty().trim()
    }

    private fun synth(key: String, text: String): ByteArray? {
        val payload = JSONObject()
            .put("model", TTS_MODEL)
            .put("voice", TTS_VOICE)
            .put("input", text)
            .put("response_format", "wav")
            .toString()
        val req = Request.Builder()
            .url(TTS_URL)
            .header("Authorization", "Bearer $key")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "TTS HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                return null
            }
            return resp.body?.bytes()
        }
    }

    private fun play(wav: ByteArray, myGen: Int) {
        // Skip the RIFF header rather than parsing it: the endpoint is asked
        // for wav and always returns 16-bit mono PCM at 24k, and a malformed
        // header would fail at the AudioTrack write anyway.
        val start = findDataChunk(wav)
        val pcmLen = wav.size - start
        if (pcmLen <= 0) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // SPEECH, not MUSIC: this is the assistant talking, and the
                    // distinction is what lets a page's own audio duck it
                    // rather than fight it.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
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
        t.play()
        var off = start
        while (off < wav.size && myGen == generation) {
            val n = t.write(wav, off, minOf(minBuf, wav.size - off))
            if (n <= 0) break
            off += n
        }
        if (myGen == generation) {
            // Let the tail drain before tearing the track down, or the last
            // word is clipped.
            Thread.sleep(250)
            runCatching { t.stop() }
        }
        runCatching { t.release() }
        if (track === t) track = null
    }

    /** Offset of the PCM payload, or 44 when the header looks standard. */
    private fun findDataChunk(wav: ByteArray): Int {
        var i = 12
        while (i + 8 < wav.size) {
            val id = String(wav, i, 4, Charsets.US_ASCII)
            val size = (wav[i + 4].toInt() and 0xFF) or
                ((wav[i + 5].toInt() and 0xFF) shl 8) or
                ((wav[i + 6].toInt() and 0xFF) shl 16) or
                ((wav[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") return i + 8
            i += 8 + size
        }
        return 44.coerceAtMost(wav.size)
    }

    @Suppress("unused")
    private fun unusedManagerRef(am: AudioManager) = am
}
