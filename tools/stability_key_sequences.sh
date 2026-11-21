#!/usr/bin/env bash
set -euo pipefail
# 关键序列 smoke（TEST_HOOKS debug 构建）
# SERIAL=xxx TEST_FILE=/sdcard/... AV3A_FILE=/sdcard/... ./tools/stability_key_sequences.sh

SERIAL="${SERIAL:-}"; ADB_BIN="${ADB_BIN:-adb}"
TEST_FILE="${TEST_FILE:-/sdcard/Music/CytusTest/who_am_i_44k.wav}"
STEM_A="${STEM_A:-/sdcard/Music/CytusTest/who_am_i_44k.wav}"
STEM_B="${STEM_B:-/sdcard/Music/CytusTest/bullet_44k.wav}"
AV3A_FILE="${AV3A_FILE:-/sdcard/Music/FromTF/坂本龍一 - Merry Christmas Mr. Lawrence.m4a}"
[ -n "$SERIAL" ] || { echo "SERIAL required"; exit 1; }
ADB=("$ADB_BIN" -s "$SERIAL")
OUT="stability-out/key-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
pass=0; fail=0

ensure() { "${ADB[@]}" shell pidof pub.miku.neuralstem >/dev/null 2>&1 || { "${ADB[@]}" shell am start -n pub.miku.neuralstem/.MainActivity >/dev/null 2>&1; sleep 2; }; }
send() { local c="$1"; shift; local a=(); local i=0; for x in "$@"; do a+=(--es "a$i" "$x"); i=$((i+1)); done; ensure; "${ADB[@]}" shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService --es cmd "$c" "${a[@]}" >/dev/null 2>&1 || true; }
mark() { "${ADB[@]}" logcat -c; }
check() { local name="$1"; shift; local err; err=$( "${ADB[@]}" logcat -d 2>/dev/null | grep -cE 'FATAL EXCEPTION|ANR in|播放失败|ERROR_CODE_' || true); if [ "$err" = 0 ]; then echo "PASS $name"; pass=$((pass+1)); else echo "FAIL $name errors=$err"; fail=$((fail+1)); "${ADB[@]}" logcat -d > "$OUT/$name.log"; fi; }
wait_ms() { sleep "$(awk -v ms="$1" 'BEGIN{printf "%.3f",ms/1000}')"; }

run() { # run <name> <fn>
  local name="$1"; shift; mark; "$@"; check "$name"; }

seq_basic() {
  send load "file://$TEST_FILE" basic; wait_ms 1200
  send play; wait_ms 800; send pause; wait_ms 500
  send seek 30000; wait_ms 500; send play; wait_ms 800
  send next; wait_ms 500; send prev; wait_ms 500
}
seq_multi() {
  send inmt "$STEM_A" "$STEM_B"; wait_ms 2500
  send mtvol vocals 0; send mtvol accompaniment 0; wait_ms 2500
  send mtpos; wait_ms 300; send exitsmt; wait_ms 800
}
seq_eq() {
  send load "file://$TEST_FILE" eq; wait_ms 800; send play; wait_ms 500
  send eqall -15; wait_ms 500; send eq false
  send eqall 15; wait_ms 500; send eq false
}
seq_av3a() {
  send load "file://$AV3A_FILE" av3a; wait_ms 1500
}
seq_restart() {
  "${ADB[@]}" shell am force-stop pub.miku.neuralstem; sleep 1
  ensure; wait_ms 1000; send load "file://$TEST_FILE" restart; wait_ms 1200
}

run basic seq_basic
run multi seq_multi
run eq seq_eq
run av3a seq_av3a
run restart seq_restart
echo "RESULT pass=$pass fail=$fail out=$OUT"
[ "$fail" = 0 ]
