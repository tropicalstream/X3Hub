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
    /**
     * What the assistant is doing, as [AssistantState] bits — a live
     * Gemini session, a working agent, both, or neither. Sampled at draw
     * time; the activity calls [refreshReadout] when it changes, because
     * in dim this readout is the ONLY surface there is, and "still
     * working" and "silently died" must not look identical.
     *
     * This replaced a pair of text glyphs. A wearer should not have to
     * recall what ✦ meant at a glance on a black screen; a figure who
     * visibly listens, or visibly thinks, needs no legend.
     */
    private val assistantState: () -> Int = { AssistantState.IDLE },
    private val onDimChanged: (Boolean) -> Unit = {}
) {

    private val density = host.resources.displayMetrics.density

    private var dimmed = false
    private var blackout: BlackoutView? = null

    /**
     * Readout brightness, 0..1 of full white. The wearer sets it from the
     * settings slider: 20% suits a dark room, daylight can want most of the
     * range. Applied to the time/battery text directly; the activity glyphs
     * keep their fixed ratio ABOVE it (they are the readout's only signal
     * of something happening, so they must never fall below the text).
     */
    private var readoutBrightness = 0.20f

    fun setReadoutBrightness(fraction: Float) {
        val clamped = fraction.coerceIn(0.05f, 1f)
        if (clamped == readoutBrightness) return
        readoutBrightness = clamped
        blackout?.applyBrightness()
        if (dimmed) blackout?.invalidate()
    }

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
            // The assistant's animator outlives nothing: a repaint loop
            // still ticking behind a torn-down readout is exactly the
            // battery leak this app was built to avoid.
            animating = false
            it.removeCallbacks(animTick)
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
     * Lift the blackout without leaving dim.
     *
     * Settings is the one page this app draws while dimmed, and it cannot
     * simply be layered over the blackout: the panel lives inside
     * unipanelOverlay, which is a SIBLING of this view, and Z ordering
     * only competes between siblings — the blackout's elevation buries the
     * whole overlay subtree no matter what elevation the panel gives
     * itself. So the blackout steps aside for the duration.
     *
     * [dimmed] deliberately stays true: it is what keeps DimBridge set, so
     * a window Gemini opens while the page is up is still the one
     * invisible window rather than a new visible sibling. The input gates
     * read the settings flag directly instead.
     */
    fun setBlackoutHidden(hidden: Boolean) {
        if (!dimmed) return
        val v = blackout ?: return
        v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (hidden) {
            v.removeCallbacks(minuteTick)
            animating = false
            animFrameMs = ANIM_FRAME_MS
            animStartMs = 0L
            v.removeCallbacks(animTick)
        } else {
            scheduleMinuteTick()
            syncAnimator()
            v.invalidate()
        }
        forceBinocularRepaint()
    }

    /** Repaint the readout now — the assistant's state changed. */
    fun refreshReadout() {
        if (!dimmed) return
        blackout?.invalidate()
        syncAnimator()
    }

    // ── The assistant's animation ────────────────────────────────────
    //
    // Deliberately the ONLY thing in this app that can repaint faster than
    // once a minute, and it runs only while she is thinking, talking or
    // working. An idle or merely LISTENING figure is drawn once and left
    // alone: a pulse that never stopped would keep the projector and the
    // GPU awake through the whole standby this app exists to make cheap.
    //
    // Talking gets the fast rate because the mouth tracks live audio, and
    // at 80ms speech looks like chewing. Thinking and working only swell
    // three dots, which reads fine at 12fps.

    private var animStartMs = 0L
    private var animating = false
    private var animFrameMs = ANIM_FRAME_MS

    private val animTick = object : Runnable {
        override fun run() {
            val v = blackout ?: return
            if (!dimmed || !animating) return
            // Re-decide every frame rather than trusting the flag set when
            // the loop started. Talking is now answered by the audio player
            // (does the speaker still have audio?), and nothing publishes an
            // event when the last sample drains — so a loop that only
            // stopped on an external notification would keep repainting at
            // 30fps against a black screen forever, which is precisely the
            // drain this app exists to avoid.
            if (!AssistantState.wantsAnimation(assistantState())) {
                animating = false
                animFrameMs = ANIM_FRAME_MS
                animStartMs = 0L
                v.invalidate()
                return
            }
            v.invalidate()
            v.postDelayed(this, animFrameMs)
        }
    }

    /** Start or stop the animation to match what the assistant is doing. */
    private fun syncAnimator() {
        val v = blackout ?: return
        val wants = dimmed && AssistantState.wantsAnimation(assistantState())
        // The rate can change without the animator stopping — she starts
        // talking mid-errand — so re-post on a rate change too, or the
        // mouth would keep running at the dots' lazy 12fps.
        //
        // Three things need the fast rate, all for the same reason: they
        // track something the wearer can HEAR or read as motion. Her mouth
        // follows live audio; the keyboard flashes to the agent's voice,
        // which is sampled every 40ms and would strobe at 12fps; and the
        // fingers type, which at 12fps looks like stamping rather than
        // typing. Dots alone can stay lazy.
        val st = assistantState()
        val rate = if (AssistantState.hasTalking(st) ||
            AssistantState.hasAgentSpeaking(st) ||
            AssistantState.hasAgent(st)
        ) {
            ANIM_FRAME_TALK_MS
        } else {
            ANIM_FRAME_MS
        }
        if (wants == animating && rate == animFrameMs) return
        animating = wants
        animFrameMs = rate
        v.removeCallbacks(animTick)
        if (wants) {
            if (animStartMs == 0L) animStartMs = android.os.SystemClock.uptimeMillis()
            v.post(animTick)
        } else {
            animStartMs = 0L
            v.invalidate()
        }
    }

    /** 0..1 sawtooth over [ANIM_PERIOD_MS]; 0 while still. */
    private fun animPhase(): Float {
        if (!animating) return 0f
        val dt = android.os.SystemClock.uptimeMillis() - animStartMs
        return ((dt % ANIM_PERIOD_MS).toFloat() / ANIM_PERIOD_MS)
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
            textSize = READOUT_TEXT_DP * density
            textAlign = Paint.Align.CENTER
        }

        /**
         * The tube around the text. Neon is a bright core inside a
         * saturated halo, so the readout is drawn twice: this wide, faint,
         * fully-coloured stroke first, then [textPaint]'s near-white fill
         * on top. Two draws of a nine-character string, once a minute.
         */
        private val textGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = READOUT_TEXT_DP * density
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        /**
         * Activity glyphs get their own paint: the same steel cyan the HUD
         * strip uses for them, held brighter than the text by a fixed ratio
         * so the state light stays the readout's loudest element at every
         * brightness the slider can choose.
         */
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = READOUT_TEXT_DP * density
            textAlign = Paint.Align.LEFT
        }

        init {
            applyBrightness()
        }

        /** Re-derive both alphas from [readoutBrightness]. */
        fun applyBrightness() {
            val textAlpha = (readoutBrightness * 255f).toInt().coerceIn(13, 255)
            // Core: the neon hue pushed most of the way to white. A pure
            // white core would lose the colour entirely at this size;
            // pure cyan without the whitening reads as a flat coloured
            // font rather than something lit.
            textPaint.style = Paint.Style.FILL
            textPaint.color = ((textAlpha * 1.7f).toInt().coerceIn(150, 255) shl 24) or NEON_TEXT_CORE
            // Halo: the saturated hue, wide and faint around the core.
            val haloAlpha = (textAlpha * 1.2f).toInt().coerceIn(80, 255)
            textGlowPaint.color = (haloAlpha shl 24) or NEON_TEXT_RGB
            textGlowPaint.strokeWidth = READOUT_TEXT_DP * density * 0.30f
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
            val baseline = height - READOUT_BOTTOM_INSET_DP * density
            canvas.drawText(line, width * 0.5f, baseline, textGlowPaint)
            canvas.drawText(line, width * 0.5f, baseline, textPaint)

            // The assistant, where the ✦/⚙ glyphs used to be: to the RIGHT
            // of the centred line rather than inside it, so the time never
            // shifts sideways when a session starts. She is drawn at every
            // state including idle — a readout whose only occupant appears
            // and vanishes reads as a fault, and her being faintly THERE is
            // what makes "dimmed" distinguishable from "dead".
            val figureSize = READOUT_TEXT_DP * density * ASSISTANT_SCALE
            val lineEnd = width * 0.5f + textPaint.measureText(line) * 0.5f
            AssistantFigure.draw(
                canvas = canvas,
                cx = lineEnd + GLYPH_GAP_DP * density + figureSize * 0.5f,
                // Lifted clear of the text's baseline rather than centred on
                // it, and lifted TWICE now. At scale 2 a half-line nudge was
                // enough; at 4 her chin alone reached past the bottom of the
                // 480px viewport; and the keyboard she works at reaches
                // 0.81 x size below centre, another 16px down. Measured
                // rather than eyeballed: her full extent is ~76px, which at
                // 0.62 x size above the baseline puts the thinking-dots at
                // y=396 and the keyboard's lowest ink at y=471, inside the
                // display with room to spare at every inset the readout
                // uses. She simply floats a little above the time now,
                // which on a black display has no edge to look wrong
                // against.
                cy = baseline - figureSize * 0.62f,
                size = figureSize,
                state = assistantState(),
                phase = animPhase(),
                // Live model-audio amplitude: her mouth moves to the actual
                // speech. Sampled at draw time so it is never a frame stale.
                // The playback head, not the network arrival — see
                // GeminiAudioPlayer.liveLevel().
                level = com.x3hub.app.core.audio.GeminiAudioPlayer.liveLevel(),
                // The AGENT's voice, measured at its playback head, so the
                // keyboard flashes on the syllables the wearer is hearing.
                agentLevel = com.x3hub.app.core.agent.AgentSpeech.speechLevel,
                paint = glyphPaint,
                brightness = readoutBrightness
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

        /** The HUD strip's activity cyan; alpha comes from the slider. */
        const val GLYPH_RGB = 0x007FD9FF
        /** Glyphs draw at 1.75× the text's alpha — the original 35%:20%. */
        const val GLYPH_OVER_TEXT = 1.75f
        const val GLYPH_GAP_DP = 8f
        const val READOUT_TEXT_DP = 12f

        /**
         * The assistant against the readout text. Two: the wearer asked
         * for roughly twice the old glyphs, and at 12dp text that puts her
         * at 24dp — big enough for a face to read as a face on this
         * projector, small enough to stay a status light rather than
         * becoming the display.
         */
        /**
         * The assistant against the readout text. Four: she began at two
         * (twice the old glyphs) and read as a small mark at arm's length
         * on the waveguide, where features an eighth of a line tall merge
         * into a blob. At four she is a face the wearer can actually read
         * expression from, and still only a few lines tall on a display
         * that is otherwise empty.
         */
        const val ASSISTANT_SCALE = 4f

        /** Neon cyan for the readout's halo — the app's sign colour. */
        const val NEON_TEXT_RGB = 0x0000E5FF

        /** Its white-hot filament. */
        const val NEON_TEXT_CORE = 0x00CFF9FF

        /** ~12fps for swelling dots. Gentle, and cheap. */
        const val ANIM_FRAME_MS = 80L

        /**
         * ~30fps while she speaks. The mouth follows live audio, and below
         * about 25fps that reads as chewing rather than talking. Paid only
         * for the seconds she is actually speaking.
         */
        const val ANIM_FRAME_TALK_MS = 33L

        /** One full cycle of the working animation. */
        const val ANIM_PERIOD_MS = 2200L
        /**
         * Clear of the very last rows: the waveguide's edge rows are the
         * first to distort on this projector, and the readout is the only
         * thing on screen — nothing competes for the safer band above.
         */
        const val READOUT_BOTTOM_INSET_DP = 18f
    }
}
