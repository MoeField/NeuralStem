#!/usr/bin/env bash
set -euo pipefail

# 状态稳定性 soak（TEST_HOOKS 构建使用）
# 用法：
#   SERIAL=xxx TEST_URI='content://...' DURATION_SEC=7200 ./tools/stability_soak.sh
# 需要：debug/TEST_HOOKS 构建、已导入可播放曲目；会 force-stop 多次。
# 产出：stability-out/<timestamp>/{logcat.txt,media_session.txt,audio.txt,summary.txt}

SERIAL="${SERIAL:-}"
DURATION_SEC="${DURATION_SEC:-120}"
TEST_URI="${TEST_URI:-}"
INTERVAL_MIN_MS="${INTERVAL_MIN_MS:-100}"
INTERVAL_MAX_MS="${INTERVAL_MAX_MS:-1500}"
OUT_DIR="${OUT_DIR:-stability-out/$(date +%Y%m%d-%H%M%S)}"
ADB_BIN="${ADB_BIN:-adb}"

if [ -z "$SERIAL" ]; then
  echo "SERIAL 未设置（adb devices）" >&2
  exit 1
fi
if ! command -v "$ADB_BIN" >/dev/null; then
  echo "找不到 adb: $ADB_BIN" >&2
  exit 1
fi

ADB=("$ADB_BIN" -s "$SERIAL")
mkdir -p "$OUT_DIR"

echo "soak: ${DURATION_SEC}s, out=$OUT_DIR, uri=${TEST_URI:-<none>}"
"${ADB[@]}" logcat -c
"${ADB[@]}" logcat -v threadtime > "$OUT_DIR/logcat.txt" 2>&1 &
LOG_PID=$!
trap 'kill "$LOG_PID" 2>/dev/null || true' EXIT

ensure_app() {
  if ! "${ADB[@]}" shell pidof pub.miku.neuralstem >/dev/null 2>&1; then
    "${ADB[@]}" shell am start -n pub.miku.neuralstem/.MainActivity >/dev/null 2>&1 || true
    sleep 2
  fi
}

send() { # send <cmd> [a0] [a1]
  local cmd="$1"; shift
  ensure_app
  local args=()
  local i=0
  for a in "$@"; do args+=(--es "a$i" "$a"); i=$((i+1)); done
  "${ADB[@]}" shell am start-service \
    -n pub.miku.neuralstem/.devtools.TestCommandService \
    --es cmd "$cmd" "${args[@]}" >/dev/null 2>&1 || true
}

snapshot() {
  {
    echo "=== $(date -Iseconds) media_session"
    "${ADB[@]}" shell dumpsys media_session 2>/dev/null | head -120 || true
    echo "=== audio"
    "${ADB[@]}" shell dumpsys audio 2>/dev/null | grep -E 'AudioPlaybackConfiguration|player piid|state:' | head -80 || true
  } >> "$OUT_DIR/state.txt"
}

rand_ms() {
  local min="$1" max="$2"
  echo $((min + RANDOM % (max - min + 1)))
}

end=$((SECONDS + DURATION_SEC))
ops=0
restarts=0
while [ "$SECONDS" -lt "$end" ]; do
  case $((RANDOM % 14)) in
    0) send play ;;
    1) send pause ;;
    2) send seek $((RANDOM % 180000)) ;;
    3) send next ;;
    4) send prev ;;
    5) send repeat "$( [ $((RANDOM%3)) -eq 0 ] && echo all || ([ $((RANDOM%2)) -eq 0 ] && echo one || echo seq) )" ;;
    6) send speed "$(awk -v x=$((RANDOM%5)) 'BEGIN{printf "%.1f",0.8+x*0.1}')" ;;
    7) send pitch $((RANDOM % 13 - 6)) ;;
    8) send eq "$( [ $((RANDOM%2)) -eq 0 ] && echo true || echo false )" ;;
    9) send edge $((RANDOM % 180000)) ;;
    10) send exitsmt ;;
    11) send edgecancel ;;
    12) if [ -n "$TEST_URI" ]; then send load "$TEST_URI" fuzz; fi ;;
    13)
      # 定期 force-stop + 冷启动，验证恢复
      if [ $((RANDOM % 6)) -eq 0 ]; then
        "${ADB[@]}" shell am force-stop pub.miku.neuralstem || true
        sleep 1
        "${ADB[@]}" shell am start -n pub.miku.neuralstem/.MainActivity >/dev/null 2>&1 || true
        sleep 2
        ensure_app
        restarts=$((restarts+1))
      fi
      ;;
  esac
  ops=$((ops+1))
  if [ $((ops % 10)) -eq 0 ]; then snapshot; fi
  sleep "$(awk -v ms="$(rand_ms "$INTERVAL_MIN_MS" "$INTERVAL_MAX_MS")" 'BEGIN{printf "%.3f", ms/1000}')"
done

snapshot
{
  echo "ops=$ops restarts=$restarts duration=${DURATION_SEC}s"
  echo "fatal=$(grep -c 'FATAL EXCEPTION' "$OUT_DIR/logcat.txt" || true)"
  echo "anr=$(grep -c 'ANR in' "$OUT_DIR/logcat.txt" || true)"
  echo "player_error=$(grep -cE '播放失败|ERROR_CODE_' "$OUT_DIR/logcat.txt" || true)"
  echo "waveform_error=$(grep -cE 'waveform extraction failed|PcmDecode:.*no audio track' "$OUT_DIR/logcat.txt" || true)"
  echo "heartbeats=$(grep -c 'StemMixer: heartbeat' "$OUT_DIR/logcat.txt" || true)"
  echo "rejects=$(grep -cE 'reject AVS3|AV3A' "$OUT_DIR/logcat.txt" || true)"
  echo "last_heartbeats:"
  grep 'StemMixer: heartbeat' "$OUT_DIR/logcat.txt" | tail -5 || true
} > "$OUT_DIR/summary.txt"

cat "$OUT_DIR/summary.txt"
echo "done: $OUT_DIR"
