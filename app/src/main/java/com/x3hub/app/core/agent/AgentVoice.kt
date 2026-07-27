package com.x3hub.app.core.agent

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.x3hub.app.core.config.KeyFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The wearer's spoken task for the page agent — SmartView's flow, ported.
 *
 * This is the half that makes the agent an agent. Without it a double-tap
 * can only run whatever task the app hard-coded, which means the agent can
 * say what is on a page and nothing else; with it, "summarise this",
 * "open the reviews", "play the first result" all reach it.
 *
 * Whisper rather than the Gemini Live session: Live is a conversation with
 * the ASSISTANT, and a task for the agent is not something the assistant
 * should answer, comment on, or decide to handle itself. A separate short
 * capture keeps the two apart — and it is the arrangement SmartView proved.
 */
object AgentVoice {

    private const val TAG = "X3HubAgentVoice"
    private const val STT_MODEL = "whisper-large-v3-turbo"
    private const val BASE = "https://api.groq.com/openai/v1"

    /** A spoken task is a sentence, not a paragraph. */
    const val MAX_RECORD_MS = 9_000L

    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class Recorder(private val context: Context) {
        private var recorder: MediaRecorder? = null
        private var file: File? = null

        @Volatile
        var isRecording = false
            private set

        fun start(): Boolean {
            stopInternal()
            return runCatching {
                val f = File.createTempFile("task_", ".m4a", context.cacheDir)
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                file = f
                isRecording = true
                true
            }.onFailure {
                Log.w(TAG, "recorder start failed: ${it.message}")
                stopInternal()
            }.getOrDefault(false)
        }

        /** Stop and return the recording, or null if there was nothing usable. */
        fun stop(): File? {
            val f = file
            stopInternal()
            return f?.takeIf { it.exists() && it.length() > 1200 }  // ignore blips
        }

        fun cancel() {
            val f = file
            stopInternal()
            runCatching { f?.delete() }
        }

        private fun stopInternal() {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
        }
    }

    private fun groqKey(context: Context): String {
        KeyFile.resolveFromDir(context.getExternalFilesDir(null), "groq")
            ?.value?.takeIf { it.isNotBlank() }?.let { return it }
        return context.getSharedPreferences("x3hub_config", Context.MODE_PRIVATE)
            .getString("groq_api_key", "").orEmpty().trim()
    }

    fun hasKey(context: Context): Boolean = groqKey(context).isNotBlank()

    /** onResult(text, error): text non-null on success; error names the failure. */
    fun transcribe(context: Context, audio: File, onResult: (String?, String?) -> Unit) {
        val key = groqKey(context)
        if (key.isEmpty()) {
            runCatching { audio.delete() }
            main.post { onResult(null, "No Groq key — triple-tap for settings.") }
            return
        }
        Thread {
            var errMsg: String? = null
            val text = runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))
                    .addFormDataPart("model", STT_MODEL)
                    .addFormDataPart("response_format", "json")
                    // A hint, not a grammar. Whisper is tuned for prose, so a
                    // short clipped instruction lands on whatever ordinary
                    // English is nearest — priming it with the shape of a page
                    // task fixes that at the decoder instead of guessing
                    // downstream. Arbitrary dictation still transcribes fine.
                    .addFormDataPart(
                        "prompt",
                        "Instructions for a web page agent: summarise this page, " +
                            "what does it say about, find, search for, open the, " +
                            "click, play the first result, scroll down, go back, " +
                            "read the reviews, what are the opening hours."
                    )
                    .addFormDataPart("temperature", "0")
                    .build()
                val req = Request.Builder()
                    .url("$BASE/audio/transcriptions")
                    .header("Authorization", "Bearer $key")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    Log.d(TAG, "STT HTTP ${resp.code} (${audio.length()}b): ${raw.take(140)}")
                    if (!resp.isSuccessful) {
                        errMsg = when (resp.code) {
                            401, 403 -> "Groq rejected the key."
                            429 -> "Groq rate limit — wait a moment."
                            else -> "Speech service error (HTTP ${resp.code})."
                        }
                        throw IOException("HTTP ${resp.code}")
                    }
                    JSONObject(raw).optString("text", "").trim()
                }
            }.onFailure { e ->
                Log.w(TAG, "transcribe failed: ${e.message}")
                if (errMsg == null) {
                    val m = e.message.orEmpty()
                    errMsg = if (m.contains("Unable to resolve host") ||
                        m.contains("Network is unreachable") ||
                        m.contains("timeout", true) || m.contains("Failed to connect", true)
                    ) "No internet connection." else "Speech error — try again."
                }
            }.getOrNull()
            runCatching { audio.delete() }
            // Whisper renders silence as punctuation ("." or "..."), which is
            // not blank and used to sail through as a real instruction. Require
            // a letter or digit before it counts as speech.
            val meaningful = text?.takeIf { t -> t.any { it.isLetterOrDigit() } }
            main.post { onResult(meaningful, if (meaningful == null) (errMsg ?: "Didn't catch that.") else null) }
        }.start()
    }
}
