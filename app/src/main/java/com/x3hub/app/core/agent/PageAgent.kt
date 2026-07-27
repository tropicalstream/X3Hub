package com.x3hub.app.core.agent

import android.content.Context
import android.util.Log
import com.x3hub.app.core.config.KeyFile
import com.x3hub.app.ui.BrowserWindowView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Tell me about this page" — the double-tap action on a browser window.
 *
 * This is deliberately NOT the acting agent that clicks links and fills
 * forms. That one is a 209 KB in-page bundle whose state is per-document
 * and whose panel CSS assumes a full-screen viewport; porting it properly
 * is its own job. What a wearer squinting at a 170px window actually wants
 * first is to be TOLD what is on it, and that needs nothing injected: the
 * text is already in the document, and Groq is already configured.
 *
 * So: pull the visible text out of the page, ask a model about it, say the
 * answer on the HUD. One request, no bundle, no per-window agent state, and
 * nothing that can break the page it is reading.
 */
class PageAgent(
    private val context: Context,
    private val showNotice: (String) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** One at a time: the answer goes to a single HUD line either way. */
    @Volatile private var busy = false

    fun ask(window: BrowserWindowView, question: String = DEFAULT_TASK) {
        if (busy) {
            showNotice("Still reading the last page…")
            return
        }
        val key = resolveKey()
        if (key.isNullOrBlank()) {
            // Name the exact fix. "Agent unavailable" tells the wearer
            // nothing they can act on while wearing glasses.
            showNotice("No Groq key — add one in settings (triple-tap).")
            return
        }
        busy = true
        showNotice("Reading the page…")
        window.extractVisibleText { text ->
            val body = text?.trim().orEmpty()
            if (body.isEmpty()) {
                busy = false
                showNotice("Nothing readable on that page yet.")
                return@extractVisibleText
            }
            val title = window.currentUrl.orEmpty()
            scope.launch {
                val answer = runCatching { request(key, question, title, body) }
                    .getOrElse { e ->
                        Log.w(TAG, "page agent failed", e)
                        null
                    }
                withContext(Dispatchers.Main) {
                    busy = false
                    showNotice(answer ?: "Couldn't reach the page agent.")
                }
            }
        }
    }

    private fun resolveKey(): String? {
        KeyFile.resolveFromDir(context.getExternalFilesDir(null), "groq")
            ?.value?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return context.getSharedPreferences("x3hub_config", Context.MODE_PRIVATE)
            .getString("groq_api_key", null)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun request(key: String, question: String, url: String, pageText: String): String? {
        // The window is small and the reply is spoken onto one HUD line, so
        // the cap is about the answer being readable, not about tokens.
        val clipped = pageText.take(MAX_PAGE_CHARS)
        val messages = JSONArray()
            .put(JSONObject()
                .put("role", "system")
                .put("content",
                    "You are reading a web page aloud to someone wearing AR glasses. " +
                        "Answer in at most two short sentences, plain spoken language, no " +
                        "markdown, no lists, no preamble. If the page does not answer the " +
                        "question, say so in one sentence."))
            .put(JSONObject()
                .put("role", "user")
                .put("content", "Page: $url\n\n$clipped\n\n---\n$question"))

        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("max_tokens", 160)
            .toString()

        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "page agent HTTP ${resp.code}: ${text.take(200)}")
                return if (resp.code == 401) "Groq rejected the key." else null
            }
            return JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    companion object {
        private const val TAG = "X3HubPageAgent"
        private const val DEFAULT_TASK = "What is this page about?"
        private const val MAX_PAGE_CHARS = 12_000
        /** Fast and cheap; the task is summarising, not reasoning. */
        private const val MODEL = "llama-3.3-70b-versatile"
    }
}
