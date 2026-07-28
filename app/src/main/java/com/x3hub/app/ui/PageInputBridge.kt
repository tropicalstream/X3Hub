package com.x3hub.app.ui

/**
 * The page-input half of the on-screen keyboard, ported from SmartView.
 *
 * Two problems, one fix. The X3 has no usable system IME — it renders into
 * the raw 1280×480 framebuffer, so it spans BOTH eyes at the wrong scale
 * and cannot be dismissed by looking at it. And a WebView will raise that
 * IME the moment any page focuses a field, which the page does on its own
 * and which our own in-page search does deliberately.
 *
 * So the system keyboard is actively suppressed and this JS reports focus
 * to the host instead, which raises the glasses' own cursor-driven
 * keyboard. Typing then comes back in through these entry points rather
 * than through an InputConnection.
 */
object PageInputBridge {

    const val NAME = "X3Input"

    /**
     * Injected once per document. Everything is namespaced __x3* and guarded
     * so a second injection is a no-op — the host injects on both page start
     * and page finish, because a script running during parse must find the
     * hooks already there.
     */
    val JS: String = """
        (function(){
          if (window.__x3InputHooked) return;
          if (!document.documentElement) return;
          window.__x3InputHooked = true;

          function isInput(el){
            return el && (el.tagName==='INPUT' || el.tagName==='TEXTAREA' || el.isContentEditable);
          }
          // page-agent drives inputs itself and is voice-driven — never raise
          // the keyboard for its own panel.
          function inAgent(el){
            try { var r = window.__x3AgentRoot && window.__x3AgentRoot();
                  return !!(r && el && r.contains(el)); } catch(e){ return false; }
          }
          try {
            var style = document.createElement('style');
            // Also kills scrollbars: at 170px wide a scrollbar is a
            // meaningful slice of the readable area.
            style.textContent = '[data-x3-active="1"]{outline:2px solid #7FDBFF!important;outline-offset:2px!important;}' +
              '::-webkit-scrollbar{display:none!important;width:0!important;height:0!important;}' +
              '*{scrollbar-width:none!important;}';
            document.documentElement.appendChild(style);
          } catch(e) {}

          function markActive(el){
            document.querySelectorAll('[data-x3-active="1"]').forEach(function(n){
              if (n !== el) n.removeAttribute('data-x3-active');
            });
            try { el.setAttribute('data-x3-active','1'); } catch(_){}
          }
          function remember(el){
            if (isInput(el) && !inAgent(el)){
              window.__x3ActiveInput = el;
              markActive(el);
              try { $NAME.onInputFocus(typeof el.value === 'string' ? el.value : (el.textContent || '')); } catch(_){}
            }
          }
          function activeInput(){
            var el = window.__x3ActiveInput;
            if (!isInput(el) || !document.contains(el)) el = document.activeElement;
            if (!isInput(el)) return null;
            window.__x3ActiveInput = el;
            markActive(el);
            try { el.focus({preventScroll:true}); } catch(e){ try { el.focus(); } catch(_){} }
            return el;
          }
          // Frameworks track value through the prototype setter and their own
          // _valueTracker; a plain assignment leaves React's state stale and
          // the field reverts on the next render.
          function setNativeValue(el, value){
            var old = typeof el.value === 'string' ? el.value : '';
            var proto = Object.getPrototypeOf(el);
            var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
            if (!desc && el instanceof HTMLInputElement) desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
            if (!desc && el instanceof HTMLTextAreaElement) desc = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
            if (desc && desc.set) desc.set.call(el, value); else el.value = value;
            if (el._valueTracker){ try { el._valueTracker.setValue(old); } catch(_){} }
          }
          function notify(el){
            try { el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:el.value})); }
            catch(e){ el.dispatchEvent(new Event('input',{bubbles:true})); }
            el.dispatchEvent(new Event('change',{bubbles:true}));
          }
          function caret(el){
            var s = (typeof el.selectionStart === 'number') ? el.selectionStart : (el.value||'').length;
            var e = (typeof el.selectionEnd === 'number') ? el.selectionEnd : s;
            return [s, e];
          }

          document.addEventListener('focusin', function(e){ remember(e.target); }, true);
          document.addEventListener('click', function(e){ if (isInput(e.target)) remember(e.target); }, true);
          document.addEventListener('focusout', function(e){
            if (isInput(e.target)) { try { $NAME.onInputBlur(); } catch(_){} }
          }, true);

          window.__x3Defocus = function(){
            var el = window.__x3ActiveInput || document.activeElement;
            if (isInput(el)) { try { el.blur(); } catch(_){} }
            window.__x3ActiveInput = null;
          };
          window.__x3Insert = function(text){
            var el = activeInput(); if (!el) return;
            if (el.isContentEditable){
              try { el.focus({preventScroll:true}); } catch(_){}
              if (!document.execCommand || !document.execCommand('insertText', false, text)){
                el.textContent = (el.textContent||'') + text;
              }
              el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));
              return;
            }
            if (typeof el.value !== 'string') return;
            var c = caret(el), s = c[0], e = c[1], v = el.value;
            setNativeValue(el, v.slice(0,s) + text + v.slice(e));
            var pos = s + text.length;
            try { el.selectionStart = el.selectionEnd = pos; } catch(_){}
            notify(el);
          };
          window.__x3Backspace = function(){
            var el = activeInput(); if (!el) return;
            if (el.isContentEditable){
              if (!document.execCommand || !document.execCommand('delete', false)){
                el.textContent = (el.textContent||'').slice(0,-1);
              }
              el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward'}));
              return;
            }
            if (typeof el.value !== 'string') return;
            var c = caret(el), s = c[0], e = c[1], v = el.value, nv, pos;
            if (s !== e){ nv = v.slice(0,s) + v.slice(e); pos = s; }
            else if (s > 0){ nv = v.slice(0,s-1) + v.slice(s); pos = s - 1; }
            else return;
            setNativeValue(el, nv);
            try { el.selectionStart = el.selectionEnd = pos; } catch(_){}
            notify(el);
          };
          window.__x3Clear = function(){
            var el = activeInput(); if (!el) return;
            if (el.isContentEditable){ el.textContent=''; el.dispatchEvent(new InputEvent('input',{bubbles:true})); return; }
            if (typeof el.value !== 'string') return;
            setNativeValue(el, ''); notify(el);
          };
          window.__x3MoveCaret = function(d){
            var el = activeInput(); if (!el || typeof el.selectionStart !== 'number') return;
            var len = (el.value||'').length;
            el.selectionStart = el.selectionEnd = Math.max(0, Math.min(len, el.selectionStart + d));
          };
          window.__x3Enter = function(){
            var el = activeInput(); if (!el) return;
            ['keydown','keypress','keyup'].forEach(function(t){
              el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
            });
            if (el.form){ try { el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); } catch(e){} }
          };
        })();
    """.trimIndent()
}
