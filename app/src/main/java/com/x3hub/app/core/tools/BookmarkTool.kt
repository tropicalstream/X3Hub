package com.x3hub.app.core.tools

import android.content.Context
import com.x3hub.app.core.bridge.BookmarkBridge
import com.x3hub.app.core.bridge.BookmarkStore

/**
 * bookmark_page — save the page the wearer is looking at, with a picture
 * of it ("pin this page", "bookmark that", "what have I saved?").
 *
 * Saving is the only action that cannot be done here. A thumbnail means
 * drawing a live WebView, which is main-thread work in an activity this
 * coroutine cannot see, so [BookmarkBridge] hands the job over and waits
 * for an answer. Listing and forgetting are plain store reads and writes,
 * so they run right here — a bridge hop for those would only add a way to
 * fail.
 */
class BookmarkTool(private val context: Context) : AiTapTool {

    override val name = "bookmark_page"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        BookmarkStore.init(context)
        return when (arg(args, "action", "command").lowercase()) {
            "list", "show", "bookmarks" -> Result.success(list())
            "remove", "delete", "forget" -> Result.success(remove(arg(args, "title", "name", "query")))
            // Saving is the overwhelmingly common intent, so an omitted or
            // unrecognised action means save rather than an error.
            else -> Result.success(save())
        }
    }

    private suspend fun save(): String {
        val saved = BookmarkBridge.bookmarkVisiblePage()
        if (!saved.ok) {
            return saved.error ?: "Could not save that page."
        }
        val title = saved.title?.takeIf { it.isNotBlank() } ?: "the page"
        // Spoken verbatim: one sentence, and it names what was saved so the
        // wearer can tell WHICH window was taken when several are open.
        return "Saved $title to your bookmarks and pinned it to the HUD."
    }

    private fun list(): String {
        val all = BookmarkStore.all()
        if (all.isEmpty()) return "You have no bookmarks saved yet."
        val names = all.take(8).joinToString(", ") { it.title }
        val more = if (all.size > 8) " and ${all.size - 8} more" else ""
        return "You have ${all.size} bookmark${if (all.size == 1) "" else "s"}: $names$more."
    }

    private fun remove(query: String): String {
        if (query.isBlank()) return "Which bookmark should I forget?"
        val removed = BookmarkStore.removeByTitle(query)
        return removed?.let { "Forgot the bookmark for $it." }
            ?: "I could not find a bookmark matching $query."
    }

    /**
     * ToolDispatcher stringifies every JSON value, so an omitted optional
     * argument arrives as the four characters "null" rather than absent.
     */
    private fun arg(args: Map<String, String>, vararg keys: String): String {
        for (k in keys) {
            val v = args[k]?.trim().orEmpty()
            if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) {
                return v.trim('"', '\'', '“', '”', '‘', '’').trim().trimEnd('.').trim()
            }
        }
        return ""
    }
}
