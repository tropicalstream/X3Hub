# X3Hub

> ## ⚠️ Experimental beta — not for consequential use
> This is an **unofficial, community-built, work-in-progress** app, not a
> RayNeo/Mercury product. It is provided for **testing and feedback only**.
>
> - **Do not rely on it for anything consequential** — medication, appointments,
>   safety-critical reminders, or any situation where a missed/incorrect
>   response would cause real harm or loss. Reminders can fail to fire, memory
>   and custom instructions can be mis-applied by the model, voice
>   transcription can mishear you, and a web agent can misread a page.
> - Expect bugs, missing features, and breaking changes between commits.
> - It talks directly to Google's Gemini API and (for browser speech) Groq's
>   API using **your own API keys** — you are responsible for your own usage
>   and any costs on your account.
> - No warranty of any kind. Use at your own risk, and verify anything
>   important through another source.

A **Gemini Live voice HUD with a built-in browser**, for the RayNeo X3 Pro AR
glasses — X3Gemini's voice assistant fused with a floating-window web browser
you can open, resize, move, and hand tasks to by voice. One Activity, one
foreground Service, everything under the HUD strip.

---

## Features

- **Voice assistant** (Gemini Live, `gemini-2.5-flash-native-audio-preview-12-2025`)
  — talk to it anywhere on the display; barge-in (talk over a reply to stop
  it) is a Settings toggle.
- **Floating browser windows** — up to 3 at once, each a real WebView pinned
  to the HUD board. Portrait 3:4 size ladder (128×170 up to 323×430),
  resizable and movable by hand.
- **Ad blocking** — 93,000+ domain blocklist, plus site-specific chrome
  filters (promo banners, "get our app" interstitials) and image-page
  auto-framing for a picture that fills the window.
- **Page agent** — an autonomous in-page agent (click, type, scroll, search)
  that takes a *spoken* task once a window is active: "play the first
  result," "search this page for X," "log in."
- **Read/see any page** — ask what a page says (its text is read aloud and
  answered from) or what it *shows*, when the page is a picture rather than
  text — the assistant looks at it and describes what's actually there.
- **Bookmarks** — "pin this page" saves the active window's URL, title, and a
  thumbnail; manage them (open/delete) from Settings, where the list replaces
  the old key-paste help box.
- **Edge scrolling** — rest the cursor near any window's edge (all four
  sides) to scroll it; speed ramps with how close to the edge you are. The
  cursor is never frozen inside a window.
- **On-screen keyboard** — a glasses-native keyboard replaces the (broken,
  cross-eye) system IME whenever a page field is focused.
- **HUD pin board** — post-it notes, pictures, auto-refreshing live-info
  cards, and countdown reminders, alongside browser windows, sharing one
  10-pin board.
- **Live-update widgets** — auto-refreshing cards for anything that
  *changes*: scores, news, weather, prices — only pinned when explicitly
  asked to.
- **Memory & custom instructions** — durable facts and personality
  instructions that persist across sessions (Live itself is stateless).
- **Reminders** — one-shot, relative, or daily-repeating; fire a system
  notification and a HUD note; survive reboots.
- **Custom commands** — save a named multi-step voice prompt, run it later by
  name ("run my morning report").
- **Web knowledge, your choice** — Settings toggle between **web search**
  (current-events grounding) and **reading links** (the assistant fetches and
  reads any URL you give it). The Live API allows only one at a time.

---

## Voice commands

Click a browser window once to select it (cyan border), then talk anywhere —
the assistant always acts on the window you've selected.

| Say | What happens |
|---|---|
| *"Open Wikipedia."* / *"Look up the tide times."* | Opens a browser window (`url=` a named site, or `query=` to search). |
| *"What does this page say?"* / *"Summarise this."* | Reads the page's text (or, if it's a picture, describes what's in it) and answers. |
| *"Pin this page."* / *"Bookmark this."* | Saves the active window (URL + title + thumbnail) and pins it to the HUD. |
| *"What have I bookmarked?"* / *"Forget the recipe one."* | List / remove a saved bookmark. |
| *"Play the first result."* / *"Search this page for X."* / *"Log in."* | Hands a task to the page agent — clicking, typing, scrolling inside the active window. |
| *"Close that."* / *"Scroll down."* / *"Go back."* / *"Make it bigger."* | Window control: close / scroll / navigate / resize the active window. |
| *"What is this a picture of?"* / *"Identify this."* | The assistant looks at the window (even with no text on the page) and describes it. |

### HUD pins

| Say | What happens |
|---|---|
| *"Make a note that the router password is hunter2."* | Pins a post-it note. |
| *"Pin that picture of the whiteboard."* | Saves the current camera frame as a picture pin. |
| *"Remove the Warriors pin."* / *"What's pinned?"* / *"Clear my pins."* | Manage the pin board. |

### Live-update widgets

- *"Add a live card watching the World Cup scores."*
- *"Pin a live widget for the top AI headline, refresh every 2 minutes."*
- *"Keep me posted on the weather in Oakland on my HUD."*

Only for information that **changes over time**. Default refresh is 5 min;
say *"refresh every N minutes"* to change it (1–180 min).

### Memory (persists across sessions)

| Say | What happens |
|---|---|
| *"Remember that my car is parked on level 3."* | Stored durably; available in every future conversation. |
| *"What do you remember?"* / *"Forget the parking one."* | List / delete memories. |

### Custom instructions (personality)

*"From now on, always answer in one short sentence,"* *"call me Mars,"* *"act
like a formal butler"* — persists across sessions. *"Show your
instructions"* / *"clear your instructions"* to inspect or reset.

### Reminders

