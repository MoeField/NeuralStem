#!/usr/bin/env bash
set -euo pipefail
# 多轨+EQ+后台 混合 soak（TEST_HOOKS debug）
SERIAL="${SERIAL:-}"; ADB_BIN="${ADB_BIN:-adb}"
STEM_A="${STEM_A:-/sdcard/Music/CytusTest/who_am_i_44k.wav}"
STEM_B="${STEM_B:-/sdcard/Music/CytusTest/bullet_44k.wav}"
DURATION_SEC="${DURATION_SEC:-1800}"
OUT_DIR="${OUT_DIR:-stability-out/multi-$(date +%Y%m%d-%H%M%S)}"
[ -n "$SERIAL" ] || { echo "SERIAL required"; exit 1; }
ADB=("$ADB_BIN" -s "$SERIAL"); mkdir -p "$OUT_DIR"
"${ADB[@]}" logcat -c; "${ADB[@]}" logcat -v threadtime > "$OUT_DIR/logcat.txt" 2>&1 & LOG_PID=$!
trap 'kill "$LOG_PID" 2>/dev/null || true' EXIT
ensure() { "${ADB[@]}" shell pidof pub.miku.neuralstem >/dev/null 2>&1 || { "${ADB[@]}" shell am start -n pub.miku.neuralstem/.MainActivity >/dev/null 2>&1; sleep 2; }; }
send() { local c="$1"; shift; local a=(); local i=0; for x in "$@"; do a+=(--es "a$i" "$x"); i=$((i+1)); done; ensure; "${ADB[@]}" shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService --es cmd "$c" "${a[@]}" >/dev/null 2>&1 || true; }
snapshot() { { echo "=== $(date -Iseconds)"; "${ADB[@]}" shell dumpsys media_session 2>/dev/null | grep -E 'package=pub.miku|state=PlaybackState' | head -8 || true; } >> "$OUT_DIR/state.txt"; }
rand() { echo $((RANDOM % ($2 - $1 + 1) + $1)); }
end=$((SECONDS + DURATION_SEC)); ops=0; restarts=0; max_unmuted=0
# 只进入一次多轨，避免测试脚本自身制造 exit→inmt 高频切换。
ensure; send inmt "$STEM_A" "$STEM_B"; sleep 3
while [ "$SECONDS" -lt "$end" ]; do
  ensure
  case $((RANDOM % 11)) in
    0) send play ;;
    1) send pause ;;
    2) send seek $((RANDOM % 180000)) ;;
    3) send mtvol vocals "$( [ $((RANDOM%2)) -eq 0 ] && echo 0 || echo 1 )" ;;
    4) send mtvol accompaniment "$( [ $((RANDOM%2)) -eq 0 ] && echo 0 || echo 1 )" ;;
    5) send eqall -15 ;;
    6) send eqall 15 ;;
    7) send repeat one ;;
    8) send repeat all ;;
    9) send speed "$(awk -v x=$((RANDOM%5)) 'BEGIN{printf "%.1f",0.8+x*0.1}')" ;;
    10) send pitch $((RANDOM % 13 - 6)) ;;
  esac
  ops=$((ops+1))
  if [ $((ops % 10)) -eq 0 ]; then
    snapshot
    u=$("${ADB[@]}" shell dumpsys audio 2>/dev/null | grep 'state:started' | grep -c 'mutedState:none' || true)
    [ "$u" -gt "$max_unmuted" ] && max_unmuted=$u
  fi
  if [ $((ops % 120)) -eq 0 ]; then
    "${ADB[@]}" shell am force-stop pub.miku.neuralstem || true
    sleep 1; ensure; send inmt "$STEM_A" "$STEM_B"; sleep 2
    restarts=$((restarts+1))
  fi
  sleep "$(awk -v ms="$(rand 400 1200)" 'BEGIN{printf "%.3f",ms/1000}')"
done
send exitsmt
snapshot
{
  echo "ops=$ops restarts=$restarts duration=${DURATION_SEC}s max_unmuted_started=$max_unmuted"
  echo "fatal=$(grep -c 'FATAL EXCEPTION' "$OUT_DIR/logcat.txt" || true)"
  echo "anr=$(grep -c 'ANR in' "$OUT_DIR/logcat.txt" || true)"
  echo "player_error=$(grep -cE '播放失败|ERROR_CODE_' "$OUT_DIR/logcat.txt" || true)"
  echo "heartbeats=$(grep -c 'StemMixer: heartbeat' "$OUT_DIR/logcat.txt" || true)"
  echo "multi_loads=$(grep -c 'loadMultiTrack(mix)' "$OUT_DIR/logcat.txt" || true)"
  grep 'StemMixer: heartbeat' "$OUT_DIR/logcat.txt" | tail -5 || true
} > "$OUT_DIR/summary.txt"
cat "$OUT_DIR/summary.txt"; echo "out=$OUT_DIR"
