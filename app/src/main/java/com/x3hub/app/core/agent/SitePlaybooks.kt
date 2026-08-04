package com.x3hub.app.core.agent

import android.net.Uri
import java.util.Locale

/**
 * What the page agent needs to KNOW about a site, as opposed to what it
 * can see on it.
 *
 * The agent reads the page fresh every run, which is exactly right for
 * pages that explain themselves and exactly wrong for app-like sites
 * whose conventions live in the user's memory instead of the DOM. Discord
 * is the type specimen: the server list is a strip of unlabeled icons, the
 * one search box on screen searches the wrong thing, and sending a message
 * requires knowing where the send control is. An agent without the
 * conventions "goes to the rayneo server" by searching a friends list —
 * observed on the glasses, not hypothetical.
 *
 * Notes ride WITH the task at dispatch time rather than living in the
 * system prompt: the agent hops across sites mid-task, and the note that
 * helps on discord.com is noise on the page it lands on next. Dispatch
 * recomputes per page, so each run carries only the notes for the ground
 * it stands on.
 */
object SitePlaybooks {

    /** The notes for [url]'s host, or null when the site needs none. */
    fun notesFor(url: String?): String? {
        val host = runCatching { Uri.parse(url ?: return null).host }
            .getOrNull().orEmpty().lowercase(Locale.US)
        return when {
            host == "discord.com" || host.endsWith(".discord.com") -> DISCORD
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be" -> YOUTUBE
            else -> null
        }
    }

    private val YOUTUBE = """
        Site notes for YouTube:
        - A watch or Shorts page AUTOPLAYS once it loads. NEVER click or tap the video itself to 'make sure' it plays — the video surface is a play/pause TOGGLE, and tapping a playing video pauses it. Watched live: the 'ensure it plays' tap was the only reason a video stopped.
        - If a video is genuinely not playing, the only safe control is an explicit play BUTTON (label 'Play'); when none is shown, the video is already playing and the task is done.
        - Prefer opening a normal watch page over a Shorts page when both match the request.

        ASKING ABOUT THE VIDEO (summaries and questions), verified on this device:
        YouTube has its own AI that has actually watched the video. Use it for ANY request about what a video says, means, covers or recommends — 'summarize this', 'what's this about', 'what did they say about X', 'is it worth watching', 'what caused it'. It is grounded in the video, so prefer it over reading the description or guessing from the title.
        A task beginning 'ask YouTube ...' or 'ask the video ...' means exactly this feature, every time. Those first words are ROUTING, not content: 'ask YouTube how many acres burned' means open the panel and ask 'how many acres burned'. Never type the routing words into the box.
        The flow, by LABEL — never by position:
        1. The entry is a button labeled 'Ask questions' (sometimes shown as 'Ask'), under the video with the title and description. Clicking the video's own description/'more' area first also reveals it. Click it ONCE — it is a TOGGLE, and the panel takes a moment to render. Measured live: an agent that clicked, looked too early, and clicked again spent four clicks opening and closing the panel and finished with it shut. After the single click, WAIT and re-observe until a textarea placeheld 'Ask a question...' exists; that textarea is the proof the panel is open. Never click the entry a second time while it is open.
        2. TYPE THE WEARER'S QUESTION into the textarea placeheld 'Ask a question...' and submit it, in their own words — do not translate it into keywords. This is the default and it is almost always right.
        3. The panel also offers ready-made suggestions ('Summarize the video', 'Recommend related content', a few specific to that video). Click one ONLY when the wearer asked for that exact thing — 'summarize this' -> 'Summarize the video'. A suggestion is NEVER a substitute for a real question: asked 'how many acres burned', clicking 'Summarize the video' was measured live returning a summary about structures and power outages, which reads like an answer and is not one. If the wearer asked something specific, TYPE IT, even when a chip looks vaguely related.
        4. Submit ONCE — press the 'Ask' control (or Enter) a single time. The box empties when it sends, so pressing 'Ask' again submits an EMPTY question and discards the one in flight; measured live, that is what turned a correctly typed question into no answer at all. After submitting, the answer streams in as chat text under your question and can take ten seconds or more: WAIT and RE-OBSERVE the page repeatedly until the answer text is there and has stopped growing. Waiting is not failure — pressing anything again is.
        5. Then REPORT THE ANSWER as your result — it is spoken aloud to the wearer, who cannot read a small panel while walking. Give the substance in three or four plain sentences, in your own words, and never claim to have summarized something whose answer you did not actually read.
        6. Answer the question that was ASKED. If the panel's reply does not actually contain it — the video never says the acreage, say — report that plainly ('the video doesn't give a figure for that') and say what it does cover instead. A confident summary handed back in place of the missing fact is the worst outcome here: the wearer has no screen to check it against.
        If the video has no 'Ask questions' button, say so and fall back to reading the visible title and description; do not invent a summary.
    """.trimIndent()

    private val DISCORD = """
        Site notes for Discord (verified on this device):
        - Names in your task arrived through SPEECH RECOGNITION and are often misspelled — 'ranio' or 'rainio' for 'RayNeo', 'jazz hop' for 'jazzhop'. Never conclude a server or channel doesn't exist because the exact spelling is missing: match what the task names against what is ON SCREEN by sound and closeness, and open the closest-sounding item.
        - The far-left narrow strip is the SERVER rail: round icon buttons, one per server, each labeled with its server's name ('RayNeo AR Community', 'KRVR'). To open a server, click the icon whose label matches the name you need. Never click rail icons by position or trial — if no label matches, scroll the rail and look again.
        - Two servers can share a word in their names. If the channel you need is not in the server you opened, open the other similarly-named server and look there.
        - Inside a server, the column beside the rail lists channels; each channel is an ordinary link. Click the channel's name to open it; scroll that column if it is not visible.
        - The 'Find or start a conversation' search box only finds people and direct messages. NEVER use it to look for a server or a channel.
        - The message composer is the textbox at the bottom of an open channel, labeled 'Message #<channel>'. To send: click the composer, type the message, then click the 'Send Message' button at the composer's right edge.
        - Only send a message when the task explicitly asks you to send one, with exactly the text the task gives. Never invent or embellish message content.
    """.trimIndent()
}
