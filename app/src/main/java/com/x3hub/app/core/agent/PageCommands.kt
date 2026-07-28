package com.x3hub.app.core.agent

import android.util.Log
import com.x3hub.app.ui.BrowserWindowView
import org.json.JSONObject
import java.net.URLEncoder

/**
 * What a spoken instruction means BEFORE the agent sees it.
 *
 * page-agent has no navigate tool — its whole tool set is
 * click_element_by_index, input_text, select_dropdown_option, wait,
 * ask_user and done — and its own prompt tells it to stay on the page. So
 * "go to archive.org", "scroll down" and "search for Mozart" cannot be
 * done by the agent at all. Sent to it anyway they cost a slow model round
 * trip and then fail, which is exactly what "it opens the page but does
 * not play the song" looks like from the outside.
 *
 * SmartView learned this and put a router in front. This is that router:
 * the app does the browsing, and the agent is left the part only it can
 * do — reading a page and pressing things on it.
 */
object PageCommands {

    private const val TAG = "X3HubPageCmd"

    /** What the router decided, so the caller can report it honestly. */
    sealed class Outcome {
        /** Handled natively; the agent is not needed. */
        data class Handled(val notice: String) : Outcome()
        /** Genuinely a task for the agent — possibly rewritten. */
        data class ForAgent(val task: String) : Outcome()
        /** Drive the page's OWN search box; falls back to the web if it has none. */
        data class SearchInPage(val query: String) : Outcome()
        /** Abort whatever the agent is doing, now. */
        object StopAgent : Outcome()
    }

