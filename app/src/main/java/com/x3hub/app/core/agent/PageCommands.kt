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
        /**
         * Go to [url] first, THEN hand [task] to the agent on whatever loads.
         *
         * The page agent lives inside one document: it can click, type and
         * scroll, but it cannot navigate to another site. So "search for John
         * Digweed and play the music", handed over whole while standing on a
         * Bit Shifter page, is a task it cannot even begin — and it said so,
         * suggesting the wearer use the search function it could not reach.
         * Doing the search HERE turns an impossible request into an ordinary
         * one: the agent wakes up already looking at the results.
         */
        data class SearchThenAgent(
            val url: String,
            val task: String,
            val notice: String
        ) : Outcome()

        /**
         * Load [url], run [js] when it lands, and if that script navigates
         * again, run [thenJs] when THAT lands.
         *
         * Two hops because of an origin wall. Bandcamp's fan API answers only
         * on bandcamp.com, and the wearer is usually standing on an artist
         * subdomain when they ask for their own music — measured from
         * pixelh8.bandcamp.com, the call dies with "Failed to fetch". So the
         * window has to be on bandcamp.com before anything can ask who is
         * logged in.
         */
        data class NavigateThenScript(
            val url: String,
            val notice: String,
            val js: String,
            val thenJs: String? = null
        ) : Outcome()

        /**
         * Run [js] on the page as it stands, and if it navigates, run [thenJs]
         * when the next document lands.
         *
         * For a site whose own API can answer from where we already are —
         * no hop needed, unlike Bandcamp's cross-origin wall.
         */
        data class RunScript(
            val js: String,
            val notice: String,
            val thenJs: String? = null
        ) : Outcome()
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

        // Your own Bandcamp music, by name rather than by hunting the UI.
        // Reaching purchases by hand means finding a profile picture and a
        // menu item inside a 170px window; asking for them used to be worse,
        // because "go to my purchases" was taken as a SEARCH and landed on a
        // comedy track called "keeping track of my venmo purchases with an
        // elaborate hieroglyphic system". Scoped to Bandcamp: "my purchases"
        // means nothing in particular anywhere else, and guessing would be
        // worse than declining.
        if (hostStem(window.currentUrl) == "bandcamp") {
            bandcampIntent(text)?.let { return it }
        }

        // Radio Garden is a WebGL globe with NO search field anywhere in the
        // document — the only input on a station page is a 0x0 volume slider.
        // So the agent, told to search, could only advise the wearer to "use
        // the search function", which is not reachable by anything it can do.
        // The site's own API answers the question directly.
        if (hostStem(window.currentUrl) == "radio") {
            radioGardenIntent(text)?.let { return it }
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

        // "search <q> on <site>" — naming a SITE means search that site, not
        // the web and not whatever box this page happens to have. Runs after
        // the engine rule (duckduckgo/google mean the open web) and before
        // the generic one, which would otherwise swallow "on youtube" into
        // the query and then fall back to DuckDuckGo when the current page
        // had no search box.
        Regex(
            "^search (?:for )?(.+?)\\s+(?:on|in|using|with|via|at)\\s+(?:the\\s+)?(.+?)$"
        ).find(text)?.let { m ->
            val q = m.groupValues[1]
            val site = m.groupValues[2]
            siteSearchUrl(site, q)?.let { url ->
                Log.d(TAG, "route=siteSearch site='$site' q='$q'")
                window.loadUrl(url)
                return Outcome.Handled("🔎 $q on $site")
            }
        }

        Regex("^search (?:for )?(.+)$").find(text)?.let { m ->
            val q = m.groupValues[1]
            // "search for Mozart and play the first recording" is a TASK: the
            // searching is a means, the playing is the point. Swallowing the
            // whole utterance as a query silently drops the half the wearer
            // cared about. Detected by ACTION VERB after a conjunction, not by
            // the conjunction itself, so "search for cats and dogs" still works.
            val split = SEARCH_THEN_ACTION.find(q)
                ?: return Outcome.SearchInPage(q)
            val subject = split.groupValues[1].trim()
            val action = split.groupValues[2].trim()

            // Do the searching ourselves when we know how to search this site.
            // Handing the whole thing to the agent only works when the target
            // happens to be on the page already — "search for bit shifter and
            // play their music" succeeded solely because the window was ALREADY
            // on bit-shifter.bandcamp.com and the agent could hop within the
            // site. Asked for a different artist from that same page it
            // answered "Cannot play John Digweed from here", which is true and
            // useless: it has no way to leave the page it is standing on.
            siteSearchUrlForHost(window.currentUrl, subject)?.let { url ->
                Log.d(TAG, "route=searchThenAgent subject='$subject' action='$action'")
                return Outcome.SearchThenAgent(url, action, "🔎 $subject")
            }
            // No site search we recognise — the agent's in-page search box is
            // still the best available answer, so this behaves as it always did.
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

    /**
     * Sites that own their own search, keyed by what a wearer calls them.
     *
     * "Search cats on YouTube" used to reach neither YouTube nor the site's
     * search box: only duckduckgo and google were recognised after "on", so
     * the site name stayed glued to the query ("cats on youtube") and was
     * typed into whatever field the page happened to have — and when there
     * was none, the fallback dropped the wearer on DuckDuckGo. Naming a site
     * is a request to search THAT SITE.
     *
     * A results URL rather than driving the page's own box: it works from
     * any page, needs no field-scoring heuristic, and survives the
     * single-page apps (YouTube above all) whose search box is the least
     * scriptable thing on them.
     *
     * Whisper hears these as two words about as often as one, so every
     * pattern tolerates internal spaces.
     */
    /** The site name inside a host — "bit-shifter.bandcamp.com" -> "bandcamp". */
    private fun hostStem(url: String?): String? {
        val host = runCatching { java.net.URL(url ?: return null).host }
            .getOrNull()?.lowercase() ?: return null
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return null
        return labels[labels.size - 2]
    }

    private val BC_PURCHASES = Regex(
        "^(?:go to |open |show |take me to |see )?(?:my )?" +
            "(?:bandcamp )?(?:purchases|collection|library|albums i bought|" +
            "music i bought|what i bought|what i have bought)$",
        RegexOption.IGNORE_CASE
    )

    private val BC_SHUFFLE = Regex(
        "^(?:shuffle|randomi[sz]e)(?: my)?(?: music| collection| library| purchases| albums)?$|" +
            "^play (?:something|anything)(?: i own| from my collection| random)?$|" +
            "^play (?:my )(?:music|collection|library|purchases)$",
        RegexOption.IGNORE_CASE
    )

    /** Bandcamp-only intents, or null to fall through to normal routing. */
    private fun bandcampIntent(text: String): Outcome? = when {
        BC_PURCHASES.matches(text) -> Outcome.NavigateThenScript(
            url = BC_HOME,
            notice = "Your Bandcamp collection",
            js = BC_GO_TO_COLLECTION_JS
        )
        BC_SHUFFLE.matches(text) -> Outcome.NavigateThenScript(
            url = BC_HOME,
            notice = "Shuffling your collection",
            js = BC_SHUFFLE_JS,
            thenJs = BC_PRESS_PLAY_JS
        )
        else -> null
    }

    private const val BC_HOME = "https://bandcamp.com/"

    /**
     * Who is logged in, asked at RUNTIME.
     *
     * collection_summary answers for whichever fan owns the cookie, so this
     * works for anyone who installs the app — there is no username, fan id or
     * account anywhere in this source. The identity cookie is httpOnly, which
     * is why this has to run as page script with credentials rather than from
     * an OkHttp call in Kotlin: only the WebView's own jar can see it.
     */
    private const val BC_FAN_JS = """
        function x3fan(){
          return fetch('https://bandcamp.com/api/fan/2/collection_summary',
                       {credentials:'include'})
            .then(function(r){ return r.json(); })
            .then(function(j){
              var s = j && j.collection_summary;
              if (!s || !s.fan_id) throw new Error('not signed in');
              return s;
            });
        }
    """

    private val BC_GO_TO_COLLECTION_JS = """
        (function(){
          $BC_FAN_JS
          x3fan().then(function(s){
            if (s.url) location.href = s.url;
          }).catch(function(e){ console.log('X3BC ' + e); });
        })();
    """

    /**
     * Play something the wearer already owns, at random.
     *
     * Bandcamp's web collection has NO shuffle control — measured on the real
     * page, there is no element matching shuffle by class, id, title or aria
     * label, and the string does not appear in the markup at all. So shuffle
     * here means what the wearer means by it: pick one of their purchases and
     * play it. collection_items returns url_hints (subdomain + slug), which
     * builds a playable address without scraping a grid that lazy-loads eight
     * items at a time into a window this small.
     */
    private val BC_SHUFFLE_JS = """
        (function(){
          $BC_FAN_JS
          x3fan().then(function(s){
            return fetch('https://bandcamp.com/api/fancollection/1/collection_items', {
              method: 'POST', credentials: 'include',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                fan_id: s.fan_id, older_than_token: '9999999999::a::', count: 200
              })
            }).then(function(r){ return r.json(); });
          }).then(function(j){
            var items = (j && j.items) || [];
            var playable = items.filter(function(it){
              var h = it && it.url_hints;
              return h && h.slug && (h.subdomain || h.custom_domain);
            });
            if (!playable.length) { console.log('X3BC nothing owned'); return; }
            var it = playable[Math.floor(Math.random() * playable.length)];
            var h = it.url_hints;
            var base = h.custom_domain
              ? ('https://' + h.custom_domain)
              : ('https://' + h.subdomain + '.bandcamp.com');
            var kind = (h.item_type === 't') ? 'track' : 'album';
            location.href = base + '/' + kind + '/' + h.slug;
          }).catch(function(e){ console.log('X3BC ' + e); });
        })();
    """

    /**
     * Press play once the album page arrives.
     *
     * Retried because the player is built after the document reports done,
     * and bounded so a page that never produces one stops trying. Stops at
     * the first button that actually starts audio rather than clicking every
     * playbutton on the page — an album page has one per track, and clicking
     * them all would race them against each other.
     */
    private val BC_PRESS_PLAY_JS = """
        (function(){
          var tries = 0;
          function playing(){
            var a = document.querySelectorAll('audio');
            for (var i = 0; i < a.length; i++) if (!a[i].paused) return true;
            return false;
          }
          var iv = setInterval(function(){
            if (playing() || ++tries > 40) { clearInterval(iv); return; }
            var b = document.querySelector('.playbutton');
            if (b) b.click();
          }, 400);
        })();
    """

    private val RG_TUNE = Regex(
        "^(?:search (?:for )?|find |play |listen to |tune (?:in )?to |put on |" +
            "switch to |go to )(.+?)(?: radio| station| fm| am)?$",
        RegexOption.IGNORE_CASE
    )

    /** Radio Garden intents, or null to fall through to normal routing. */
    private fun radioGardenIntent(text: String): Outcome? {
        val q = RG_TUNE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (q.isBlank() || q.length < 2) return null
        val encoded = URLEncoder.encode(q, "UTF-8")
        return Outcome.RunScript(
            js = rgTuneJs(encoded),
            notice = "Tuning to $q",
            thenJs = RG_START_JS
        )
    }

    /**
     * Find a station by name and go to it.
     *
     * Radio Garden's own search endpoint answers from the same origin, so
     * unlike Bandcamp there is no hop: the page we are standing on can ask
     * directly. Channels only — a search for a city also returns places, and
     * "play KPFA" means the station, not a pin on a globe.
     */
    private fun rgTuneJs(encodedQuery: String): String = """
        (function(){
          fetch('https://radio.garden/api/search?q=$encodedQuery',
                {credentials:'include'})
            .then(function(r){ return r.json(); })
            .then(function(j){
              var hits = (j && j.hits && j.hits.hits) || [];
              var chan = null;
              for (var i = 0; i < hits.length; i++) {
                var s = hits[i] && hits[i]._source;
                var p = s && s.page;
                if (p && p.url && (p.type === 'channel' || s.type === 'channel')) {
                  chan = p; break;
                }
              }
              if (!chan) { console.log('X3RG no station'); return; }
              console.log('X3RG tuning ' + chan.title + ' -> ' + chan.url);
              location.href = 'https://radio.garden' + chan.url;
            })
            .catch(function(e){ console.log('X3RG ' + e); });
        })();
    """

    /**
     * Get past the front door.
     *
     * Radio Garden opens behind a full-window "Start Radio Garden" cover and
     * plays nothing at all until it is dismissed — a station page loads with
     * the right title and zero audio, which reads as a dead window. Retried
     * because the cover is drawn after the document reports done.
     *
     * Do not look for an <audio> element to decide whether this worked:
     * Radio Garden plays through Web Audio, so the DOM shows no media even
     * while sound is coming out. Measured on device — nAudio 0 with a live
     * USAGE_MEDIA track in dumpsys.
     */
    private val RG_START_JS = """
        (function(){
          var tries = 0;
          var iv = setInterval(function(){
            var gate = document.querySelector('[aria-label="Start Radio Garden"]');
            if (gate) { try { gate.click(); } catch (e) {} }
            if (++tries > 50) clearInterval(iv);
          }, 400);
        })();
    """

    /**
     * Splits "<subject> and <verb> …" into the thing to look for and the
     * thing to do with it. Anchored on an ACTION VERB after a conjunction so
     * "search for cats and dogs" stays one query rather than becoming a
     * search for cats and an attempt to do something to dogs.
     */
    private val SEARCH_THEN_ACTION = Regex(
        "^(.+?)\\s+(?:and|then|,)\\s+(?:please\\s+)?" +
            "((?:play|open|click|press|start|select|choose|read|tell|summari[sz]e|" +
            "show|describe|explain|add|download|watch|listen)\\b.*)$",
        RegexOption.IGNORE_CASE
    )

    private val SITE_SEARCHES: List<Pair<Regex, String>> = listOf(
        Regex("^(?:the\\s+)?you\\s*tube(?:\\.com)?$", RegexOption.IGNORE_CASE)
            to "https://m.youtube.com/results?search_query=",
        Regex("^(?:the\\s+)?wiki\\s*p[ae]dia(?:\\.org)?$", RegexOption.IGNORE_CASE)
            to "https://en.m.wikipedia.org/w/index.php?search=",
        Regex("^(?:the\\s+)?(?:internet\\s+)?archive(?:\\.org)?$", RegexOption.IGNORE_CASE)
            to "https://archive.org/search?query=",
        Regex("^(?:the\\s+)?reddit(?:\\.com)?$", RegexOption.IGNORE_CASE)
            to "https://www.reddit.com/search/?q=",
        Regex("^(?:the\\s+)?git\\s*hub(?:\\.com)?$", RegexOption.IGNORE_CASE)
            to "https://github.com/search?q=",
        Regex("^(?:the\\s+)?amazon(?:\\.com)?$", RegexOption.IGNORE_CASE)
            to "https://www.amazon.com/s?k=",
        Regex("^(?:the\\s+)?e\\s*bay(?:\\.com)?$", RegexOption.IGNORE_CASE)
            to "https://www.ebay.com/sch/i.html?_nkw=",
        // Bandcamp hides its search behind a magnifier: the input[name=q] is
        // in the DOM on every artist page but measures 0x0, so the in-page
        // scorer rightly refuses it (typing into a zero-size field types into
        // nothing) and the wearer got thrown to DuckDuckGo — while standing
        // on the music site they were asking to search. Measured on device:
        // bandcamp.com/search?q= returns 18 usable results for "bit shifter".
        Regex("^(?:the\\s+)?band\\s*camp(?:\\.com)?$", RegexOption.IGNORE_CASE)
            to "https://bandcamp.com/search?q="
    )

    /**
     * The search URL for the site a window is ALREADY on, or null.
     *
     * Used when an in-page search finds no usable box: dropping the wearer
     * on DuckDuckGo while they are standing on YouTube is never what they
     * asked for. Matched on the host so m./www./mobile. prefixes all count.
     */
    fun siteSearchUrlForHost(currentUrl: String?, query: String): String? {
        val host = runCatching { java.net.URL(currentUrl ?: return null).host }
            .getOrNull()?.lowercase() ?: return null
        // "m.youtube.com" and "en.wikipedia.org" both have to reduce to the
        // name a person uses. Taking the FIRST label gives "m" and "en";
        // dropping the TLD and taking the LAST remaining one gives "youtube"
        // and "wikipedia", which is what the table is keyed by.
        val labels = host.split('.').filter { it.isNotBlank() }
        val stem = when {
            labels.size >= 2 -> labels[labels.size - 2]
            labels.size == 1 -> labels[0]
            else -> return null
        }
        val template = SITE_SEARCHES.firstOrNull {
            it.first.matches(stem) || it.first.matches(host)
        }?.second ?: return null
        return template + URLEncoder.encode(query.trim(), "UTF-8")
    }

    /** The search URL for a site the wearer named, or null if unknown. */
    fun siteSearchUrl(site: String, query: String): String? {
        val name = site.trim().trim('"', '\'').trim()
        val template = SITE_SEARCHES.firstOrNull { it.first.matches(name) }?.second
            ?: return null
        return template + URLEncoder.encode(query.trim(), "UTF-8")
    }

    fun searchUrl(query: String, google: Boolean): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return if (google) "https://www.google.com/search?q=$q" else "https://duckduckgo.com/?q=$q"
    }
}
