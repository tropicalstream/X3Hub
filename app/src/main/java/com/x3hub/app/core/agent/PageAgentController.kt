package com.x3hub.app.core.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.x3hub.app.ui.BrowserWindowView
import org.json.JSONObject

/**
 * SmartView's page agent, one instance per browser window.
 *
 * The agent itself is an in-page bundle (assets/page-agent.js) that reads
 * the DOM, decides what to click, and calls an LLM to do it. Everything
 * here is the host half: getting the bundle into the document, keeping the
 * model's credential out of the page's reach, and surviving the fact that
 * the agent dies whenever it navigates.
 *
 * WHY PER WINDOW. SmartView held all of this — agentRunning, the task, the
 * hop count, the pending-ask id, the watchdog, and the fetch response path
 * — as MainActivity fields, which is correct when there is exactly one
 * WebView. x3hub runs up to three, and the llmFetch reply in particular
 * has to be evaluated back into the WebView that asked. So the state lives
 * beside the window it belongs to.
 *
 * WHAT IS DELIBERATELY LEFT OUT.
 *  • The panel. page-agent's own UI is written in fixed px for a phone
 *    viewport (--width:360px, bottom:100px) and this window is 170–300 px
 *    wide with the layout pinned to 320 CSS px — the panel would cover the
 *    page it is meant to be working on. Status goes to the HUD instead.
 *  • ask_user's voice loop. SmartView spoke the question and reopened the
 *    mic; here the microphone and the speaker belong to the Gemini Live
 *    session for the whole time it is up. The question is surfaced on the
 *    HUD and the ask is refused, which the system instructions already
 *    steer the model away from needing.
 *  • Media detection. AudioManager reports for the whole device, so with
 *    three windows there is no way to attribute a sound to one page.
 */
