package com.x3hub.app.core.bridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Saved pages, with a thumbnail each.
 *
 * Deliberately NOT HudPinStore. That store is capped at ten pins shared by
 * every type — notes, reminders, live cards and browser windows all compete
 * for the same slots — so a library of bookmarks kept there would evict the
 * wearer's actual HUD after ten saves, and a bookmark would cease to exist
 * the moment it was unpinned. A bookmark outlives its pin: this is the
 * library, and pinning one to the HUD is a separate, optional act.
 *
 * Thumbnails are files, and this class owns their lifetime — [remove]
 * deletes the image. HudPinStore never deletes anything it wrote, which is
 * why deleted picture pins leak their JPEGs today; there is no reason to
 * repeat that here.
 */
object BookmarkStore {

    private const val TAG = "X3HubBookmarks"
    private const val PREFS_FILE = "hud_bookmark_store"
    private const val KEY_ITEMS = "bookmarks"

    /**
     * Generous, because these are cheap — a row of text and a ~12KB JPEG.
     * The limit exists so a runaway loop cannot fill the disk, not to
     * ration anything the wearer does by hand.
     */
    const val MAX_BOOKMARKS = 40

    data class Bookmark(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        /** Absolute path to the thumbnail JPEG, or null if capture failed. */
        val thumbPath: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("url", url)
            .put("title", title)
            .put("thumbPath", thumbPath ?: JSONObject.NULL)
            .put("createdAt", createdAt)

        companion object {
            fun fromJson(o: JSONObject): Bookmark? {
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
                val thumb = o.optString("thumbPath").takeIf {
                    it.isNotBlank() && it != "null"
                }
                return Bookmark(
                    id = o.optString("id").takeIf { it.isNotBlank() }
                        ?: UUID.randomUUID().toString(),
                    url = url,
                    title = o.optString("title").takeIf { it.isNotBlank() } ?: url,
                    thumbPath = thumb,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }
    }

    private var appContext: Context? = null
    private var cache: MutableList<Bookmark>? = null
    private val listeners = mutableListOf<(List<Bookmark>) -> Unit>()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    @Synchronized
    fun all(): List<Bookmark> {
        cache?.let { return it.toList() }
        val loaded = load().toMutableList()
        cache = loaded
        return loaded.toList()
    }

    /**
     * Save a page. Re-saving a URL REPLACES the old entry rather than
     * stacking duplicates — the wearer re-pinning a page means "update
     * this", and the stale thumbnail is deleted with it.
     */
    @Synchronized
    fun add(bookmark: Bookmark): Boolean {
        val list = all().toMutableList()
        val existing = list.indexOfFirst { it.url.equals(bookmark.url, ignoreCase = true) }
        if (existing >= 0) {
            deleteThumb(list[existing])
            list[existing] = bookmark
        } else {
            if (list.size >= MAX_BOOKMARKS) {
                Log.w(TAG, "bookmark limit reached ($MAX_BOOKMARKS)")
                return false
            }
            list.add(0, bookmark)
        }
        commit(list)
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        deleteThumb(list.removeAt(i))
        commit(list)
        return true
    }

    /** Fuzzy removal by title, for "forget the recipe bookmark". */
    @Synchronized
    fun removeByTitle(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val hit = all().firstOrNull {
            it.title.lowercase().contains(q) || q.contains(it.title.lowercase())
        } ?: return null
        return if (remove(hit.id)) hit.title else null
    }

    fun find(id: String): Bookmark? = all().firstOrNull { it.id == id }

    fun observe(listener: (List<Bookmark>) -> Unit): AutoCloseable {
        synchronized(listeners) { listeners.add(listener) }
        listener(all())
        return AutoCloseable { synchronized(listeners) { listeners.remove(listener) } }
    }

    private fun deleteThumb(b: Bookmark) {
        val p = b.thumbPath ?: return
        runCatching { File(p).delete() }
            .onFailure { Log.w(TAG, "could not delete thumbnail $p") }
    }

    private fun commit(list: List<Bookmark>) {
        cache = list.toMutableList()
        persist(list)
        val snapshot = list.toList()
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { runCatching { it(snapshot) } }
    }

    private fun persist(list: List<Bookmark>) {
        val ctx = appContext ?: return
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    private fun load(): List<Bookmark> {
        val ctx = appContext ?: return emptyList()
        val raw = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Bookmark.fromJson(arr.getJSONObject(it)) }
        }.getOrElse {
            Log.w(TAG, "bookmark store unreadable — starting empty")
            emptyList()
        }
    }

    /** Where thumbnails live. Separate from hud_pins so sweeps cannot cross. */
    fun thumbDir(context: Context): File =
        File(context.filesDir, "bookmarks").apply { mkdirs() }
}
