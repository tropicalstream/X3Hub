package com.x3hub.app.core.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.x3hub.app.core.web.LocalPages
import java.io.File
import java.io.FileOutputStream

/**
 * What a brand-new install finds on the board.
 *
 * An empty HUD is an honest state and a terrible first impression: the
 * wearer puts the glasses on, sees nothing, and has no way to learn that
 * this app is about putting live web pages in front of them. Four pins
 * answer that at a glance and cover what the app is actually FOR — video,
 * podcasts, radio, music — each one a place the voice orchestrator and the
 * page agent already know how to drive.
 *
 * Deliberately conservative about when it runs:
 *
 *  - ONCE per install, recorded by a flag rather than inferred from the
 *    board being empty. A wearer who clears these away has said something,
 *    and putting them back every launch would be arguing.
 *  - Never onto a board that already has pins. An existing wearer updating
 *    the app keeps exactly the board they arranged; only a genuinely fresh
 *    install is seeded.
 *
 * The icons are DRAWN, not shipped: a lettermark on the same dark tile the
 * bookmark pins already use. No binary assets in the repository, no
 * network fetch on first run (which would leave blank tiles on a wearer
 * who starts up offline), and nothing that can rot when a site restyles
 * its favicon.
 */
object DefaultPins {

    private const val TAG = "X3HubDefaultPins"
    private const val PREFS_FILE = "x3hub_pins"
    private const val PREF_SEEDED = "default_pins_seeded_v1"

    private data class Seed(
        val label: String,
        val url: String,
        val letter: String,
        val color: Int
    )

    /**
     * Ordered as they should read on the board. Colours are each site's own
     * accent, kept as the GLYPH rather than the background: black is
     * transparent on the waveguide, so a saturated tile would be a lamp in
     * the wearer's eye while a bright mark on dark is simply legible.
     */
    private val SEEDS = listOf(
        Seed("YouTube", "https://m.youtube.com", "Y", 0xFFFF4A5C.toInt()),
        Seed("Podcasts", LocalPages.PLAYER_URL, "P", 0xFF9E8CFF.toInt()),
        Seed("Radio Garden", "https://radio.garden", "R", 0xFF5FD79A.toInt()),
        Seed("Bandcamp", "https://bandcamp.com", "B", 0xFF63C8E0.toInt())
    )

    /** Called once from the activity, after [HudPinStore.init]. */
    fun seedIfFirstRun(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_SEEDED, false)) return
        // Written BEFORE the work, not after: a seed that dies halfway
        // through must not run again on every launch and stack duplicates.
        prefs.edit().putBoolean(PREF_SEEDED, true).apply()

        HudPinStore.init(context)
        if (HudPinStore.all().isNotEmpty()) {
            Log.i(TAG, "board already has pins — not seeding")
            return
        }

        val dir = File(context.applicationContext.filesDir, "default_pins")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create icon dir — skipping seed")
            return
        }
        SEEDS.forEach { seed ->
            val icon = File(dir, "${seed.letter.lowercase()}.png")
            if (!writeIcon(icon, seed)) return@forEach
            HudPinStore.add(
                HudPinStore.HudPin(
                    type = HudPinStore.TYPE_BOOKMARK,
                    label = seed.label,
                    // A bookmark pin's payload IS its picture; the address
                    // it opens rides in sourceUrl.
                    payload = icon.absolutePath,
                    sourceUrl = seed.url
                )
            )
        }
        Log.i(TAG, "seeded ${SEEDS.size} default pins")
    }

    /**
     * A lettermark tile. Drawn at twice the pin's display size so it stays
     * crisp when the board scales it, and only once — the file persists,
     * so later launches pay nothing.
     */
    private fun writeIcon(file: File, seed: Seed): Boolean = runCatching {
        if (file.exists() && file.length() > 0) return@runCatching true
        val w = 132
        val h = 132
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10181E.toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 6f, 6f, bg)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = seed.color
            alpha = 120
        }
        canvas.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), 6f, 6f, ring)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = seed.color
            textSize = 74f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD
            )
        }
        // Centred on the glyph's own metrics, not the box: a baseline at
        // half the height sits the letter noticeably low.
        val metrics = text.fontMetrics
        val baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(seed.letter, w / 2f, baseline, text)
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        true
    }.getOrElse {
        Log.w(TAG, "icon write failed for ${seed.label}: ${it.message}")
        false
    }
}
