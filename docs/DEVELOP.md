# 开发指南（NeuralStem）

> 面向想改功能、修 bug、接手的贡献者。内部的开发流水/设备记录不随公开仓库发布，这里只保留可复用的架构、构建、测试和踩坑信息。

## 1. 代码结构

```
app/src/main/java/pub/miku/neuralstem/
├── player/       PlayerViewModel、PlaybackService、MultiTrackSession、
│                 StemMixer、SonicStage、MixClock、TrackMixerPanel
├── spike/        分离：SeparationRepository、SeparationScreen、SpikeViewModel、
│                 EdgeSeparationSession、Spleeter4Dsp、Spleeter4/2 推理
├── playlist/     PlaylistStore、PlaylistViewModel、PlaylistScreen
├── ui/           AppShell（导航/页面组装）
├── export/       StemExporter（MediaStore 导出）
├── devtools/     TestCommandService（仅调试用）
└── ...
app/src/main/cpp/ native：sherpa-onnx / ONNX Runtime 链接；可选 AVS3 解码不在仓库内
app/src/test/     JVM 单元测试（可离线跑）
bench/            性能 benchmark 模块
scripts/          设备辅助脚本（用 ANDROID_SERIAL 指定设备）
docs/             本目录
```

## 2. 构建与运行

环境：

- JDK 21（CI 与本地一致）
- Android SDK **37**（compileSdk 37）
- NDK `30.0.16248370`、CMake `4.1.2`
- 首次构建需要联网加载 Gradle/依赖；之后可 `--offline`

```bash
unset ANDROID_PREFS_ROOT     # AGP 9.x 下避免与 ANDROID_USER_HOME 冲突
./gradlew :app:assembleDebug --offline
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest --offline
```

模型不在仓库里：运行 App → **设置 → 模型库** 下载 Spleeter/UVR 模型。没有模型时播放、EQ、波形仍可用，分离页会提示缺模型。

### 可选 AVS3 / NCM

AVS3-P3 参考实现受 UWA 政策限制，**不随仓库分发**，release APK 也不包含 `libAVS3AudioDec.so` / `libVMFPlatForm.so`。默认构建不依赖它：

- 没有参考实现时，`avs3dec` 目标跳过，桥接回退 `dlopen`；其余播放/分离功能不受影响。
- 需要本地调试 AVS3 时自己执行 `tools/fetch-avs3.sh`，产物已被 `.gitignore` 挡住，不要提交。

## 3. 核心架构与不变量

### 单一真源

- `SessionStateMachine` 持有播放队列、多轨会话、分离任务、EQ 可用性等状态；`PlayerViewModel` 只做 UI 映射和命令转发。
- `PlaybackService` 持有真正的 `ExoPlayer` 实例；MediaSession 始终认 `singlePlayer`（多轨时它是主轨）。
- 多轨的真实声音在 `StemMixer`（一个 AudioTrack），门面 `singlePlayer` 静音；通知/锁屏/后台仍走 MediaSession。

### 单轨 / 多轨切换

- 进入多轨前先关跟随轨、停门面；退出/换曲前必须 `beforeSetQueue()`（退多轨），否则新旧两首会同时响。
- 多轨下速度/音高只下发给 `StemMixer`（`SonicStage`），不要改门面的 playback parameters；退出多轨或换单曲时再把 UI 参数下发给单轨播放器。
- 多轨位置用 `MixClock` 计算（`StemMixer` 实际播放位置），不要用静音门面的 position 做续播/退出。

### 分离

- `SeparationRepository` 是分离任务唯一入口；每次新任务先取消并 join 旧任务，用 `generation` 防止旧回调污染新状态。
- 边分离边播使用**预分配整曲 WAV 文件**（`EdgeSeparationSession`）；缓存目录按 `sourceHash + engine` 隔离，带 `MANIFEST.json`。
- 读缓存前校验 manifest 与 WAV 长度（`44 + totalFrames*channels*2`）；不能只信 manifest。
- 边分离的起始帧/水位线要持久化；seek 到分离起点之前属于“灰区”，要明确提示无声，不要假装已分离。
- 导出必须绑定 `ownerUri`（本次分离属于哪首曲），换曲后旧结果不可导出。

### 歌单 / 队列 / 续播

- `PlaylistStore` 写文件用临时文件 + `fsync` + rename，损坏 JSON 保留副本，不能直接覆盖成空。
- 进度 `lastPlayedUri` 存**队列/歌单里的原始 uri**，不要存 NCM/AVS3 的内部临时 uri，否则冷启动匹配不到。
- `loadAndPlay` 单曲导入默认清空 `currentListId` 与队列；队列内部换曲（`playQueue`/next/T-6）传 `clearQueue=false`。
- 歌单删除/排序要通过 `onTrackRemoved`/`onTrackMoved` 同步 `SessionStateMachine` 的队列；当前曲按 uri 重新定位。
- SAF 失效曲保留在歌单并标记「需重新授权」，不要自动删除；删除文件夹歌单时释放 tree grant。

