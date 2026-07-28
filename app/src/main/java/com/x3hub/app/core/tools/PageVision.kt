package com.x3hub.app.core.tools

import android.content.Context
import android.util.Log
import com.x3hub.app.core.config.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Describes a picture on the wearer's display, in words.
 *
 * The obvious design was to hand the Live session the image and let it look
 * for itself. It does not work: an image pushed as client content while a
 * function call is in flight does not reliably enter the context the model
 * generates from, so it answered the tool response having never seen the
 * picture — a lion came back as "a domestic calico cat, a safe pet". Sent
 * as a COMPLETE turn instead it answered twice, a guess and then a
 * correction. Verified against the live endpoint that the model reads this
 * exact image correctly when it is simply in a turn, so the fault was the
 * smuggling, not the picture.
 *
 * So the app does the looking. A separate vision call returns a factual
 * description, and the tool response carries TEXT — which is what a tool
 * response is for. One answer, from something that actually saw it.
 */
object PageVision {

    private const val TAG = "X3HubPageVision"
    private val MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Blocking; call off the main thread. Null when it could not look. */
    fun describe(context: Context, jpegBase64: String): String? {
        val key = ApiKeyStore.resolve(context).orEmpty().trim()
        if (key.isEmpty()) {
            Log.w(TAG, "no Gemini key — cannot describe the page")
            return null
        }
        val prompt =
            "This is a screenshot of a web page on a person's heads-up display. " +
                "Describe what is actually visible, specifically and factually, in at " +
                "most three sentences. If it is a photograph of an animal, name the " +
                "species exactly — do not soften it or guess a friendlier one. If text " +
                "is legible, quote the important parts. Describe only what you can see."
        for (model in MODELS) {
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "image/jpeg")
                                            .put("data", jpegBase64)
                                    )
                                )
                                .put(JSONObject().put("text", prompt))
                        )
                    )
                )
                .toString()
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                // Header auth: the AQ.* keys 404 on the ?key= query form here.
                .header("x-goog-api-key", key)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val text = runCatching {
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "$model HTTP ${resp.code}: ${raw.take(160)}")
                        return@use null
                    }
                    JSONObject(raw)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)
                        ?.optString("text")?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
            }.onFailure { Log.w(TAG, "$model failed: ${it.message}") }.getOrNull()
            if (text != null) {
                Log.i(TAG, "described page via $model (${text.length} chars)")
                return text
            }
        }
        return null
    }
}