class PageAgentController(
    private val context: Context,
    private val window: BrowserWindowView,
    private val showNotice: (String) -> Unit,
    /**
     * Offered the agent's outcome before it is spoken. Returning true means
     * an orchestrator took it — a live Gemini session that will relay the
     * result in its own voice and decide the next step — so the agent's
     * standalone TTS stays quiet instead of talking over it. Null or false
     * keeps the original behaviour: the agent speaks for itself.
     */
    private val onResult: ((message: String, ok: Boolean) -> Boolean)? = null
) {

    private val main = Handler(Looper.getMainLooper())

    private var running = false
    private var taskText: String? = null
    private var hops = 0
    private var resumePending = false
    private var injected = false

    private val watchdog = Runnable {
        if (!running) return@Runnable
        Log.w(TAG, "agent watchdog fired — giving up")
        running = false
        taskText = null
        hops = 0
        showNotice("The agent stopped responding.")
    }

    init {
        window.addAgentBridge(Bridge(), BRIDGE)
        window.onPageStartedListener = { injected = false }
        window.onPageFinishedListener = { url -> onPageReady(url) }
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    fun run(task: String) {
        val provider = AgentProviders.provider(context)
        if (AgentProviders.key(context).isBlank()) {
            refuse("No ${provider.label} key is set, so the page agent cannot run.")
            return
        }
        val url = window.currentUrl
        if (url.isNullOrBlank() || url.startsWith("about:")) {
            refuse("The page has not loaded yet, so the agent has nothing to work on.")
            return
        }
        dispatch(task, retry = true, continuation = false)
    }

    /**
     * A run that never starts still ENDS — through the same door a
     * finished one uses. These bails were HUD notices only, and a wearer
     * mid-conversation cannot see a notice: the orchestrator had been told
     * the agent was working, held the session open for a report, and the
     * report never came. Silence here is a broken promise upstream.
     */
    private fun refuse(message: String) {
        showNotice(message.take(64))
        onResult?.invoke(message, false)
    }

    fun stop() {
        AgentSpeech.stop()
        running = false
        taskText = null
        hops = 0
        clearWatchdog()
        window.evaluateJavascript("try{window.__x3AgentStop&&window.__x3AgentStop()}catch(e){}")
    }

    fun destroy() {
        clearWatchdog()
        window.onPageStartedListener = null
        window.onPageFinishedListener = null
    }

    // ------------------------------------------------------------------
    // Lifecycle: a navigation destroys the agent with the document
    // ------------------------------------------------------------------

    /**
     * The agent lives in the document, so following a link ends the run.
     * That is a normal step — search, open a result, act on it — so the
     * same task is handed to the agent again on the new page and the hop
     * budget is what stops it wandering.
     */
    private fun onPageReady(url: String?) {
        if (url.isNullOrBlank() || url.startsWith("about:")) return
        if (!running && taskText == null) return

        if (running) {
            // The old instance went with the old document.
            running = false
            if (taskText != null && hops < MAX_HOPS) {
                hops++
                resumePending = true
                Log.d(TAG, "agent hopped (${hops}/$MAX_HOPS) — resuming on $url")
            } else {
                taskText = null
                hops = 0
                resumePending = false
                clearWatchdog()
            }
        }
        if (resumePending) {
            val task = taskText
            resumePending = false
            inject()
            if (task != null) {
                main.postDelayed({ dispatch(task, retry = true, continuation = true) }, 900)
            }
        }
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private fun dispatch(task: String, retry: Boolean, continuation: Boolean) {
        if (!continuation) {
            taskText = task
            hops = 0
        }
        window.evaluateJavascript("(typeof window.__x3AgentTask==='function')") { r ->
            when {
                r == "true" -> {
                    running = true
                    armWatchdog()
                    showNotice("Agent: ${task.take(48)}")
                    window.evaluateJavascript(
                        "window.__x3AgentTask(${JSONObject.quote(task)})"
                    )
                }
                retry -> {
                    inject()
                    main.postDelayed({ dispatch(task, retry = false, continuation = true) }, 900)
                }
                else -> showNotice("The agent can't run on this page.")
            }
        }
    }

    private fun armWatchdog() {
        clearWatchdog()
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun clearWatchdog() = main.removeCallbacks(watchdog)

    // ------------------------------------------------------------------
    // Injection
    // ------------------------------------------------------------------

    private fun inject() {
        val bundle = bundle(context) ?: return
        if (AgentProviders.key(context).isBlank()) return
        val p = AgentProviders.provider(context)

        // The bundle auto-inits against its own demo LLM when
        // document.currentScript is null, which is exactly the
        // evaluateJavascript case. Spoof currentScript with autoInit=false
        // around the eval to keep it inert, then initialise it ourselves.
        val spoof = "try{Object.defineProperty(document,'currentScript',{configurable:true," +
            "get:function(){return {src:'https://x3hub.local/page-agent.js?autoInit=false'};}});}catch(e){}"
        val unspoof = "try{delete document.currentScript;}catch(e){}"

        runCatching {
            window.evaluateJavascript(spoof)
            window.evaluateJavascript(fetchProxyJs())
            window.evaluateJavascript(bundle)
            window.evaluateJavascript(unspoof)
            window.evaluateJavascript(initJs(p))
        }
        injected = true
    }

    /**
     * Route the agent's provider calls through the native bridge.
     *
     * Two reasons, both load-bearing: the loaded page's CSP connect-src
     * would otherwise block them, and the credential never has to exist in
     * the page's JS world at all.
     *
     * The host check compares the PARSED host, not a substring of the URL.
     * SmartView's first version matched the whole string including the
     * query, so any page could route arbitrary requests through the app as
     * the app — no CORS, no preflight — just by naming an allowed host in
     * a parameter. Native re-checks regardless, because the page can
     * redefine anything in here.
     */
    private fun fetchProxyJs(): String {
        val hosts = AgentProviders.HOSTS.joinToString(",") { JSONObject.quote(it) }
        return """
            (function(){
              if (window.__x3FetchProxy) return; window.__x3FetchProxy = true;
              var HOSTS = [$hosts];
              function isAgentUrl(u){
                var h = '';
                try { h = new URL(u, location.href).host.toLowerCase(); } catch(e){ return false; }
                for (var i=0;i<HOSTS.length;i++){
                  if (h === HOSTS[i] || h.endsWith('.' + HOSTS[i])) return true;
                }
                return false;
              }
              var seq = 0, pending = {};
              window.__x3FetchResolve = function(id, status, ok, b64){
                var p = pending[id]; if(!p) return; delete pending[id];
                var body = '';
                try { body = decodeURIComponent(escape(atob(b64))); }
                catch(e){ try{ body = atob(b64); }catch(e2){ body = ''; } }
                try { p.resolve(new Response(body, {status: status||200, statusText: ok?'OK':'ERR'})); }
                catch(e){ p.reject(new TypeError('resp: ' + e)); }
              };
              window.__x3FetchReject = function(id, msg){
                var p = pending[id]; if(!p) return; delete pending[id];
                p.reject(new TypeError(msg || 'network request failed'));
              };
              var orig = window.fetch ? window.fetch.bind(window) : null;
              window.fetch = function(input, init){
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                if (!isAgentUrl(url) && orig) return orig(input, init);
                init = init || {};
                var method = init.method || (typeof input==='object' && input && input.method) || 'GET';
                var headers = {};
                try {
                  var h = init.headers || (typeof input==='object' && input && input.headers);
                  if (h){
                    if (typeof h.forEach === 'function'){ h.forEach(function(v,k){ headers[k]=v; }); }
                    else { Object.keys(h).forEach(function(k){ headers[k]=h[k]; }); }
                  }
                } catch(e){}
                var body = init.body;
                if (body != null && typeof body !== 'string'){ try{ body = String(body); }catch(e){ body=''; } }
                return new Promise(function(resolve, reject){
                  var id = 'f' + (++seq); pending[id] = {resolve:resolve, reject:reject};
                  try { $BRIDGE.llmFetch(id, url, String(method).toUpperCase(), JSON.stringify(headers), body||''); }
                  catch(e){ delete pending[id]; reject(new TypeError('bridge: ' + e)); }
                });
              };
            })();
        """.trimIndent()
    }

    private fun initJs(p: AgentProviders.Provider): String = """
        (function(){
          try{
            if (window.__x3AgentTask) return;
            try{ if(window.pageAgent && window.pageAgent.dispose) window.pageAgent.dispose(); }catch(e){}
            window.pageAgent = new window.PageAgent({
              model: ${JSONObject.quote(p.model)},
              baseURL: ${JSONObject.quote(p.baseUrl)},
              // Placeholder, NOT the credential — page-agent keeps its
              // options on this.config in the page's own world, where any
              // script could read it. The real key is attached natively.
              apiKey: 'x3-proxy',
              language: 'en-US',
              // MUST be { system: "..." }: page-agent reads
              // instructions?.system with no coercion and no warning, so a
              // bare string is accepted and then silently ignored.
              instructions: { system: [
                'You are running on AR glasses. The user is hands-free and reads',
                'your replies on a small heads-up display, so be brief and specific.',
                'Answer from the current page whenever the page can answer.',
                'Do NOT ask the user to confirm or clarify anything you could',
                'determine by reading or scrolling the page yourself — asking is',
                'expensive here and usually fails.',
                'If the page cannot answer, say so plainly and stop.',
                'You MAY follow a link when the task genuinely needs another page.',
                'Navigation ends your current run, but the app hands the same task',
                'back to you on the new page, so continue from wherever you land.',
                'Budget: about three navigations. Spend them reaching the page that',
                'completes the task, not on browsing.',
                'If the task is to PLAY, WATCH or LISTEN to something, reaching the',
                'item is only halfway: actually press the play control. Reporting',
                'that you found it is NOT completing it.'
              ].join(' ') }
            });
            // The panel is built for a phone-sized viewport and would cover
            // this window entirely. Status goes to the HUD instead. Hiding it
            // once is NOT enough — execute() re-shows it on every task, so the
            // "Task completed / Enter new task" card was left sitting in the
            // window after each run. Neutering show() keeps it gone for good.
            try{
              if (window.pageAgent.panel){
                if (window.pageAgent.panel.hide) window.pageAgent.panel.hide();
                window.pageAgent.panel.show = function(){};
              }
            }catch(e){}
            // Belt and braces: the bundle may rebuild its DOM between steps,
            // so any panel element that appears anyway is removed.
            window.__x3HidePanel = function(){
              try{ if(window.pageAgent && window.pageAgent.panel){ window.pageAgent.panel.hide(); window.pageAgent.panel.show = function(){}; } }catch(e){}
              try{
                var els = document.querySelectorAll('[class*="page-agent" i], [id*="page-agent" i], page-agent');
                for (var i=0;i<els.length;i++){ els[i].style.display='none'; }
              }catch(e){}
            };

            // ask_user has no answer path here — the mic belongs to the Gemini
            // session. Surface the question and refuse, rather than leaving the
            // run hanging on a promise nothing will ever resolve.
            var x3Ask = function(question){
              try{ $BRIDGE.onAgentAsk(String(question || '')); }catch(e){}
              return Promise.reject(new Error('ask_user unavailable; answer from the page'));
            };
            try{ window.pageAgent.onAskUser = x3Ask; }catch(e){}
            try{ if(window.pageAgent.core) window.pageAgent.core.onAskUser = x3Ask; }catch(e){}

            window.__x3AgentStop = function(){
              try{ window.pageAgent.stop(); }catch(e){}
              try{ if(window.pageAgent.core && window.pageAgent.core.stop) window.pageAgent.core.stop(); }catch(e){}
            };
            window.__x3AgentTask = function(task){
              try{
                var st = '';
                try{ st = window.pageAgent.status || ''; }catch(e){}
                if (st === 'running'){ $BRIDGE.onAgentBusy(String(task)); return; }
                setTimeout(window.__x3HidePanel, 50);
                Promise.resolve(window.pageAgent.execute(task)).then(function(res){
                  setTimeout(window.__x3HidePanel, 0);
                  // execute() RESOLVES for LLM errors, step-limit exhaustion
                  // and user abort — only disposal/duplicate/empty reject. So
                  // success has to be read off the result, not inferred from
                  // "did not throw".
                  var ok = !!(res && res.success);
                  var msg = (res && res.data) || '';
                  if (ok) $BRIDGE.onAgentDone(String(msg || 'Done.'));
                  else $BRIDGE.onAgentError(String(msg || 'Task did not complete.'));
                }).catch(function(e){
                  setTimeout(window.__x3HidePanel, 0);
                  $BRIDGE.onAgentError(String((e && e.message) || e));
                });
              }catch(e){ $BRIDGE.onAgentError(String((e && e.message) || e)); }
            };
            $BRIDGE.onAgentReady();
          }catch(e){ $BRIDGE.onAgentError('init: ' + String((e && e.message) || e)); }
        })();
    """.trimIndent()

    // ------------------------------------------------------------------
    // Bridge
    // ------------------------------------------------------------------

    private inner class Bridge {

        @JavascriptInterface
        fun onAgentReady() = main.post { Log.d(TAG, "page-agent ready on ${window.currentUrl}") }

        @JavascriptInterface
        fun onAgentDone(message: String) = main.post {
            taskText = null
            hops = 0
            resumePending = false
            running = false
            clearWatchdog()
            // Shown AND spoken. The notice strip holds about one line, and
            // the window is too small to read a paragraph off — an answer
            // that is only displayed is an answer the wearer never gets.
            // When an orchestrating session is live it does the speaking,
            // in one voice, and can chain the wearer's next step.
            showNotice(message)
            if (onResult?.invoke(message, true) != true) {
                AgentSpeech.speak(context, message)
            }
        }

        @JavascriptInterface
        fun onAgentError(message: String) = main.post {
            taskText = null
            hops = 0
            resumePending = false
            running = false
            clearWatchdog()
            Log.w(TAG, "agent error: $message")
            showNotice("Agent: ${message.take(90)}")
            onResult?.invoke(message, false)
        }

        @JavascriptInterface
        fun onAgentBusy(task: String) = main.post {
            showNotice("Agent is still working.")
        }

        @JavascriptInterface
        fun onAgentAsk(question: String) = main.post {
            armWatchdog()
            showNotice("Agent asked: ${question.take(80)}")
        }

        /**
         * page-agent's LLM call, performed natively. The reply must be
         * evaluated back into THIS window — with three of them there is no
         * ambient "the WebView" to call.
         */
        @JavascriptInterface
        fun llmFetch(id: String, url: String, method: String, headersJson: String, body: String) {
            val safeId = id.replace(Regex("[^A-Za-z0-9]"), "")
            main.post { armWatchdog() }
            AgentProviders.rawRequest(context, url, method, headersJson, body) { code, ok, bytes ->
                main.post {
                    if (code == 0) {
                        val msg = String(bytes)
                            .replace("\\", "\\\\").replace("'", "\\'")
                            .replace("\n", " ").replace("\r", " ")
                            .take(180)
                        window.evaluateJavascript(
                            "window.__x3FetchReject&&window.__x3FetchReject('$safeId','$msg')"
                        )
                    } else {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        window.evaluateJavascript(
                            "window.__x3FetchResolve&&window.__x3FetchResolve('$safeId',$code,$ok,'$b64')"
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "X3HubPageAgent"
        private const val BRIDGE = "X3Bridge"
        private const val MAX_HOPS = 3
        private const val WATCHDOG_MS = 90_000L

        /** 209 KB; read once for the process, not once per window. */
        @Volatile private var cachedBundle: String? = null

        fun bundle(context: Context): String? {
            cachedBundle?.let { return it }
            val loaded = runCatching {
                context.assets.open("page-agent.js").bufferedReader().use { it.readText() }
            }.getOrElse {
                Log.w(TAG, "page-agent.js missing from assets", it)
                null
            }
            cachedBundle = loaded
            return loaded
        }
    }
}