### 导出

- MediaStore 插入 `IS_PENDING=1`，写完置 0；失败/异常必须删除 pending 行。
- 同名目标先删旧行再插入，避免自动生成 `(1)`。
- 导出内容不带 EQ/音量处理。

## 4. 常见坑（改代码前先看）

- **Media3 状态抖动**：门面 ExoPlayer 的 `isPlaying/STATE_*` 在多轨时不代表真实声音；以 `StemMixer`/Service 的 mixer 状态为准。
- **换曲竞态**：切歌/取消分离时旧任务的回调可能晚到；所有回调先检查 `generation`/owner 是否仍是当前。
- **AudioTrack underrun**：心跳里同时看 `underruns partial/empty`、`framePos` 是否推进；不要用阻塞读把渲染线程卡死。
- **缓存误命中**：缓存目录必须带 sourceHash/engine；manifest 的 totalFrames 与 WAV 文件长度都要校验。
- **变速/变调**：多轨时只发 `CMD_SET_SPEED_PITCH`；不要在 Service/Controller 再叠防抖；新命令必须加入 `ALL_COMMANDS`。
- **旋转/重建**：Activity 重建后从 Service extras 恢复 currentUri/stems/speed/pitch；队列与 currentListId 由 `playlistStore` 反查恢复；自动化 intent 加 `rememberSaveable` consumed 标记。
- **导出/SAF**：MediaStore pending 回滚、导出 owner、SAF 持久授权（`takePersistableUriPermission`）、tree grant 释放，四件事缺一不可。
- **测试环境**：设备媒体音量可能被 ROM 锁在 0（“能响”不等于音量正常）；用 `dumpsys media_session`、`input keyevent`、`cmd media_session dispatch` 和 `files/edge_probe.txt` 做黑盒验证。

## 5. 测试与调试

```bash
# JVM
./gradlew :app:testDebugUnitTest --offline

# 设备命令（先设置 ANDROID_SERIAL）
adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService \
  --es cmd load --es a0 "file:///sdcard/Music/test.mp3" --es a1 "test"

adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService \
  --es cmd edge --es a0 0

adb shell dumpsys media_session | grep -m1 PlaybackState
adb shell cmd media_session dispatch pause
adb shell input keyevent 127

# debug 取证（debug APK）
adb shell run-as pub.miku.neuralstem cat files/edge_probe.txt
```

常用 logcat tag：`PlayerVm`、`SepRepo`、`EdgeSession`、`SepScreen`、`StemMixer`、`PlaybackService`、`StemExporter`。

回归 checklist：

- 单曲导入 → 播放/暂停/seek/变速/变调；
- 分离（2 轨/4 轨）→ 自动切多轨 → 音量/静音/独奏 → 退出多轨回原曲；
- 边分离边播 → seek 到灰区 → 取消/重试；
- 旋转/后台/Activity 重建；
- 歌单播放 → 下一首/上一首 → 删除/排序 → 冷启动续播；
- 导出（2 轨/4 轨）→ MediaStore 无 pending 残留、无 `(1)` 重复；
- release：`versionCode/versionName` 递增、APK 无 UWA `.so`、`apksigner verify`。

## 6. 发布

版本号规则：`versionCode` 严格递增；`versionName` 用 `vX.Y.Z` tag 驱动。

CI（`.github/workflows/android-ci.yml`）需要四个 Repository Secrets：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

本地无密钥时 release 会回退 debug 签名，只能测试、不可发布。发布前：

```bash
./gradlew :app:assembleRelease
unzip -l app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep -E 'libAVS3|libVMF'   # 应为空
apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

推送 `vX.Y.Z` tag 后 CI 会跑单测并上传 Release APK。

## 7. 已知限制

- AVS3/NCM 参考解码器不入库；没有它时相关格式跳过。
- 真实来电的音频焦点丢失没有自动化模拟（代码路径已单独测过焦点请求/丢弃）。
- 0.71× 等非 1× 速度的后台长播：门面 ExoPlayer 可能先到 ENDED，混音器还在收尾；前台播放正常，后台生命周期仍需观察。
- Release 当前关闭 R8；`TestCommandService` / `TestPanelActivity` / `SpikeActivity` 已移入 `src/debug`，release manifest 不含这些入口。进一步收紧包体需另开 R8 分支（注意反射与 `Avs3Bridge`）。
