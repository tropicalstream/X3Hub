package com.x3hub.app.core.tools

import android.content.Context
import com.x3hub.app.core.agent.AgentTaskBridge
import com.x3hub.app.core.bridge.HudPinStore

/**
 * page_agent — hand a task to the agent living inside an open browser
 * window ("play the first Mozart recording", "find the opening hours").
 *
 * The double-tap gesture runs a fixed "what is on this page" task, which
 * is the right default for a glance but cannot express an errand. This is
 * how a spoken errand gets there.
 */
class PageAgentTool(private val context: Context) : AiTapTool {

    override val name = "page_agent"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val task = arg(args, "task", "instruction", "text", "query")
        if (task.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Say what the agent should do on the page.")
            )
        }
        // The bridge answers true whenever the activity is up — it says
        // nothing about whether a page exists. With an empty board the
        // errand used to die in a HUD notice the wearer cannot hear, AFTER
        // the model had already been told "the agent is working on it".
        HudPinStore.init(context)
        if (BrowserTool.browserPins().isEmpty()) {
            return Result.success(
                "No page is open, so there is nothing for the agent to work on. " +
                    "Open a browser window first, then hand the errand over."
            )
        }
        return if (AgentTaskBridge.request(task)) {
            Result.success("The agent is working on it.")
        } else {
            Result.failure(IllegalStateException("No browser window is open."))
        }
    }

    private fun arg(args: Map<String, String>, vararg names: String): String {
        for (n in names) args[n]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }
}
