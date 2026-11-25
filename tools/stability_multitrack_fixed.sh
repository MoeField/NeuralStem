#!/usr/bin/env bash
set -euo pipefail
# 多轨固定基线 soak：EQ 全段 = EQ_DB、每轨音量 = VOL。
# 目的：长稳态播放 + seek/变速变调/repeat + 周期 force-stop 重启；
# 少量切换 EQ/音量后立即恢复，覆盖切换路径但不改变基线与判定。
SERIAL="${SERIAL:-}"; ADB_BIN="${ADB_BIN:-adb}"
STEM_A="${STEM_A:-/sdcard/Music/CytusTest/who_am_i_44k.wav}"
STEM_B="${STEM_B:-/sdcard/Music/CytusTest/bullet_44k.wav}"
DURATION_SEC="${DURATION_SEC:-7200}"
EQ_DB="${EQ_DB:--15}"
VOL="${VOL:-1}"
RESTART_EVERY="${RESTART_EVERY:-240}"
OUT_DIR="${OUT_DIR:-stability-out/mt-fixed-$(date +%Y%m%d-%H%M%S)}"
[ -n "$SERIAL" ] || { echo "SERIAL required"; exit 1; }
ADB=("$ADB_BIN" -s "$SERIAL"); mkdir -p "$OUT_DIR"
"${ADB[@]}" logcat -c; "${ADB[@]}" logcat -v threadtime > "$OUT_DIR/logcat.txt" 2>&1 & LOG_PID=$!
trap 'kill "$LOG_PID" 2>/dev/null || true' EXIT
ensure() { "${ADB[@]}" shell pidof pub.miku.neuralstem >/dev/null 2>&1 || { "${ADB[@]}" shell am start -n pub.miku.neuralstem/.MainActivity >/dev/null 2>&1; sleep 2; }; }
send() { local c="$1"; shift; local a=(); local i=0; for x in "$@"; do a+=(--es "a$i" "$x"); i=$((i+1)); done; ensure; "${ADB[@]}" shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService --es cmd "$c" "${a[@]}" >/dev/null 2>&1 || true; }
snapshot() { { echo "=== $(date -Iseconds)"; "${ADB[@]}" shell dumpsys media_session 2>/dev/null | grep -E 'package=pub.miku|state=PlaybackState' | head -8 || true; } >> "$OUT_DIR/state.txt"; }
rand() { echo $((RANDOM % ($2 - $1 + 1) + $1)); }
end=$((SECONDS + DURATION_SEC)); ops=0; restarts=0; max_unmuted=0
ensure; send inmt "$STEM_A" "$STEM_B"; sleep 3
send eqall "$EQ_DB"
send mtvol vocals "$VOL"; send mtvol accompaniment "$VOL"
echo "baseline: eq=$EQ_DB vol=$VOL duration=${DURATION_SEC}s out=$OUT_DIR" >> "$OUT_DIR/summary.txt"
while [ "$SECONDS" -lt "$end" ]; do
  ensure
  case $((RANDOM % 12)) in
    0) send play ;;
    1) send pause ;;
    2) send seek $((RANDOM % 180000)) ;;
    3) send mtvol vocals "$VOL" ;;
    4) send mtvol accompaniment "$VOL" ;;
    5) send eqall "$EQ_DB" ;;
    6) send repeat one ;;
    7) send repeat all ;;
    8) send speed "$(awk -v x=$((RANDOM%5)) 'BEGIN{printf "%.1f",0.8+x*0.1}')" ;;
    9) send pitch $((RANDOM % 13 - 6)) ;;
    10) send eqall 15; send eqall "$EQ_DB" ;;   # 短暂切到另一端再恢复，覆盖切换路径
    11) send mtvol vocals 0; send mtvol vocals "$VOL" ;;
  esac
  ops=$((ops+1))
  if [ $((ops % 10)) -eq 0 ]; then
    snapshot
    u=$("${ADB[@]}" shell dumpsys audio 2>/dev/null | grep 'state:started' | grep -c 'mutedState:none' || true)
    [ "$u" -gt "$max_unmuted" ] && max_unmuted=$u
  fi
  if [ $((ops % RESTART_EVERY)) -eq 0 ]; then
    "${ADB[@]}" shell am force-stop pub.miku.neuralstem || true
    sleep 1; ensure; send inmt "$STEM_A" "$STEM_B"; sleep 2
    send eqall "$EQ_DB"; send mtvol vocals "$VOL"; send mtvol accompaniment "$VOL"
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
  echo "eq_fail=$(grep -cE 'EqController.*fail|setBandLevel.*fail' "$OUT_DIR/logcat.txt" || true)"
  grep 'StemMixer: heartbeat' "$OUT_DIR/logcat.txt" | tail -5 || true
} > "$OUT_DIR/summary.txt"
echo '--- summary'
cat "$OUT_DIR/summary.txt"; echo "out=$OUT_DIR"
