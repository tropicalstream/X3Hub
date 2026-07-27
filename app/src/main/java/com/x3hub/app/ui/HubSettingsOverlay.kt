package com.x3hub.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.x3hub.app.core.config.ApiKeyStore
import java.io.File

/**
 * x3hub settings — the page opened by a triple-tap outside a browser
 * window. One page for both halves of the app: every API key x3hub can
 * use, in one place.
 *
 * The whole design follows from one fact: there is no keyboard on the
 * glasses, and an API key is 39–60 characters of case-sensitive noise.
 * So the page is ordered by what a visit is actually for:
 *
 *   1. Is a key present? (masked — first 6 characters, then dots). Most
 *      visits are to check, not to change, so this is the front page.
 *   2. How to paste one from a computer — the exact adb command, with
 *      this build's real external-files path filled in.
 *   3. Only then, on-screen entry: a character grid, because taps arrive
 *      as synthetic clicks at the cursor and a clickable view is the only
 *      input path that exists. An EditText would be dead here — nothing
 *      raises an IME on this device.
 *
 * Geometry is written in raw logical px. MainActivity.attachBaseContext
 * pins the configuration to DENSITY_MEDIUM, so 1dp == 1px and the layout
 * is expressed directly in the 640×480 viewport units — the same
 * convention as HudPinBoardController.UNDER_HUD_ZONE.
 *
 * The page covers the entire viewport on purpose. Black is transparent on
 * the waveguide, so the panel is a dark wash plus bright strokes rather
 * than a solid card; covering everything also means a mis-aimed tap can't
 * reach the voice orb behind it and start a Gemini session mid-edit.
 *
 * Everything here runs on the main thread. The key files are ~40 bytes
 * each and are read on show() and written on a save tap; that is far
 * cheaper than a thread hop that would then race the re-render.
 */
