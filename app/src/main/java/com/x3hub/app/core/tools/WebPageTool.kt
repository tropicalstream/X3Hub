package com.x3hub.app.core.tools

import android.content.Context
import com.x3hub.app.core.bridge.WindowBridge

/**
 * read_page — hand the assistant the text of the page the wearer picked.
 *
 * This is the tool that lets Gemini answer ABOUT a page rather than act on
 * one. page_agent already existed, but it is a different agent running a
 * different model whose job is clicking and typing inside the document;
 * asking it to summarise means the wearer's own assistant never sees the
 * article and cannot be asked a follow-up about it. Handing the text to
 * Gemini instead means "summarise this", "what does it say about X", "is
 * there a phone number on it" and "compare that to what you just told me"
 * are all one conversation.
 *
 * Text, not a screenshot: the window shows a couple of hundred pixels of a
 * long article, so a picture would capture the part the wearer happens to
 * be looking at and nothing else. innerText is the whole document.
 */
class ReadPageTool(@Suppress("unused") private val context: Context) : AiTapTool {

    override val name = "read_page"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val reply = WindowBridge.request("read")
        return Result.success(reply.text)
    }
}

/**
 * window_control — close, scroll, resize and navigate the selected window.
 *
 * The wearer can do all of this by hand, but not while their hands are
 * busy, and "close that" mid-sentence is exactly when speech is worth
 * having. Deliberately separate from read_page so the model does not have
 * to reason about an action argument to answer a question.
 */
class WindowControlTool(@Suppress("unused") private val context: Context) : AiTapTool {

    override val name = "window_control"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = arg(args, "action", "command").lowercase()
        if (action.isEmpty()) {
            return Result.success("Say what to do with the window: close, scroll, bigger, smaller, back or reload.")
        }
        val reply = WindowBridge.request(action, arg(args, "amount", "direction", "value"))
        return Result.success(reply.text)
    }

    /** ToolDispatcher stringifies every value, so an absent arg is "null". */
    private fun arg(args: Map<String, String>, vararg keys: String): String {
        for (k in keys) {
            val v = args[k]?.trim().orEmpty()
            if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) {
                return v.trim('"', '\'').trim().trimEnd('.').trim()
            }
        }
        return ""
    }
}
