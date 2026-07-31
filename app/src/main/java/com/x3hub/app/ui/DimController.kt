package com.x3hub.app.ui

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.BatteryManager
import android.view.View
import android.widget.FrameLayout
import java.util.Calendar

/**
 * Dim mode: an opaque black surface draped over the whole logical viewport.
 *
 * On the waveguide black is transparent, so an all-black frame is a display
 * that is simply not there. That makes "get the HUD out of my eyes" a pure
 * drawing change — nothing is stopped, released or unbound, so coming back is
 * a repaint and the Gemini session, its audio and any browser window's page
 * carry on untouched behind the black.
 *
 * Ported from SmartView's enterDim/exitDim
 * (smartview/app/src/main/java/com/smartview/app/MainActivity.kt).
 *
 * Kept: the whole idea — one full-bleed black View toggled by visibility, never
 * torn down; and, above all, SmartView's hard-won rule that dim must NOT touch
 * window flags. Its exitDim once cleared FLAG_KEEP_SCREEN_ON that onCreate had
 * set, so a single dim/undim cycle silently disabled keep-awake for the rest of
 * the run and the display slept mid-article. Nothing here reads or writes
 * window flags, wake locks, audio or the session.
 *
 * Changed, three things:
 *  1. Where the overlay lives. SmartView added it as a second child of its
 *     BinocularSbsLayout, whose onLayout/dispatchDraw walk every child. x3hub's
 *     BinocularSbsLayout lays out and draws getChildAt(0) ONLY, so an overlay
 *     added there would never be measured or drawn. It goes inside the single
 *     logical viewport (mainContainer) instead, which also gets it duplicated
 *     to both eyes for free.
 *  2. Z by elevation rather than by add order, because cursorView sits at
 *     1000dp and would otherwise draw its arrow on top of the blackout.
 *  3. A faint alive-mark, which SmartView had not: its dim was pure black, and
 *     a wearer who pulled right by accident had nothing to distinguish "dimmed"
 *     from "crashed" or "glasses off". See [BlackoutView].
 *
 * The activity owns the gestures (bottom-edge pull-through → [enter],
 * triple tap → [exit]) and owns the rule that entry is not reachable while a
 * browser window has focus; [isDimmed] is here so it can also suppress cursor
 * movement, clicks and the tap pipeline while black.
 */
