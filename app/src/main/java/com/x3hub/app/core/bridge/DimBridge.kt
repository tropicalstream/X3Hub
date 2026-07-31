package com.x3hub.app.core.bridge

/**
 * Whether the display is dimmed — readable from the voice-tool coroutine,
 * which has no activity and no view tree.
 *
 * Dim changes what "open a page" MEANS: the wearer cannot see windows, so a
 * second one is pure cost — memory, a renderer process, and a surprise
 * waiting on undim. While dimmed every open therefore targets the one
 * (invisible) window, exactly as if the wearer had said "in this window".
 * That rule lives in BrowserTool, which can only know to apply it through
 * this flag; DimController itself is a View concern the tool must not touch.
 *
 * Written only by the activity's onDimChanged, cleared when the activity
 * goes away — a dim state cannot outlive the surface that was dimmed.
 */
object DimBridge {
    @Volatile var dimmed: Boolean = false
}
