# F-Droid 分发评估 / 可信依赖 spike

> 目标：判断能否进入官方 F-Droid 主库；结论会随 spike 结果更新。

## 结论摘要

- 官方主库：**有可行技术路径**，但需要先消除 tracked 预编译原生库、验证 Debian 构建服务器工具链，并处理模型许可。
- 自建 F-Droid 兼容源：**最省事**，可保留现有签名和升级路径。
- IzzyOnDroid：可作为第三方渠道，需 Fastlane 元数据、release 签名和其 2026 AI 政策评估。

## 关键发现

### sherpa-onnx AAR

旧 `app/libs/sherpa-onnx-1.13.8.aar` 与 JitPack 固定 tag 的 AAR 内容完全一致：

- 坐标：`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`
- AAR URL：`https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v1.13.8/sherpa-onnx-v1.13.8.aar`
- SHA-256：`633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
- 本地旧 AAR 与 JitPack AAR `cmp` 完全一致。

F-Droid 收录政策把 JitPack.io 列为可信 Maven 来源，因此这条依赖链可以替代 tracked AAR。

### 原生 .so

spike 分支已删除：

- `app/libs/sherpa-onnx-1.13.8.aar`
- `app/src/main/cpp/sherpa-libs/*/libsherpa-onnx-c-api.so`
- `app/src/main/cpp/ort-libs/*/libonnxruntime.so`

改为 Gradle 任务 `extractSherpaNativeLibs` 在构建时从 JitPack AAR 解包到
`app/build/native-deps/jni/<abi>`；CMake 只从构建目录链接。源码仓库不再保存这些二进制。

仅保留：

- `app/src/main/jniLibs/arm64-v8a/libc++_shared.so`（Android NDK 产物）
- `gradle/wrapper/gradle-wrapper.jar`（Gradle wrapper 正常组成）

### Debian / Docker

`fdroid/Dockerfile` + `fdroid/docker-compose.yml` 提供 Debian trixie 构建环境：

- JDK 21
- Android SDK 37.0 / build-tools 36（37 不可用时回退）
- NDK 30.0.16248370
- CMake 4.1.2
- fdroidserver 2.4.2
- SDK/NDK/Gradle 使用 Docker named volume 持久化

用法：

```bash
cd fdroid
docker compose run --rm builder
```

若 Docker Desktop 无法 bind mount 外置卷，把源码 rsync 到用户目录后：

```bash
SRC_DIR=/Users/you/NeuralStem-docker docker compose run --rm builder
```

## 仍待解决

- Debian 容器内 release 构建是否完整通过（R8/lint/打包）。
- `fdroid build` 用 fdroiddata 配方是否接受 AGP 9.5/Gradle 9.8/API 37 preview。
- Fastlane 元数据、fdroiddata 描述。
- 模型许可与默认下载代理（UVR 权重许可不统一；`api.gitproxy.dev` 可能触发 NonFreeNet 标记）。
- 官方签名切换导致 GitHub → F-Droid 无法原地升级。
