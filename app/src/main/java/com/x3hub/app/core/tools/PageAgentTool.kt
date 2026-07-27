package com.x3hub.app.core.tools

import android.content.Context
import com.x3hub.app.core.agent.AgentTaskBridge

/**
 * page_agent — hand a task to the agent living inside an open browser
 * window ("play the first Mozart recording", "find the opening hours").
 *
 * The double-tap gesture runs a fixed "what is on this page" task, which
 * is the right default for a glance but cannot express an errand. This is
 * how a spoken errand gets there.
 */
class PageAgentTool(@Suppress("unused") private val context: Context) : AiTapTool {

    override val name = "page_agent"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val task = arg(args, "task", "instruction", "text", "query")
        if (task.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Say what the agent should do on the page.")
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
