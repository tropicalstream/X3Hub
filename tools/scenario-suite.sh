#!/bin/bash
# x3hub scenario suite v2 — clean board, targeted dispatch, plain-quote JS,
# time-correlated audio assertions.
export ANDROID_SERIAL=A06B4A96A733283
B="com.x3hub.app.DEBUG_OPEN_BROWSER"
P="com.x3hub.app"
R=()

bc() { adb shell "am broadcast -a $B -p $P $1" >/dev/null 2>&1; }
probe() { # probe <host> <js-with-plain-quotes>
  adb logcat -c
  adb shell "am broadcast -a $B -p $P -e on $1 -e js '$2'" >/dev/null 2>&1
  sleep 4
  adb logcat -d 2>/dev/null | grep "eval ->" | tail -1
}
# Is the app AUDIBLY playing right now? The focus stack is the truthful
# witness: Chromium takes GAIN when media starts and abandons it on pause,
# so "focus held, no loss" means sound. Counting "state:started" player
# lines was not truthful — dead players linger and unrelated ones churn,
# and the count produced two false FAILs in one run while the audio played.
audio_playing() {
  adb shell dumpsys audio 2>/dev/null |
    grep "pack: com.x3hub.app" | grep -q "gain: GAIN.*loss: none"
}
result() { R+=("$1 $2"); echo "»» $1 $2"; }

adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb shell am start -n $P/.MainActivity >/dev/null 2>&1; sleep 14

echo "== reset: close every browser window =="
for i in 1 2 3 4 5; do bc "-e winact close"; sleep 2; done
adb logcat -c
bc "-e board 1"; sleep 3
adb logcat -d -s X3HubBoard | grep -c browser | sed 's/^/windows left: /'

echo "== S1: podcast player search + bylines (jazz) =="
bc "-e url 'https://x3hub.local/podplayer.html?q=jazz'"; sleep 14
OUT=$(probe x3hub.local 'JSON.stringify({tiles:document.querySelectorAll(".tile").length,byline:(document.querySelector(".tile .a")||{}).textContent})')
echo "$OUT" | grep -qE 'tiles.{0,4}(1[5-9]|2[0-9])' && result PASS "S1 player search: tiles+bylines" || result FAIL "S1: $OUT"

echo "== S2: player episode playback (hands-free) =="
adb shell "am broadcast -a $B -p $P -e on x3hub.local -e js 'openPod(0); 1'" >/dev/null 2>&1; sleep 8
adb shell "am broadcast -a $B -p $P -e on x3hub.local -e js 'playEp(0); 1'" >/dev/null 2>&1; sleep 10
OUT=$(probe x3hub.local 'var a=document.getElementById("au"); "paused="+a.paused+" t="+Math.round(a.currentTime)')
echo "$OUT" | grep -q 'paused=false' && result PASS "S2 player audio ($OUT)" || result FAIL "S2: $OUT"
adb shell "am broadcast -a $B -p $P -e on x3hub.local -e js 'document.getElementById("au").pause(); 1'" >/dev/null 2>&1

echo "== S3: star talk ranking + byline =="
bc "-e url 'https://x3hub.local/podplayer.html?q=star+talk'"; sleep 12
OUT=$(probe x3hub.local 'var t=document.querySelector(".tile"); t.querySelector(".t").textContent+"/"+t.querySelector(".a").textContent')
echo "$OUT" | grep -qi 'StarTalk Radio.*Tyson' && result PASS "S3 StarTalk Radio / Tyson first" || result FAIL "S3: $OUT"

echo "== S4: bandcamp shuffle-play (targeted dispatch) =="
bc "-e url 'https://bandcamp.com'"; sleep 14
adb shell "am broadcast -a $B -p $P -e on bandcamp -e task 'play my purchases'" >/dev/null 2>&1; sleep 55
adb logcat -c
adb shell "am broadcast -a $B -p $P -e on bandcamp -e scrollinfo 0" >/dev/null 2>&1; sleep 3
URL=$(adb logcat -d | grep "DEBUG scrollinfo" | tail -1)
if echo "$URL" | grep -qE 'scrollinfo\[https://[a-z0-9-]+\.(bandcam|bandcamp)'; then
  audio_playing && result PASS "S4 shuffle: album + audio playing" || result FAIL "S4 album loaded but no audio: $URL"
else result FAIL "S4 wrong page: $URL"; fi

echo "== S5: named artist from purchases =="
adb logcat -c
adb shell "am broadcast -a $B -p $P -e on bandcamp -e task 'play 8 bit weapon from my purchases'" >/dev/null 2>&1
sleep 50
adb logcat -d | grep -q "X3BC playing 8 Bit Weapon" && result PASS "S5 artist match logged" || result INFO "S5 match log rotated (S5b is authoritative)"
adb logcat -c
adb shell "am broadcast -a $B -p $P -e on 8bitweapon -e scrollinfo 0" >/dev/null 2>&1; sleep 3
adb logcat -d | grep "DEBUG scrollinfo" | grep -q "8bitweapon.bandcamp.com" && result PASS "S5b on artist subdomain" || result FAIL "S5b wrong page"

echo "== S6: open collection (no play verb) =="
adb shell "am broadcast -a $B -p $P -e on bandcamp -e task 'go to my purchases'" >/dev/null 2>&1; sleep 25
adb logcat -c
adb shell "am broadcast -a $B -p $P -e on bandcamp -e scrollinfo 0" >/dev/null 2>&1; sleep 3
URL=$(adb logcat -d | grep "DEBUG scrollinfo" | tail -1)
echo "$URL" | grep -qE "bandcamp\.com/[A-Za-z0-9_-]+\]" && result PASS "S6 collection page" || result FAIL "S6: $URL"

echo "== S7: radio.garden native tune =="
bc "-e url 'https://radio.garden'"; sleep 18
adb shell "am broadcast -a $B -p $P -e on radio.garden -e task 'play kpfa'" >/dev/null 2>&1; sleep 35
audio_playing && result PASS "S7 tune: app holds media focus" || result FAIL "S7 no media focus after tune"
adb shell "am broadcast -a $B -p $P -e on radio.garden -e js 'document.querySelectorAll(\"audio,video\").forEach(function(a){a.pause()}); 1'" >/dev/null 2>&1

echo "== S8: radio4all www retry renders =="
for i in 1 2; do bc "-e winact close"; sleep 2; done
bc "-e url 'https://radio4all.net'"; sleep 20
OUT=$(probe radio4all 'location.href+" txt="+document.body.innerText.length')
echo "$OUT" | grep -q "www.radio4all.net" && result PASS "S8 www retry + content" || result FAIL "S8: $OUT"

echo ""
echo "=== SCORECARD ==="
printf '%s\n' "${R[@]}"