class HubSettingsOverlay(
    private val activity: Activity,
    private val overlayRoot: FrameLayout,
    private val uiHandler: Handler,
    private val forceCursorVisible: () -> Unit,
    private val showToast: (String) -> Unit,
    private val onKeyChanged: (slotId: String, newValue: String?) -> Unit,
    private val slots: List<KeySlot> = defaultSlots(activity)
) {

    /**
     * One configurable key. [read] and [write] are the whole storage
     * contract: the defaults below persist to the same SharedPreferences
     * file ApiKeyStore uses plus a pushable file, but a slot can be
     * repointed at another store (SmartView's GroqSpeech, once it is
     * ported) without touching this page.
     *
     * [pushFileName] and [broadcastAction] are the adb routes ADVERTISED
     * to the wearer — leave one null when that route genuinely does not
     * work for the key, since a printed command that silently does
     * nothing is worse than no command at all.
     */
    class KeySlot(
        val id: String,
        val title: String,
        val purpose: String,
        val hint: String,
        val pushFileName: String?,
        val broadcastAction: String?,
        val read: () -> StoredKey?,
        val write: (String?) -> Unit
    )

    /**
     * A key as currently resolved. [fromKeyFile] matters to the wearer: the
     * key FILE wins over the stored value, so when it is set that is the
     * string that will actually be sent to the provider.
     */
    data class StoredKey(val value: String, val fromKeyFile: Boolean)

    // ── View tree (built once, then shown/hidden) ─────────────────────

    private var root: FrameLayout? = null
    private var mainPage: View? = null
    private var detailPage: View? = null
    private var keypadPage: View? = null
    private var statusLine: TextView? = null

    private val cardStatusViews = LinkedHashMap<String, TextView>()
    private var detailTitle: TextView? = null
    private var detailStatus: TextView? = null
    private var detailCommands: TextView? = null
    private var detailClearButton: TextView? = null
    private var keypadTitle: TextView? = null
    private var keypadBuffer: TextView? = null
    private val letterKeys = ArrayList<TextView>(26)

    // ── Page state ────────────────────────────────────────────────────

    private var activeSlot: KeySlot? = null
    private val buffer = StringBuilder(MAX_KEY_CHARS)
    private var upperCase = false
    private var clearArmed = false

    private val disarmClear = Runnable {
        clearArmed = false
        detailClearButton?.text = "Clear key"
        detailClearButton?.setTextColor(Color.WHITE)
    }
    private val hideStatusLine = Runnable {
        statusLine?.visibility = View.GONE
    }

    /** Real path of this build's external files dir, for the adb command. */
    private val filesDirPath: String =
        runCatching { activity.getExternalFilesDir(null)?.absolutePath }.getOrNull()
            ?: "/sdcard/Android/data/${activity.packageName}/files"

    val isShowing: Boolean
        get() = root?.visibility == View.VISIBLE

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    fun show() {
        val r = ensureBuilt()
        if (r.parent == null) {
            r.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply {
                setMargins(4, 4, 4, 4)
            }
            overlayRoot.addView(r)
        }
        refreshCards()
        showPage(mainPage)
        r.visibility = View.VISIBLE
        // The overlay's hit-test walks children in reverse order, and pins
        // (or the fullscreen picture viewer) may have been added after this
        // page was first attached.
        r.bringToFront()
        forceCursorVisible()
    }

    fun hide() {
        val r = root ?: return
        uiHandler.removeCallbacks(disarmClear)
        uiHandler.removeCallbacks(hideStatusLine)
        disarmClear.run()
        // Never leave a half-typed key sitting in memory behind a hidden view.
        buffer.setLength(0)
        activeSlot = null
        statusLine?.visibility = View.GONE
        r.visibility = View.GONE
    }

    /** Convenience for the triple-tap handler: open, or close if already open. */
    fun toggle() {
        if (isShowing) hide() else show()
    }

    /**
     * BACK / a "close" gesture: steps one page back (keypad → key detail →
     * front page → closed). Returns false when the page isn't showing so
     * the caller can fall through to its own back handling.
     */
    fun handleBack(): Boolean {
        if (!isShowing) return false
        val slot = activeSlot
        when {
            keypadPage?.visibility == View.VISIBLE && slot != null -> {
                buffer.setLength(0)
                openDetail(slot)
            }
            detailPage?.visibility == View.VISIBLE -> backToMain()
            else -> hide()
        }
        return true
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private fun ensureBuilt(): FrameLayout {
        root?.let { return it }
        val r = FrameLayout(activity)
        r.background = boxBg(fill = 0xE6081016.toInt(), stroke = ACCENT, strokeW = 2)
        // Above pins (6–12) and the fullscreen picture viewer (30).
        r.elevation = 40f
        // Clickable with no listener: that is what makes MainActivity's
        // hit-test treat a tap on empty panel space as consumed instead of
        // falling through to "empty space while idle → activate Gemini".
        r.isClickable = true
        r.isFocusable = true

        mainPage = buildMainPage().also { addPage(r, it) }
        detailPage = buildDetailPage().also { addPage(r, it) }
        keypadPage = buildKeypadPage().also { addPage(r, it) }

        // Feedback line. The HUD notice strip that showToast writes to is
        // UNDERNEATH this page, so anything the wearer must read is echoed
        // here. Deliberately backgroundless (a shadow carries it instead):
        // a view with a background counts as an inert surface in the
        // overlay hit-test and would swallow taps on the buttons below it.
        val status = label("", 15f, OK, bold = true)
        status.gravity = Gravity.CENTER
        status.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        status.visibility = View.GONE
        status.layoutParams = FrameLayout.LayoutParams(
            MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = 56 }
        r.addView(status)
        statusLine = status

        root = r
        return r
    }

    private fun addPage(parent: FrameLayout, page: View) {
        page.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        page.visibility = View.GONE
        parent.addView(page)
    }

    private fun showPage(page: View?) {
        uiHandler.removeCallbacks(disarmClear)
        disarmClear.run()
        mainPage?.visibility = if (page === mainPage) View.VISIBLE else View.GONE
        detailPage?.visibility = if (page === detailPage) View.VISIBLE else View.GONE
        keypadPage?.visibility = if (page === keypadPage) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Front page: one card per key
    // ------------------------------------------------------------------

    private fun buildMainPage(): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(PAD, PAD, PAD, PAD)
        col.addView(header(label("x3hub settings", 18f, ACCENT, bold = true), onBack = null))

        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(MATCH, CARD_H).apply { topMargin = 6 }
        slots.forEach { row.addView(buildCard(it)) }
        col.addView(row)

        col.addView(pasteHelpBox())
        return col
    }

    /**
     * The whole card is the tap target (about 200×138), not a button
     * inside it: the trackpad cursor lands within a few px of where the
     * wearer meant, and a mis-tap onto the wrong key's field is worse than
     * an oversized target.
     */
    private fun buildCard(slot: KeySlot): View {
        val card = LinearLayout(activity)
        card.orientation = LinearLayout.VERTICAL
        card.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
            leftMargin = 4
            rightMargin = 4
        }
        card.setPadding(8, 6, 8, 6)
        card.background = boxBg(fill = 0x14FFFFFF, stroke = 0x66FFFFFF, strokeW = 1)
        card.isClickable = true
        card.setOnClickListener { openDetail(slot) }

        card.addView(label(slot.title, 16f, ACCENT, bold = true))
        card.addView(label(slot.purpose, 14f, DIM).apply { maxLines = 2 })

        val status = label("", 14f, Color.WHITE, mono = true)
        status.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 4 }
        cardStatusViews[slot.id] = status
        card.addView(status)

        val spacer = View(activity)
        spacer.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        card.addView(spacer)

        card.addView(label("tap to change  ›", 14f, ACCENT))
        return card
    }

    private fun refreshCards() {
        slots.forEach { slot ->
            val tv = cardStatusViews[slot.id] ?: return@forEach
            val stored = runCatching { slot.read() }.getOrNull()
            if (stored == null) {
                tv.text = "not set"
                tv.setTextColor(WARN)
            } else {
                tv.text = mask(stored.value)
                tv.setTextColor(OK)
            }
        }
    }

    private fun pasteHelpBox(): View {
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 6 }
        box.setPadding(10, 8, 10, 8)
        box.background = boxBg(fill = 0x14FFFFFF, stroke = 0x66FFFFFF, strokeW = 1)

        box.addView(label("Paste a key from a computer", 16f, ACCENT, bold = true))
        box.addView(
            label(
                "Keys are long and case-sensitive, so adb is the reliable route. " +
                    "Put the key in a text file and push it — it is picked up on the " +
                    "next call, no restart:",
                14f, DIM
            ).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 4 }
            }
        )
        box.addView(
            label(
                "adb push gemini_api_key.txt \\\n  $filesDirPath/gemini_api_key.txt",
                14f, Color.WHITE, mono = true
            ).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6 }
            }
        )
        box.addView(
            label(
                "Tap a key above for that key's exact commands, or to type it here.",
                14f, DIM
            ).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6 }
            }
        )
        return box
    }

    // ------------------------------------------------------------------
    // Key detail page
    // ------------------------------------------------------------------

    private fun buildDetailPage(): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(PAD, PAD, PAD, PAD)

        val title = label("", 18f, ACCENT, bold = true)
        detailTitle = title
        col.addView(header(title, onBack = { backToMain() }))

        val status = label("", 15f, Color.WHITE, mono = true)
        status.setPadding(10, 8, 10, 8)
        status.background = boxBg(fill = 0x14FFFFFF, stroke = 0x66FFFFFF, strokeW = 1)
        status.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6 }
        detailStatus = status
        col.addView(status)

        col.addView(
            label("From a computer — the route that works first time:", 14f, DIM).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8 }
            }
        )

        val cmds = label("", 14f, Color.WHITE, mono = true)
        cmds.setPadding(10, 8, 10, 8)
        cmds.background = boxBg(fill = 0x14FFFFFF, stroke = 0x66FFFFFF, strokeW = 1)
        cmds.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 4 }
        detailCommands = cmds
        col.addView(cmds)

        val spacer = View(activity)
        spacer.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        col.addView(spacer)

        val actions = LinearLayout(activity)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.layoutParams = LinearLayout.LayoutParams(MATCH, 48).apply { topMargin = 6 }
        // 2:1 — the destructive action gets the SMALLER target of the two.
        val typeBtn = button("Type it on-screen", 0) { openKeypad() }
        typeBtn.layoutParams = LinearLayout.LayoutParams(0, MATCH, 2f).apply { rightMargin = 4 }
        actions.addView(typeBtn)
        val clearBtn = button("Clear key", 0) { onClearTapped() }
        clearBtn.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 4 }
        detailClearButton = clearBtn
        actions.addView(clearBtn)
        col.addView(actions)
        return col
    }

    private fun openDetail(slot: KeySlot) {
        activeSlot = slot
        detailTitle?.text = "${slot.title} key"
        val stored = runCatching { slot.read() }.getOrNull()
        detailStatus?.let { tv ->
            if (stored == null) {
                tv.text = "Not set — x3hub cannot use ${slot.title} yet."
                tv.setTextColor(WARN)
            } else {
                // Never says "pushed": saving on-screen writes the same file,
                // so the honest distinction is which store answers, not who
                // put it there.
                val source = if (stored.fromKeyFile) {
                    "from ${slot.pushFileName} — that file wins"
                } else {
                    "saved on this device"
                }
                tv.text = "In use:  ${mask(stored.value)}\n$source"
                tv.setTextColor(OK)
            }
        }
        detailCommands?.text = commandsFor(slot)
        // INVISIBLE, not GONE: keeping the row's weights fixed means the
        // "Type it on-screen" button never moves under the cursor between
        // one key and the next.
        detailClearButton?.visibility = if (stored == null) View.INVISIBLE else View.VISIBLE
        showPage(detailPage)
        forceCursorVisible()
    }

    private fun backToMain() {
        activeSlot = null
        refreshCards()
        showPage(mainPage)
        forceCursorVisible()
    }

    /** Only routes that actually work for this slot get printed. */
    private fun commandsFor(slot: KeySlot): String {
        val steps = ArrayList<String>(2)
        slot.pushFileName?.let {
            steps += "put the key in $it, then\n" +
                "   adb push $it \\\n" +
                "     $filesDirPath/$it"
        }
        slot.broadcastAction?.let {
            steps += "send it straight in\n" +
                "   adb shell am broadcast \\\n" +
                "     -a $it --es key \"${slot.hint}\""
        }
        if (steps.isEmpty()) return "No adb route for this key — enter it below."
        return steps.mapIndexed { i, s -> "${i + 1}  $s" }.joinToString("\n\n")
    }

    /**
     * Two-step clear: the first tap arms, the second within
     * [CLEAR_CONFIRM_MS] commits. A single stray cursor tap must not be
     * able to wipe a key that took a computer to install.
     */
    private fun onClearTapped() {
        val slot = activeSlot ?: return
        if (!clearArmed) {
            clearArmed = true
            detailClearButton?.text = "Confirm clear?"
            detailClearButton?.setTextColor(WARN)
            uiHandler.removeCallbacks(disarmClear)
            uiHandler.postDelayed(disarmClear, CLEAR_CONFIRM_MS)
            return
        }
        uiHandler.removeCallbacks(disarmClear)
        disarmClear.run()
        runCatching { slot.write(null) }.onFailure {
            note("Could not clear: ${it.message}")
            return
        }
        onKeyChanged(slot.id, null)
        note("${slot.title} key cleared")
        refreshCards()
        openDetail(slot)
    }

    // ------------------------------------------------------------------
    // On-screen entry
    // ------------------------------------------------------------------

    private fun buildKeypadPage(): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(PAD, PAD, PAD, PAD)

        val title = label("", 18f, ACCENT, bold = true)
        keypadTitle = title
        col.addView(
            header(
                title,
                onBack = {
                    buffer.setLength(0)
                    activeSlot?.let { slot -> openDetail(slot) } ?: backToMain()
                }
            )
        )

        val buf = label("", 15f, Color.WHITE, mono = true)
        buf.setPadding(10, 8, 10, 8)
        buf.minHeight = TAP_MIN
        buf.background = boxBg(fill = 0x14FFFFFF, stroke = ACCENT, strokeW = 2)
        buf.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6 }
        keypadBuffer = buf
        col.addView(buf)

        col.addView(keyRow("abcdefghij"))
        col.addView(keyRow("klmnopqrst"))
        col.addView(keyRow("uvwxyz-_.="))
        col.addView(keyRow("0123456789"))

        val actions = LinearLayout(activity)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.layoutParams = LinearLayout.LayoutParams(MATCH, 46).apply { topMargin = 6 }
        actions.addView(actionKey("aA") { toggleCase() })
        actions.addView(actionKey("⌫") { backspace() })
        actions.addView(actionKey("Paste") { pasteFromClipboard() })
        actions.addView(actionKey("Cancel") {
            buffer.setLength(0)
            activeSlot?.let { openDetail(it) } ?: backToMain()
        })
        actions.addView(actionKey("Save", accent = true) { saveTyped() })
        col.addView(actions)
        return col
    }

    private fun keyRow(chars: String): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(MATCH, TAP_MIN).apply { topMargin = 4 }
        for (c in chars) {
            val k = button(c.toString(), 0) { appendChar(cased(c)) }
            k.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                leftMargin = 2
                rightMargin = 2
            }
            k.textSize = 17f
            if (c.isLetter()) {
                // The tag holds the LOWERCASE character; the label follows the
                // case toggle, and the tap re-derives the case at tap time.
                k.tag = c
                letterKeys.add(k)
            }
            row.addView(k)
        }
        return row
    }

    private fun actionKey(text: String, accent: Boolean = false, onTap: () -> Unit): TextView {
        val b = button(text, 0, onTap)
        b.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
            leftMargin = 2
            rightMargin = 2
        }
        if (accent) {
            b.background = boxBg(fill = 0x2600FF88, stroke = OK, strokeW = 2)
            b.setTextColor(OK)
        }
        return b
    }

    private fun openKeypad() {
        val slot = activeSlot ?: return
        buffer.setLength(0)
        // Start empty rather than pre-filled with the current key: editing 39
        // characters one cursor tap at a time is worse than retyping, and the
        // masked value on the previous page already answers "what is set?".
        upperCase = false
        applyCaseLabels()
        keypadTitle?.text = "Type the ${slot.title} key"
        renderBuffer()
        showPage(keypadPage)
        forceCursorVisible()
    }

    private fun cased(c: Char): Char = if (upperCase) c.uppercaseChar() else c

    private fun toggleCase() {
        upperCase = !upperCase
        applyCaseLabels()
    }

    private fun applyCaseLabels() {
        for (tv in letterKeys) {
            val c = tv.tag as? Char ?: continue
            tv.text = cased(c).toString()
        }
    }

    private fun appendChar(c: Char) {
        if (buffer.length >= MAX_KEY_CHARS) {
            note("That is already $MAX_KEY_CHARS characters")
            return
        }
        buffer.append(c)
        renderBuffer()
    }

    private fun backspace() {
        if (buffer.isEmpty()) return
        buffer.setLength(buffer.length - 1)
        renderBuffer()
    }

    private fun renderBuffer() {
        val tv = keypadBuffer ?: return
        if (buffer.isEmpty()) {
            tv.text = "example:  ${activeSlot?.hint.orEmpty()}"
            tv.setTextColor(DIM)
        } else {
            // toString(), not the builder itself — a TextView holds the
            // CharSequence it was given, and handing it a mutable one invites
            // a stale render the first time someone forgets this call.
            tv.text = buffer.toString()
            tv.setTextColor(Color.WHITE)
        }
    }

    /**
     * Clipboard paste. Android 10+ only lets the FOCUSED app read the
     * clipboard, which this page is; it is the fastest path whenever the
     * key was already opened in the browser window next door.
     */
    private fun pasteFromClipboard() {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            runCatching { clip.getItemAt(0)?.coerceToText(activity)?.toString() }.getOrNull()
        } else {
            null
        }
        val clean = text?.trim().orEmpty()
        if (clean.isBlank()) {
            note("Clipboard is empty")
            return
        }
        buffer.setLength(0)
        buffer.append(clean.take(MAX_KEY_CHARS))
        renderBuffer()
        note("Pasted ${buffer.length} characters")
    }

    private fun saveTyped() {
        val slot = activeSlot ?: return
        val value = buffer.toString().trim()
        if (value.length < MIN_KEY_CHARS) {
            // Every provider's key is far longer than this; a short string is
            // a slipped tap, not a key, and saving it would break the client.
            note("Too short for an API key")
            return
        }
        runCatching { slot.write(value) }.onFailure {
            note("Could not save: ${it.message}")
            return
        }
        buffer.setLength(0)
        onKeyChanged(slot.id, value)
        note("${slot.title} key saved")
        refreshCards()
        openDetail(slot)
    }

    // ------------------------------------------------------------------
    // Small shared pieces
    // ------------------------------------------------------------------

    /** Header row: optional back button, title, and an always-present ✕. */
    private fun header(title: TextView, onBack: (() -> Unit)?): LinearLayout {
        val h = LinearLayout(activity)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.layoutParams = LinearLayout.LayoutParams(MATCH, TAP_MIN)
        val back = onBack
        if (back != null) h.addView(button("‹  Back", 96) { back() })
        title.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            leftMargin = 8
            marginStart = 8
        }
        title.maxLines = 1
        h.addView(title)
        h.addView(button("✕", TAP_MIN) { hide() })
        return h
    }

    private fun label(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView {
        val tv = TextView(activity)
        tv.text = text
        tv.textSize = sizeSp
        tv.setTextColor(color)
        tv.typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        tv.setLineSpacing(0f, 1.1f)
        // Labels must stay inert: a clickable child would steal the tap from
        // the card or button that owns it.
        tv.isClickable = false
        tv.isFocusable = false
        return tv
    }

    private fun button(text: String, widthPx: Int, onTap: () -> Unit): TextView {
        val b = TextView(activity)
        b.layoutParams = LinearLayout.LayoutParams(widthPx, TAP_MIN)
        b.minHeight = TAP_MIN
        b.gravity = Gravity.CENTER
        b.text = text
        b.textSize = 15f
        b.setTextColor(Color.WHITE)
        b.typeface = Typeface.DEFAULT_BOLD
        b.background = boxBg(fill = 0x1AFFFFFF, stroke = ACCENT, strokeW = 2)
        b.isClickable = true
        b.setOnClickListener { onTap() }
        return b
    }

    private fun boxBg(fill: Int, stroke: Int, strokeW: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(strokeW, stroke)
            cornerRadius = 4f
        }

    /**
     * First 6 characters, dots, then the length. The length is what makes a
     * paste verifiable at a glance (a truncated push is the common failure)
     * and it leaks nothing a masked key doesn't already.
     */
    private fun mask(value: String): String =
        value.take(6) + "••••••" + "  ${value.length} chars"

    /** Feedback the wearer must read — inline here, and on the HUD strip. */
    private fun note(message: String) {
        statusLine?.let { tv ->
            tv.text = message
            tv.visibility = View.VISIBLE
            uiHandler.removeCallbacks(hideStatusLine)
            uiHandler.postDelayed(hideStatusLine, STATUS_LINE_MS)
        }
        showToast(message)
    }

    companion object {
        const val SLOT_GEMINI = "gemini"
        const val SLOT_GROQ = "groq"
        const val SLOT_CEREBRAS = "cerebras"

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val PAD = 8
        /** Minimum touch target; the cursor is a trackpad estimate, not a finger. */
        private const val TAP_MIN = 44
        private const val CARD_H = 138

        private const val ACCENT = 0xFF7FDBFF.toInt()   // HUD cyan
        private const val OK = 0xFF9FE6B0.toInt()
        private const val WARN = 0xFFFFB347.toInt()
        private const val DIM = 0xCCFFFFFF.toInt()

        private const val MAX_KEY_CHARS = 200
        private const val MIN_KEY_CHARS = 12
        private const val CLEAR_CONFIRM_MS = 4_000L
        private const val STATUS_LINE_MS = 3_000L

        /**
         * ApiKeyStore's SharedPreferences file and pref key, duplicated here
         * because it keeps them private and exposes no clear(). If either name
         * changes there, change it here too — a mismatch is silent.
         */
        private const val PREFS_FILE = "x3hub_config"
        private const val GEMINI_PREF = "gemini_api_key"
        private const val GEMINI_FILE = "gemini_api_key.txt"

        /**
         * The keys this build can use. Groq and Cerebras use the SAME pref
         * names SmartView's GroqSpeech does ("groq_api_key",
         * "cerebras_api_key") so a port can be pointed at this store without
         * a migration — but if that port keeps its own prefs file, pass a
         * slot list with read/write repointed at it rather than editing here.
         */
        @JvmStatic
        fun defaultSlots(context: Context): List<KeySlot> {
            val app = context.applicationContext
            return listOf(
                KeySlot(
                    id = SLOT_GEMINI,
                    title = "Gemini",
                    purpose = "Voice assistant (Live API)",
                    hint = "AIza…",
                    pushFileName = GEMINI_FILE,
                    broadcastAction = "com.x3hub.app.SET_API_KEY",
                    // ApiKeyStore owns the resolution order; asking it rather
                    // than re-reading both sources here keeps ONE definition of
                    // "the key actually in use".
                    read = {
                        ApiKeyStore.resolve(app)?.let {
                            StoredKey(it, fromKeyFile = keyFileExists(app, GEMINI_FILE))
                        }
                    },
                    write = { value ->
                        writeKey(app, GEMINI_FILE, GEMINI_PREF, value)
                        ApiKeyStore.invalidateCache()
                    }
                ),
                KeySlot(
                    id = SLOT_GROQ,
                    title = "Groq",
                    purpose = "Browser speech: Whisper + TTS",
                    hint = "gsk_…",
                    pushFileName = "groq_api_key.txt",
                    // No SET_GROQ_KEY receiver exists in the manifest, so no
                    // broadcast command is advertised for this one.
                    broadcastAction = null,
                    read = { readKey(app, "groq_api_key.txt", "groq_api_key") },
                    write = { value -> writeKey(app, "groq_api_key.txt", "groq_api_key", value) }
                ),
                KeySlot(
                    id = SLOT_CEREBRAS,
                    title = "Cerebras",
                    purpose = "Optional page-agent LLM",
                    hint = "csk-…",
                    pushFileName = "cerebras_api_key.txt",
                    broadcastAction = null,
                    read = { readKey(app, "cerebras_api_key.txt", "cerebras_api_key") },
                    write = { value ->
                        writeKey(app, "cerebras_api_key.txt", "cerebras_api_key", value)
                    }
                )
            )
        }

        private fun keyFile(context: Context, fileName: String): File? =
            context.getExternalFilesDir(null)?.let { File(it, fileName) }

        private fun keyFileExists(context: Context, fileName: String): Boolean =
            runCatching { keyFile(context, fileName)?.exists() == true }.getOrDefault(false)

        /** Pushed file first, then the stored value — ApiKeyStore's order. */
        private fun readKey(context: Context, fileName: String, prefKey: String): StoredKey? {
            val fromFile = runCatching {
                keyFile(context, fileName)
                    ?.takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (fromFile != null) return StoredKey(fromFile, fromKeyFile = true)
            val fromPref = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getString(prefKey, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            return fromPref?.let { StoredKey(it, fromKeyFile = false) }
        }

        /**
         * Writes BOTH the pushable file and the pref (and removes both on a
         * clear). The file wins on resolve, so a typed key that only reached
         * the pref would be silently ignored on any unit where a key file had
         * ever been pushed — the two routes have to be kept in agreement.
         */
        private fun writeKey(
            context: Context,
            fileName: String,
            prefKey: String,
            value: String?
        ) {
            runCatching {
                val f = keyFile(context, fileName)
                if (value == null) {
                    if (f?.exists() == true) f.delete()
                } else {
                    f?.writeText(value)
                }
            }
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().apply {
                if (value == null) remove(prefKey) else putString(prefKey, value)
            }.apply()
        }
    }
}