class DimController(
    private val host: FrameLayout,
    private val onDimChanged: (Boolean) -> Unit = {}
) {

    private val density = host.resources.displayMetrics.density

    private var dimmed = false
    private var blackout: BlackoutView? = null

    val isDimmed: Boolean get() = dimmed

    /** Black out both eyes. Everything keeps running behind it. */
    fun enter() {
        if (dimmed) return
        dimmed = true
        val view = blackout ?: BlackoutView().also {
            // Created on first use, not in the constructor: most sessions never
            // dim, and until then this costs no view, no layout and no draw.
            blackout = it
            host.addView(it)
        }
        view.visibility = View.VISIBLE
        scheduleMinuteTick()
        forceBinocularRepaint()
        onDimChanged(true)
    }

    /** Undim. A repaint, not a rebuild — no state was given up on the way in. */
    fun exit() {
        if (!dimmed) return
        dimmed = false
        // INVISIBLE and not GONE: GONE would drop the view out of the layout
        // pass and make the next enter() a relayout instead of a redraw.
        blackout?.let {
            it.removeCallbacks(minuteTick)
            it.visibility = View.INVISIBLE
        }
        forceBinocularRepaint()
        onDimChanged(false)
    }

    /**
     * The readout changes once a minute, so it is redrawn once a minute —
     * scheduled to the top of the NEXT minute rather than every 60s from an
     * arbitrary phase, so the shown time is never up to a minute stale. This
     * is the only recurring work dim does, and it stops with [exit]; the
     * whole point of the mode is a projector with nothing to do.
     */
    private val minuteTick = Runnable {
        if (dimmed) {
            blackout?.invalidate()
            scheduleMinuteTick()
        }
    }

    private fun scheduleMinuteTick() {
        val v = blackout ?: return
        v.removeCallbacks(minuteTick)
        val now = Calendar.getInstance()
        val msIntoMinute = now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND)
        v.postDelayed(minuteTick, 60_000L - msIntoMinute + 250L)
    }

    /**
     * A child's visibility change already invalidates up the tree, and
     * BinocularSbsLayout.onDescendantInvalidated redraws both eyes. This is the
     * belt to that pair of braces: the mirrored right eye only exists inside
     * dispatchDraw, so a missed invalidate leaves one eye lit and the other
     * black, which is far more unpleasant to wear than it sounds. Costs one
     * invalidate per gesture. The cast fails harmlessly if a caller hosts this
     * somewhere other than under the SBS layout.
     */
    private fun forceBinocularRepaint() {
        (host.parent as? BinocularSbsLayout)?.invalidate()
    }

    /**
     * The blackout itself, plus the one thing that stays visible while
     * dimmed: the time and the battery, small, at the bottom of the view.
     *
     * That pair is the whole display contract of dim mode — the wearer is
     * looking at the WORLD, and a glance down answers the only two questions
     * glasses get asked while worn as glasses. Drawn at 20% white: bright
     * enough to read against a dark room, too dim to be a light source in
     * one, and deliberately static between minute ticks — anything that
     * pulsed would invalidate the tree every frame and keep the projector
     * busy for the whole time the wearer thought the display was off.
     *
     * No background drawable is set. Filling in onDraw keeps the outline
     * empty, so this large elevated view casts no shadow.
     */
    private inner class BlackoutView : View(host.context) {

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MARK_COLOR
            textSize = READOUT_TEXT_DP * density
            textAlign = Paint.Align.CENTER
        }

        init {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Not clickable and not focusable: the temple pad is routed by the
            // activity's dispatchTouchEvent and never reaches the view tree, and
            // the overlay hit-test only walks unipanelOverlay's descendants —
            // this view is a sibling of that, so it can never be mistaken for an
            // inert surface and swallow a tap.
            isClickable = false
            isFocusable = false
            // Above cursorView's 1000dp. Z ordering only competes between
            // siblings, so anything nested inside unipanelOverlay — pins,
            // browser windows, the fullscreen picture viewer — is covered no
            // matter what elevation it gives itself.
            elevation = DIM_ELEVATION_DP * density
        }

        override fun onDraw(canvas: Canvas) {
            // Fills the current clip, which dispatchDraw has already narrowed to
            // the eye being drawn. Allocates nothing on the black itself; the
            // readout formats two small strings once per minute tick.
            canvas.drawColor(Color.BLACK)

            // Sampled at draw time, not cached: the sticky battery intent is
            // a cheap read, and caching it is how a readout shows 40% for an
            // hour. Respects the device's 12/24-hour setting.
            val time = android.text.format.DateFormat.getTimeFormat(context)
                .format(java.util.Date())
            val battery = batteryPercent()?.let { "$it%" } ?: ""
            val line = if (battery.isEmpty()) time else "$time   $battery"
            canvas.drawText(
                line,
                width * 0.5f,
                height - READOUT_BOTTOM_INSET_DP * density,
                textPaint
            )
        }

        private fun batteryPercent(): Int? {
            // The sticky broadcast needs no receiver registration and no
            // lifecycle: null-receiver registerReceiver returns the last
            // ACTION_BATTERY_CHANGED immediately.
            val i = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull() ?: return null
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            return level * 100 / scale
        }
    }

    private companion object {
        /** dp; cursorView is 1000dp and this must outrank it. */
        const val DIM_ELEVATION_DP = 4000f

        /** 20% white: readable as "alive", too dim to be a light source. */
        const val MARK_COLOR = 0x33FFFFFF
        const val READOUT_TEXT_DP = 12f
        /**
         * Clear of the very last rows: the waveguide's edge rows are the
         * first to distort on this projector, and the readout is the only
         * thing on screen — nothing competes for the safer band above.
         */
        const val READOUT_BOTTOM_INSET_DP = 18f
    }
}
