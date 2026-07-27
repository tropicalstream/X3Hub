# x3hub round 1 — on-glasses test notes

Tested 2026-07-27 on the X3 Pro over adb, debug build. Video of the run:
`x3hub-round1-issues.mp4` (left eye of the SBS pair, captioned).

Driving the temple pad from a host needs two debug-only hooks, both
`BuildConfig.DEBUG`-gated:

```bash
# open a window without speaking to the assistant
adb shell am broadcast -a com.x3hub.app.DEBUG_OPEN_BROWSER \
    -e url https://en.m.wikipedia.org/wiki/Cuttlefish -p com.x3hub.app

# park the cursor on the first window (or at "x,y")
adb shell am broadcast -a com.x3hub.app.DEBUG_OPEN_BROWSER \
    -e cursor window -p com.x3hub.app
```

`dispatchTouchEvent` also accepts the `Virtual` device name in debug builds,
so `adb shell input tap/swipe` drives the cursor exactly like the real pad.

## What the spec asked for, and what it does

All confirmed on the glasses:

| Spec'd control | Result |
| --- | --- |
| Assistant opens a browser window at ~1/8 screen, comfortable page ratio | Works — 170×226 logical px, 3:4 portrait, mobile Wikipedia legible |
| Cursor crossing a window does not activate it | Works — stays INERT, grey border |
| Single click activates | Works — cyan border, page then takes clicks |
| Double click gives X3Gemini window control (delete, move) | Works — amber border + delete chip |
| Resize by swiping forward/back, ratio retained | Works after fix — 128×170 / 170×226 / 238×317 / 323×430 |
| Triple tap in a window exits it and returns the cursor | Works |
| Triple tap in x3hub opens settings | Works — Gemini / Groq / Cerebras key cards |
| Dim works in x3hub (pull right / pull left), not in SmartView windows | Works — and the pull is correctly suppressed while a window is active |

## Fixed during this round

1. **Hard crash on any pin re-render.** The WebView cache re-added a view that
   still had a parent, so `refreshZone()` threw `IllegalStateException` and
   killed the process. The camera-state observer calls `refreshZone()`, so this
   fired in ordinary use, not just under test.
2. **Pages could not be scrolled at all.** Only a synthetic click was ever
   forwarded; a slide moved the cursor and nothing else. Every page ended at
   the fold. Trackpad deltas inside an ACTIVE window are now forwarded 1:1 as a
   touch drag, so flings and overscroll come from the WebView itself.
3. **The window could never grow.** `largestStepThatFits` measured against its
   immediate parent, which the pin board sizes to exactly the window — so no
   larger step ever "fit". It now measures against the board's free zone.
4. **Resizing did not re-flow the page.** The engine adopts an initial scale
   only at navigation time, and `reload()` restores the old zoom, so a grown
   window kept its old scale and lines ran off the right edge. It now does a
   fresh `loadUrl` and carries the scroll offset across in CSS px.
5. **The second resize swipe blacked out the display.** `render()` dropped
   modify mode, so the identical follow-up swipe was no longer a resize and
   fell through to the dim pull. Modify mode now survives a re-render.

## Still to address

| # | Issue | Notes |
| --- | --- | --- |
| 1 | Settings panel is translucent | The HUD and the page behind read through the text. On a waveguide that costs real legibility — it wants an opaque backing. |
| 2 | Every single tap is delayed 340 ms | `DOUBLE_TAP_WINDOW_MS + 20`. It has to wait out a possible third tap. On a page, every link click feels laggy. |
| 3 | Tapping empty space while in MODIFY starts a voice session | It should cancel modify. Right now the only way out is to tap the pin itself. |
| 4 | At the top ladder step the window overlaps the HUD strip | 323×430 fills essentially the whole display and rides up over the clock. Fine as a deliberate reading mode, but it should stop below the strip. |
| 5 | In-page navigation is lost on restart | The pin stores the URL it was opened with, so a restart returns to that page, not the one being read. |
| 6 | Resize costs a page reload each step | Unavoidable given how the engine adopts scale, but a 2-step resize currently reloads twice. Debouncing the reload until the wearer stops stepping would make it one. |
| 7 | The voice path is unverified | No Gemini key on this build, so "open a browser" was only exercised through the debug broadcast. The tool, the pin and the window are proven; the spoken trigger is not. |
| 8 | Multiple windows unverified | `BrowserTool` caps at 3. Only one was ever on screen. |
| 9 | Nothing marks an INERT window as clickable | The wearer has to know that one click wakes it. |

## More intuitive controls, for the next round

Ordered by how much they'd help.

1. **Move dim to the left temple pad.** Dim and resize are both "swipe
   sideways", separated today only by which mode you are in — and issue 5 above
   was exactly that separation failing. The left pad carries only volume and
   the camera double-tap. Putting dim on the other arm makes the collision
   structurally impossible and is easy to remember: *this arm changes the
   display, that one drives the cursor.*

2. **Let resize out of MODIFY.** Resizing is the thing a wearer does
   constantly — text is too small, make it bigger — while delete and move are
   rare. Burying the common action behind a double-tap and grouping it with two
   destructive ones is backwards. A horizontal flick while a window is ACTIVE
   would do it in one gesture; pages scroll vertically far more than
   horizontally, so the conflict is mild and could be limited to flicks fast
   enough to be unambiguous.

3. **Fire single clicks immediately inside an ACTIVE window.** The 340 ms
   deferral exists so a double-tap can be distinguished from a single. But
   inside a page the cost of guessing wrong is a link click, which is cheap and
   reversible, whereas the lag is paid on every single tap. Firing at once
   inside a window and keeping the deferral on the hub would make browsing feel
   immediate.

4. **Give INERT windows a hover state.** Brightening the border when the cursor
   is over a window makes "click to use me" visible instead of memorised, and
   it costs one drawable swap.

5. **Pin browser windows to a fixed slot.** The pin board re-flows around
   assistant cards, so a window can jump across the display when the assistant
   answers. Windows should hold their place and let cards route around them.

6. **Put a gear in the HUD strip.** Triple-tap-for-settings is a good shortcut
   but nothing on screen suggests it exists — and it is the one screen a new
   wearer must find, because the app does nothing without keys.

7. **Say what a window is showing.** At 170 px there is no room for a title
   bar, but the HUD strip has space: showing the active window's host would
   tell the wearer which page has the input when more than one is open.
