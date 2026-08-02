package com.x3hub.app.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Routes Gemini Live tool calls to X3Gemini's native tools:
 * camera_action, hud_pin, and the "real assistant" trio —
 * assistant_memory (memory + custom instructions), reminder
 * (notification + HUD delivery), and custom_command (saved prompts).
 * Slimmed from TapInsight's ToolDispatcher (which carried a dozen
 * Google/agent/browser tools).
 */
class ToolDispatcher(
    context: Context,
    cameraFrameProvider: () -> String?
) {

    companion object {
        private const val TAG = "ToolDispatcher"

        /**
         * True when this call is a HANDOFF — the tool dispatches work
         * that continues without the session, so the conversation should
         * end once the model finishes acknowledging, exactly as it does
         * for page errands. Lives here rather than in the pipeline
         * because which calls hand off is tool knowledge, and rather
         * than in the tool because by the time execute() returns, the
         * caller no longer knows what kind of call it made.
         */
        fun endsConversation(name: String, args: String): Boolean =
            name == "camera_action" && args.contains("take_photo")
    }

    private val tools: Map<String, AiTapTool> = listOf(
        CameraTool(context, cameraFrameProvider),
        HudPinTool(context, cameraFrameProvider),
        MemoryTool(context),
        ReminderTool(context),
        CommandTool(context),
        // x3hub: "open a browser" becomes a pin of TYPE_BROWSER, which the pin
        // board then builds a live WebView for. The tool itself never touches a
        // view — it runs on a voice-tool coroutine with no activity — so it
        // goes through HudPinStore exactly as HudPinTool does.
        BrowserTool(context),
        PageAgentTool(context),
        // Saving a page needs a picture of it, which means drawing a live
        // View — so unlike every tool above, this one hands the job to the
        // activity through BookmarkBridge and waits for the answer.
        BookmarkTool(context),
        // Reading and steering the window the wearer picked. Both need a
        // live WebView on the main thread, so both go through WindowBridge.
        ReadPageTool(context),
        WindowControlTool(context)
    ).associateBy { it.name }

    fun isSupported(name: String): Boolean = tools.containsKey(name.trim())

    suspend fun dispatch(name: String, argsJson: String): Result<String> {
        val tool = tools[name.trim()]
            ?: return Result.failure(IllegalArgumentException("Unknown tool: $name"))
        val args = parseArgs(argsJson)
        Log.d(TAG, "dispatch $name args=${args.keys}")
        return runCatching { tool.execute(args) }.getOrElse { Result.failure(it) }
    }

    private fun parseArgs(argsJson: String): Map<String, String> {
        if (argsJson.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(argsJson)
            val out = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                out[key] = obj.opt(key)?.toString().orEmpty()
            }
            out
        }.getOrDefault(emptyMap())
    }
}
