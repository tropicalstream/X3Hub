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
            else -> null
        }
    }

    private val DISCORD = """
        Site notes for Discord (verified on this device):
        - The far-left narrow strip is the SERVER rail. Each server there is an item whose label ends with the server's name — 'Unread messages, RayNeo AR Community', '3 mentions, KRVR'. To open a server, click THAT item (its round icon image). Elements labeled just 'Community Server' are decoration: clicking them does nothing, so never pick them.
        - Two servers can share a word in their names. If the channel you need is not in the server you opened, open the other similarly-named server and look there.
        - Inside a server, the column beside the rail lists channels; each channel is an ordinary link. Click the channel's name to open it; scroll that column if it is not visible.
        - The 'Find or start a conversation' search box only finds people and direct messages. NEVER use it to look for a server or a channel.
        - The message composer is the textbox at the bottom of an open channel, labeled 'Message #<channel>'. To send: click the composer, type the message, then click the 'Send Message' button at the composer's right edge.
        - Only send a message when the task explicitly asks you to send one, with exactly the text the task gives. Never invent or embellish message content.
    """.trimIndent()
}
