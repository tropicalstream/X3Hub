package com.x3hub.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout

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
 * The activity owns the gestures (pull right → [enter], pull left → [exit]) and
 * owns the rule that neither is reachable while a browser window has focus;
 * [isDimmed] is here so it can also suppress cursor movement, clicks and the
 * tap pipeline while black.
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
        forceBinocularRepaint()
        onDimChanged(true)
    }

    /** Undim. A repaint, not a rebuild — no state was given up on the way in. */
    fun exit() {
        if (!dimmed) return
        dimmed = false
        // INVISIBLE and not GONE: GONE would drop the view out of the layout
        // pass and make the next enter() a relayout instead of a redraw.
        blackout?.visibility = View.INVISIBLE
        forceBinocularRepaint()
        onDimChanged(false)
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
     * The blackout itself, plus the one mark that stays visible while dimmed.
     *
     * The mark is a small chevron at the left edge pointing left, because the
     * way out is a left pull on the temple pad — the affordance and the
     * instruction are the same shape. It is drawn at 20% white, bright enough
     * to prove the app is alive and dim enough not to be a light source in a
     * dark room, and it is deliberately static: anything that pulsed would
     * invalidate the tree every frame and keep the projector busy for the whole
     * time the wearer thought the display was off.
     *
     * No background drawable is set. Filling in onDraw keeps the outline empty,
     * so this large elevated view casts no shadow.
     */
    private inner class BlackoutView : View(host.context) {

        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = MARK_COLOR
            strokeWidth = MARK_STROKE_DP * density
            strokeCap = Paint.Cap.ROUND
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
            // the eye being drawn. Allocates nothing: this runs at frame rate
            // whenever a WebView or the session animates behind the black.
            canvas.drawColor(Color.BLACK)

            val centreY = height * 0.5f
            val tipX = MARK_INSET_DP * density
            val tailX = tipX + MARK_LENGTH_DP * density
            val halfSpan = MARK_HALF_SPAN_DP * density
            canvas.drawLine(tailX, centreY - halfSpan, tipX, centreY, markPaint)
            canvas.drawLine(tipX, centreY, tailX, centreY + halfSpan, markPaint)
        }
    }

    private companion object {
        /** dp; cursorView is 1000dp and this must outrank it. */
        const val DIM_ELEVATION_DP = 4000f

        /** 20% white: readable as "alive", too dim to be a light source. */
        const val MARK_COLOR = 0x33FFFFFF
        const val MARK_STROKE_DP = 1.5f
        const val MARK_INSET_DP = 10f
        const val MARK_LENGTH_DP = 7f
        const val MARK_HALF_SPAN_DP = 6f
    }
}
