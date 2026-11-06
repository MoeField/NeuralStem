#!/usr/bin/env bash
# ★边播 Gate 自动验收（2026-11-06）
#
# 验证什么：
#   1. 边播切到多轨后，Gate 进入 Starved **真的暂停了 StemMixer**（不是只暂停静音门面）；
#   2. 分离追上后 Gate 恢复，混音器心跳继续推进。
#
# 前置条件：
#   - 装的是 **debug 构建**（devtools 命令才生效），且 Spleeter 2-stem 模型已安装；
#   - 测试曲已放进 app 私有目录（脚本默认 files/edge_test.mp3）；
#   - 设备已授权 adb。
#
# 用法：
#   scripts/edge_gate_test.sh [serial] [歌曲绝对路径] [seek_ms]
#   scripts/edge_gate_test.sh ANDROID_SERIAL /data/user/0/pub.miku.neuralstem/files/edge_test.mp3 150000
#
# 取证：app 私有 files/edge_probe.txt（vivo 吞 logcat，所以写文件）

set -uo pipefail

ADB=${ADB:-/workspace/Library/Android/sdk/platform-tools/adb}
S=${1:-ANDROID_SERIAL}
SONG=${2:-/data/user/0/pub.miku.neuralstem/files/edge_test.mp3}
SEEK_MS=${3:-150000}
PKG=pub.miku.neuralstem
CMD_SVC="$PKG/.devtools.TestCommandService"

say() { printf '%s\n' "$*"; }
fail() { say "FAIL: $*"; exit 1; }

cmd() {
  local name=$1; shift
  local extra=()
  if [ $# -gt 0 ]; then extra=(--es a0 "$1"); fi
  "$ADB" -s "$S" shell am start-service -n "$CMD_SVC" --es cmd "$name" "${extra[@]}" >/dev/null 2>&1
}

probe() { "$ADB" -s "$S" shell "run-as $PKG cat files/edge_probe.txt" 2>/dev/null; }

wait_for() { # wait_for <pattern> <timeout_s> <label>
  local pat=$1 timeout=$2 label=$3 i
  for ((i = 0; i < timeout; i++)); do
    if probe | grep -q "$pat"; then say "  ✓ $label"; return 0; fi
    sleep 1
  done
  return 1
}

say "== 1. 清缓存 + 重启 App =="
"$ADB" -s "$S" shell am force-stop "$PKG"
"$ADB" -s "$S" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 3
cmd clearcache; sleep 1
probe | grep -q "clearCache" || fail "devtools/EdgeProbe 不可用（装的是 debug 构建吗？）"

say "== 2. 载入测试曲并播放 =="
cmd load "file://$SONG"; sleep 3
active=$("$ADB" -s "$S" shell dumpsys media.audio_flinger 2>/dev/null | grep -m1 "Tracks of which" | grep -o "[0-9]* are active")
say "  audio_flinger: ${active:-unknown}"

say "== 3. 启动边播分离（从 0）=="
cmd edge 0
wait_for "\[Mixer\] play" 40 "已切到多轨（Mixer play）" || fail "40s 内未切到多轨"
sleep 1

say "== 4. seek 到 ${SEEK_MS}ms（超出分离水位线）=="
cmd seek "$SEEK_MS"
wait_for "\[Mixer\] pause" 15 "Gate 已暂停混音器" || fail "Gate 未暂停混音器（P0-1 回归！）"
paused_active=$("$ADB" -s "$S" shell dumpsys media.audio_flinger 2>/dev/null | grep -m1 "Tracks of which" | grep -o "[0-9]* are active")
say "  暂停时 audio_flinger: ${paused_active:-unknown}"
sleep 1

say "== 5. 等分离追上 → Gate 恢复 =="
# 从“最后一次 pause”之后找 paused=false
start_line=$(probe | grep -n "\[Mixer\] pause" | tail -1 | cut -d: -f1)
ok=0
for i in $(seq 1 90); do
  if probe | tail -n +"$start_line" | grep -q "\[Gate\] paused=false"; then ok=1; break; fi
  sleep 1
done
[ "$ok" = 1 ] || fail "90s 内 Gate 未恢复"
sleep 5
tail=$(probe | tail -6)
say "$tail"
hb=$(echo "$tail" | grep -o "hb [0-9]*ms" | tail -1 | grep -o "[0-9]*")
if [ -n "${hb:-}" ] && [ "$hb" -gt "$SEEK_MS" ]; then
  say "  ✓ 恢复后混音器推进到 ${hb}ms"
else
  fail "恢复后混音器未推进（hb=${hb:-none}）"
fi

resumed_active=$("$ADB" -s "$S" shell dumpsys media.audio_flinger 2>/dev/null | grep -m1 "Tracks of which" | grep -o "[0-9]* are active")
say "  恢复后 audio_flinger: ${resumed_active:-unknown}"

say ""
say "PASS：Gate 暂停/恢复均真实作用到 StemMixer。"
