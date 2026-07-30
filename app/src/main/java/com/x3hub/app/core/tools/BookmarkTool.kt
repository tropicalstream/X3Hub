package com.x3hub.app.core.tools

import android.content.Context
import com.x3hub.app.core.bridge.BookmarkBridge
import com.x3hub.app.core.bridge.BookmarkStore
import com.x3hub.app.core.bridge.HudPinStore

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
        HudPinStore.init(context)
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

    /**
     * The pinned pages, same as the settings panel shows.
     *
     * Reading the bookmark store instead would let the spoken answer name
     * pages the wearer cannot find on the board — a save that could not pin
     * (full board) leaves a record with nothing on screen. Whatever is
     * pinned is what they can point at, so that is the answer to "what have
     * I saved".
     */
    private fun list(): String {
        val all = HudPinStore.all().filter { it.type == HudPinStore.TYPE_BOOKMARK }
        if (all.isEmpty()) return "You have no pages pinned yet."
        val names = all.take(8).joinToString(", ") { it.label }
        val more = if (all.size > 8) " and ${all.size - 8} more" else ""
        return "You have ${all.size} page${if (all.size == 1) "" else "s"} pinned: $names$more."
    }

    /** Unpin, and drop the saved record with it so no orphan is left. */
    private fun remove(query: String): String {
        if (query.isBlank()) return "Which pinned page should I forget?"
        val pin = HudPinStore.all()
            .filter { it.type == HudPinStore.TYPE_BOOKMARK }
            .firstOrNull { it.label.contains(query, ignoreCase = true) }
            ?: return "I could not find a pinned page matching $query."
        HudPinStore.remove(pin.id)
        pin.sourceUrl?.let { url ->
            BookmarkStore.all().filter { it.url == url }.forEach { BookmarkStore.remove(it.id) }
        }
        return "Unpinned ${pin.label}."
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
