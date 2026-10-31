#!/usr/bin/env bash
# ★唯一真源：所有「易变数字」都从这里取，文档里不许写死结果。
#
# ## 为什么存在（2026-10-27）
# 之前 `.memory.md` / `HANDOFF.md` / `冷启动入口.md` 各自维护了一份「状态快照」，
# 里面写死了 HEAD、测试数、已发布版本。三份全腐烂了 —— 实际 HEAD 是 02ef766、
# 325 测试、已发 v0.2.4，而三份文档还写着 0565de7 / 308 / v0.2.3。
#
# 危害不只是"数字不好看"：一个按文档仔细读完的人，会因此**得出错误结论**
# （比如以为还没发版、以为 SAF 导入还没验）。手写的易变数字必然腐烂，这是机制问题。
#
# ## 用法
#   ./scripts/status.sh          # 快速（不跑测试，<1s）
#   ./scripts/status.sh --full   # 完整（跑 JVM 单测，约 1-2 分钟）
#
# 文档里只写「运行 ./scripts/status.sh」，**不要**粘贴它的输出。

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

FULL=0
[ "${1:-}" = "--full" ] && FULL=1

echo "=== NeuralStem 状态（$(date '+%Y-%m-%d %H:%M')）==="
echo

# ---------- Git ----------
echo "[Git]"
echo "  分支      : $(git rev-parse --abbrev-ref HEAD)"
echo "  HEAD      : $(git log --oneline -1)"
printf "  最近 tag  : "
git describe --tags --abbrev=0 2>/dev/null || echo "(无)"
printf "  tag 在 HEAD: "
if git describe --exact-match --tags HEAD >/dev/null 2>&1; then
    echo "是（$(git describe --exact-match --tags HEAD)）"
else
    echo "否（HEAD 尚未打 tag）"
fi

# 与远端比对
#
# 注意：本仓库 `main` **没有配置 upstream**（历史命令都是 `git push origin main`，
# 没带 `-u`）。所以不能直接依赖 `@{upstream}`，回退到 origin/main。
UPSTREAM=$(git rev-parse --abbrev-ref '@{upstream}' 2>/dev/null || echo "origin/main")
if git rev-parse --verify "$UPSTREAM" >/dev/null 2>&1; then
    ahead=$(git rev-list --count "$UPSTREAM..HEAD" 2>/dev/null || echo 0)
    behind=$(git rev-list --count "HEAD..$UPSTREAM" 2>/dev/null || echo 0)
    echo "  对比远端  : $UPSTREAM"
    echo "  待推送    : $ahead 个 commit"
    echo "  落后远端  : $behind 个 commit"
else
    echo "  待推送    : （找不到 $UPSTREAM，先 git fetch）"
fi

# 工作区
dirty=$(git status --porcelain | wc -l | tr -d ' ')
if [ "$dirty" = "0" ]; then
    echo "  工作区    : 干净"
else
    echo "  工作区    : $dirty 个改动未提交"
    git status --porcelain | head -10 | sed 's/^/              /'
fi

# 合规：AVS3 绝不能入库
leak=$(git ls-files | grep -icE 'avs3dec/|libAVS3AudioDec|libVMFPlatForm|libavs3self' || true)
if [ "$leak" = "0" ]; then
    echo "  AVS3 入库  : 0（合规 ✅）"
else
    echo "  AVS3 入库  : $leak 个文件 ⚠️ 违规！UWA 会员制授权，不可再分发"
fi
echo

# ---------- 版本 ----------
echo "[版本]"
grep -E 'versionCode =|versionName =' app/build.gradle.kts 2>/dev/null \
    | sed 's/^ *//' | sed 's/^/  /'
echo

# ---------- 构建产物 ----------
echo "[构建产物]"
found=0
for f in app/build/outputs/apk/release/app-universal-release.apk \
         app/build/outputs/apk/debug/app-universal-debug.apk; do
    if [ -f "$f" ]; then
        found=1
        size=$(ls -la "$f" | awk '{printf "%.1f MB", $5/1048576}')
        when=$(ls -la "$f" | awk '{print $6, $7, $8}')
        echo "  $size  $when  $f"
    fi
done
[ "$found" = "0" ] && echo "  （尚未构建）"
echo

# ---------- 测试 ----------
if [ "$FULL" = "1" ]; then
    echo "[JVM 单测]（跑中，约 1-2 分钟）"
    out=$(./gradlew app:testDebugUnitTest -Pwith_ncm=true --console=plain 2>&1)
    echo "$out" | grep -E 'tests completed|BUILD (SUCCESSFUL|FAILED)' | sed 's/^/  /'
    echo
    echo "[默认构建单测]（不含 NCM）"
    out2=$(./gradlew app:testDebugUnitTest --console=plain 2>&1)
    echo "$out2" | grep -E 'tests completed|BUILD (SUCCESSFUL|FAILED)' | sed 's/^/  /'
else
    echo "[JVM 单测]  未运行（加 --full 才跑）"
fi
echo

# ---------- 设备 ----------
echo "[设备]"
ADB="${ADB:-/Volumes/SA500/Library/Android/sdk/platform-tools/adb}"
if [ -x "$ADB" ]; then
    # 可能同时挂着多台（鼠标机 + 测试机）。
    # 用 ANDROID_SERIAL=xxx 指定；否则优先选装了本 App 的那台。
    if [ -n "${ANDROID_SERIAL:-}" ]; then
        targets="$ANDROID_SERIAL"
    else
        targets=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
    fi
    if [ -z "${targets:-}" ]; then
        echo "  （无设备）"
    else
        for serial in $targets; do
            ver=$("$ADB" -s "$serial" shell dumpsys package pub.miku.neuralstem 2>/dev/null \
                  | grep -oE 'versionName=[0-9.]+' | head -1 | cut -d= -f2)
            [ -z "$ver" ] && continue     # 没装本 App 的设备跳过（避免噪声）
            dbg=$("$ADB" -s "$serial" shell run-as pub.miku.neuralstem ls files/ >/dev/null 2>&1 \
                  && echo "可调试" || echo "不可调试(release)")
            echo "  $serial: v$ver  $dbg"
        done
    fi
else
    echo "  （adb 不在 $ADB）"
fi