| Say | What happens |
|---|---|
| *"Remind me to take out the trash at 8pm."* | One-shot reminder. |
| *"Remind me every morning at 7:30 to run my morning report."* | Daily repeating. |
| *"What are my reminders?"* / *"Cancel the trash one."* | List / cancel. |

### Custom commands (saved prompts)

- *"Save a command called **morning report** that tells me the weather in
  Oakland, my reminders for today, and the top three AI headlines."*
- *"Run my morning report."*

---

## Controls (gestures)

| Gesture | Action |
|---|---|
| Right trackpad slide | Move cursor. Near a selected window's edge, scrolls it instead. |
| **Right arm single tap**, empty space | Start Gemini (or click a pin/window). |
| **Right arm double tap**, on a browser window | **Activate the page agent** — speak a task for the page. |
| **Right arm double tap**, empty space | Toggle Gemini (start / full exit). |
| **Right arm triple tap**, on a window or pin | **Modify mode** — move it (dashed preview shows the destination) or tap the ✕ to delete. |
| **Right arm triple tap**, empty space | Open **Settings**. |
| Left trackpad slide left/right | Resize the selected window (portrait 3:4 ladder). |
| Tap a bookmark/picture pin | Opens it (bookmark → reopens the page; picture → fullscreen viewer). |
| Tap a live card | Refresh it now. |

---

## Install

Needs Android SDK + JDK 17.

```bash
git clone https://github.com/tropicalstream/X3Hub.git
cd X3Hub
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `&&` matters — a failed build must not install a stale APK. A prebuilt
`X3Hub.apk` is also included at the repo root.

### Enabling adb on the X3 Pro
Glasses Settings → General → swipe to the far left → trigger the
"wall-collision" bounce **10×** to toggle ADB on.

### Permissions
First launch prompts for microphone + camera on-device, or pre-grant over
adb:

```bash
adb shell pm grant com.x3hub.app android.permission.RECORD_AUDIO
adb shell pm grant com.x3hub.app android.permission.CAMERA
```

---

## API keys (never stored in this repo)

X3Hub uses three keys, all **pushed to the device over adb** — none are ever
committed here (`*.key`, `*_api_key.txt` are git-ignored).

| Key | Used for | Get one at |
|---|---|---|
| **Gemini** | The voice assistant (Live API), page vision, live-info cards | [aistudio.google.com](https://aistudio.google.com) |
| **Groq** | Browser speech: Whisper transcription + TTS for the page agent | [console.groq.com](https://console.groq.com) |
| **Cerebras** *(optional)* | Alternate LLM for the page agent | [cloud.cerebras.ai](https://cloud.cerebras.ai) |

Launch the app once so its data directory exists, then push a key as a text
file — it's picked up within seconds, no restart needed:

```bash
echo "AIza...your-gemini-key..." > gemini_api_key.txt
adb push gemini_api_key.txt /sdcard/Android/data/com.x3hub.app/files/gemini_api_key.txt

echo "gsk_...your-groq-key..." > groq_api_key.txt
adb push groq_api_key.txt /sdcard/Android/data/com.x3hub.app/files/groq_api_key.txt
```

Or type a key on-device: triple-tap empty space to open **Settings**, tap a
key card, then **"Type it on-screen."**

---

## Architecture (single module, `com.x3hub.app`)

```
MainActivity                     — HUD strip, cursor + gestures, tap
                                    classification, keyboard host
ui/BinocularSbsLayout             — 640×480 logical viewport drawn twice (L+R)
ui/BrowserWindowView              — one floating WebView: activate/modify
                                    state machine, resize ladder, edge
                                    scrolling, ad-block, page-image fit,
                                    thumbnail capture, resume-from-snapshot
ui/HudPinBoardController          — notes/pictures/live cards/bookmarks/
                                    browser windows on the pin board; flow-
                                    grid layout; move/delete via modify mode
ui/HubSettingsOverlay             — API key cards, barge-in + web-knowledge
                                    toggles, bookmarks list
ui/CustomKeyboardView             — glasses-native on-screen keyboard
core/session/…FgService           — foreground service (mic|camera) hosting
                                    the voice pipeline + CameraX
core/session/GeminiVoicePipeline  — mic → Live WS → AudioTrack; silence
                                    watchdog; barge-in; mic-privilege holds
core/network/GeminiLiveClient     — Live WebSocket; 10 declared tools;
                                    googleSearch XOR urlContext
core/live/LiveCardEngine          — refreshes live cards; 429/stale handling
core/agent/PageAgentController    — one page-agent instance per browser
                                    window (click/type/scroll/search)
core/agent/AgentVoice             — Groq Whisper STT for spoken page tasks
core/agent/AgentSpeech            — Gemini TTS for spoken page-agent answers
core/agent/PageCommands           — voice command router (URLs, navigation,
                                    in-page search) ahead of the agent
core/tools/…                      — BrowserTool, BookmarkTool, PageVision,
                                    HudPinTool, CameraTool, ReminderTool, …
core/bridge/…                     — HudPinStore, BookmarkStore, BookmarkBridge,
                                    WindowBridge, HudStateBridge, VoiceServiceApi
core/web/AdBlock                  — 93k-domain blocklist + request interception
core/config/ApiKeyStore           — resolves adb-pushed keys (Gemini/Groq/Cerebras)
```

Platform ground rules (X3 Pro field guide): pure-black canvas (black =
transparent on the waveguide — never call this "3D," it's binocular SBS), no
`ar_mode` meta-data, geometry lives in the layout, the right-arm click can
arrive as a KEY *or* a touch event, and a HUD-overlay app is never the "top"
activity — anything recording audio must hold a microphone-type foreground
service or it silently captures nothing.