    /**
     * Head fillers only, anchored: a global strip corrupts real queries —
     * "search just eat" would become "search eat".
     */
    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[,;:!?.\\-_/]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("^(?:(?:uh|um|er|ok|okay|hey|please|can you|could you)\\s+)+"), "")
            .replace(Regex("\\s+please$"), "")
            .trim()

    /**
     * Dictated URLs arrive with their punctuation SPOKEN — "archive dot org",
     * "wikipedia dot org slash wiki slash mozart" — because there is no other
     * way to say a path out loud. Tried as an ALTERNATIVE candidate rather
     * than rewriting the input, so ordinary prose containing "dot" or "slash"
     * is unaffected; the pattern is anchored and demands a domain shape.
     */
    private fun spokenUrlForm(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("\\bcolon\\s+slash\\s+slash\\b"), "://")
            .replace(Regex("\\s+slash\\s+"), "/")
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\s+(?:dash|hyphen)\\s+"), "-")
            .replace(Regex("\\s+underscore\\s+"), "_")
            .replace(Regex("/at\\s+"), "/@")
            .let { s ->
                Regex(
                    "^(\\s*(?:go to|goto|open|load|visit|navigate to)?\\s*" +
                        "(?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,})(/.*)$"
                ).find(s)?.let { m ->
                    m.groupValues[1] + m.groupValues[2].replace(Regex("\\s+"), "")
                } ?: s
            }

    private val URL_RE = Regex(
        "^\\s*(?:go to|goto|open|load|visit|navigate to)?\\s*" +
            "((?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/\\S*)?)\\s*\\.?\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun route(raw: String, window: BrowserWindowView): Outcome {
        // URL shapes are matched on the RAW text and BEFORE normalisation,
        // which strips '.' and '/' — "go to archive.org/details" would
        // otherwise normalise to "go to archive org details" and fall through
        // to the agent as a browsing task it cannot perform.
        (URL_RE.find(raw.trim()) ?: URL_RE.find(spokenUrlForm(raw)))?.let { m ->
            val target = m.groupValues[1].trimEnd('.')
                .let { if (it.startsWith("http")) it else "https://$it" }
            Log.d(TAG, "route=url $target")
            window.loadUrl(target)
            return Outcome.Handled("→ ${target.take(48)}")
        }

        val text = normalize(raw)
        Log.d(TAG, "route: '$text'")

        when (text) {
            "go back", "back" -> { window.goBack(); return Outcome.Handled("‹ Back") }
            "go forward", "forward" -> { window.goForward(); return Outcome.Handled("› Forward") }
            "reload", "refresh" -> { window.reload(); return Outcome.Handled("↻ Reloading") }
            "go home", "home" -> { window.loadUrl(HOME_URL); return Outcome.Handled("⌂ Home") }
            // "stop" must never reach the model: it is what a wearer says when
            // the agent is doing the wrong thing, and a round trip to ask an
            // LLM whether to stop is the one thing that cannot be allowed to
            // be slow.
            "stop", "stop it", "cancel", "never mind", "nevermind" ->
                return Outcome.StopAgent
            "scroll down", "down" -> { window.scrollByJs(420); return Outcome.Handled("↓") }
            "scroll up", "up" -> { window.scrollByJs(-420); return Outcome.Handled("↑") }
            "page down" -> { window.scrollByJs(760); return Outcome.Handled("↓") }
            "page up" -> { window.scrollByJs(-760); return Outcome.Handled("↑") }
            "top", "scroll to top", "scroll to the top", "go to the top", "go to top" ->
                { window.scrollByJs(-2_000_000); return Outcome.Handled("⤒ Top") }
            "bottom", "scroll to bottom", "scroll to the bottom", "go to the bottom", "go to bottom" ->
                { window.scrollByJs(2_000_000); return Outcome.Handled("⤓ Bottom") }
        }

        // "search <q> on duckduckgo|google" — naming an engine is how you ask
        // to LEAVE the site. Tolerant of the spacing Whisper inserts.
        Regex(
            "^search (?:for )?(.+?)\\s+(?:on|in|using|with|via)\\s+" +
                "(duck\\s*duck\\s*go|duckduckgo|ddg|google)\\b.*$"
        ).find(text)?.let { m ->
            val q = m.groupValues[1]
            window.loadUrl(searchUrl(q, m.groupValues[2].startsWith("google")))
            return Outcome.Handled("🔎 $q")
        }

        Regex("^search (?:for )?(.+)$").find(text)?.let { m ->
            val q = m.groupValues[1]
            // "search for Mozart and play the first recording" is a TASK: the
            // searching is a means, the playing is the point. Swallowing the
            // whole utterance as a query silently drops the half the wearer
            // cared about. Detected by ACTION VERB after a conjunction, not by
            // the conjunction itself, so "search for cats and dogs" still works.
            val wantsAction = Regex(
                "\\b(?:and|then|,)\\s+(?:please\\s+)?" +
                    "(?:play|open|click|press|start|select|choose|read|tell|summari[sz]e|" +
                    "show|describe|explain|add|download|watch|listen)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(q)
            if (!wantsAction) return Outcome.SearchInPage(q)
            Log.d(TAG, "route=searchIsTask '$q'")
            return Outcome.ForAgent(raw.trim())
        }

        return Outcome.ForAgent(raw.trim())
    }

    /**
     * Search the page you are ON, using its own search box.
     *
     * On a site with its own index — an archive, a shop, a wiki — the site's
     * search is the whole point of being there, and throwing the wearer back
     * to a web search is a strictly worse answer. Say "… on duckduckgo" to
     * leave deliberately.
     */
    fun searchInPageJs(query: String): String = """
        (function(q){
          function visible(el){
            if (!el || el.disabled || el.readOnly) return false;
            var r = el.getBoundingClientRect();
            if (r.width < 60 || r.height < 12) return false;
            var s = getComputedStyle(el);
            return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
          }
          // Score rather than accept/reject: a page often holds several text
          // fields and the biggest is not the right one. archive.org is the
          // cautionary case — its Wayback URL bar is by far the largest input,
          // so a query went in there and looked up ARCHIVED SITES instead of
          // searching the collection.
          function scoreField(el){
            if (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') return -99;
            var t = (el.type || 'text').toLowerCase();
            if (['hidden','password','checkbox','radio','file','submit','button','email','number'].indexOf(t) >= 0) return -99;
            var hay = [el.name, el.id, el.placeholder, el.className,
                       el.getAttribute('aria-label'), el.getAttribute('role'),
                       el.getAttribute('title')].join(' ').toLowerCase();
            // A URL/address lookup takes a location, not a query. Fatal, not a
            // nudge: filling it navigates somewhere unrelated.
            if (/\burl\b|\baddress\b|\bdomain\b|https?:|home page|web ?address/.test(hay)) return -99;
            var s = 0;
            if (t === 'search') s += 4;
            if (/^(q|s|query|search|kw|term)${'$'}/.test((el.name || '').toLowerCase())) s += 4;
            if (/search/.test(hay)) s += 3;
            if (/query|find|keyword/.test(hay)) s += 2;
            var f = el.form;
            if (f && /search/i.test((f.getAttribute('role') || '') + ' ' + (f.action || '') + ' ' + (f.className || ''))) s += 2;
            return s;
          }
          // Sites like archive.org put the search box inside a web component,
          // where a plain querySelectorAll cannot see it.
          function collect(root, out, depth, sel){
            if (!root || depth > 5) return;
            var all;
            try { all = root.querySelectorAll(sel); } catch(e){ return; }
            for (var i = 0; i < all.length; i++) out.push(all[i]);
            var hosts;
            try { hosts = root.querySelectorAll('*'); } catch(e){ return; }
            for (var j = 0; j < hosts.length; j++){
              if (hosts[j].shadowRoot) collect(hosts[j].shadowRoot, out, depth + 1, sel);
            }
          }
          function bestField(){
            var out = []; collect(document, out, 0, 'input, textarea');
            var scored = [];
            for (var i = 0; i < out.length; i++){
              if (!visible(out[i])) continue;
              var sc = scoreField(out[i]);
              if (sc > 0) scored.push({el: out[i], s: sc});
            }
            if (!scored.length) return null;
            scored.sort(function(a, b){
              if (b.s !== a.s) return b.s - a.s;
              return b.el.getBoundingClientRect().width - a.el.getBoundingClientRect().width;
            });
            return scored[0].el;
          }
          var el = bestField();
          if (!el){
            // Many sites keep the search box collapsed behind a magnifier
            // until it is clicked — archive.org does. Reveal it and let the
            // caller retry once the DOM has settled.
            var toggles = []; collect(document, toggles, 0, 'button, a, [role="button"], summary');
            for (var k = 0; k < toggles.length; k++){
              var b = toggles[k];
              var lbl = ((b.getAttribute('aria-label') || '') + ' ' + (b.title || '') +
                         ' ' + (b.className || '') + ' ' + (b.id || '')).toLowerCase();
              if (/search/.test(lbl) && visible(b)){ try { b.click(); return 'opened'; } catch(e){} }
            }
            return 'none';
          }
          el.focus();
          // Frameworks track value through the prototype setter; assigning
          // .value directly leaves their state stale and the field reverts on
          // the next render.
          try {
            var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement : window.HTMLInputElement;
            Object.getOwnPropertyDescriptor(proto.prototype, 'value').set.call(el, q);
          } catch(e){ el.value = q; }
          el.dispatchEvent(new Event('input',  {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          var submitted = false;
          if (el.form){
            try { if (el.form.requestSubmit) { el.form.requestSubmit(); submitted = true; } } catch(e){}
            if (!submitted){ try { el.form.submit(); submitted = true; } catch(e){} }
          }
          if (!submitted){
            ['keydown','keypress','keyup'].forEach(function(type){
              el.dispatchEvent(new KeyboardEvent(type, {
                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true
              }));
            });
          }
          return 'ok';
        })(${JSONObject.quote(query.trim())});
    """.trimIndent()

    /** Same home the assistant's browser tool uses. */
    private const val HOME_URL = "https://duckduckgo.com/"

    fun searchUrl(query: String, google: Boolean): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return if (google) "https://www.google.com/search?q=$q" else "https://duckduckgo.com/?q=$q"
    }
}
