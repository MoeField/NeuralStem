# 许可边界（Licensing Boundary）

> 本文件说明哪些内容属于本项目原创 MIT、哪些是第三方、哪些**绝不能**进仓库。

## 1. 本项目原创（MIT）

仓库根目录 `LICENSE` 为 MIT。以下均为原创代码：

- Kotlin/Compose 应用层：播放器、多轨、EQ、波形、分离档位、边分离边播、歌单/导出等。
- 分离与流式播放实现：`StreamingSeparator`、`SegmentedSeparator`、`StemPcmRingBuffer`、`StemPcmDataSource` 等。
- JNI 胶水层：`app/src/main/cpp/sherpa_spike.c`、`avs3_audio_bridge.c` 等。
- NCM 模块 `app/src/ncm/`：NCM 容器解析/解密/导入/播放的**自写格式互操作实现**，默认不编译。
- `app/src/main/java/.../player/Avs3Bridge.kt`：main 源集到可选 NCM 源集的反射桥，原创 MIT。

## 2. AVS3-P3 参考实现（不在仓库）

AVS3-P3（`av3a`）的 UWA 参考实现受 **UWA Code Sharing Policy** 限制，**不随本仓库分发**：

- `app/src/main/cpp/avs3dec/`（参考源码）
- `app/src/main/jniLibs/arm64-v8a/libAVS3AudioDec.so`
- `app/src/main/jniLibs/arm64-v8a/libVMFPlatForm.so`
- 自编译产物 `libavs3self.so` / `libneuralstem_avs3.so`

这些路径已被 `.gitignore` 挡住。本地调试需要时，用 `tools/fetch-avs3.sh` 自行获取，**不要提交**。

`app/src/ncm/.../Avs3AudioDecoder.kt` 等 AVS3 封装是**本项目原创的接口/封装代码**，不是 UWA 参考实现源码；它只在 `-Pwith_ncm=true` 且本地有参考库时才可用。

## 3. 第三方运行时/模型

- sherpa-onnx：Apache-2.0
- ONNX Runtime：MIT
- AndroidX / Media3 / Kotlin：Apache-2.0
- 分离模型权重：各自许可，**不入库**，用户在 App 内下载；商用/分发前需逐个核对。

详见根目录 [`NOTICE`](../NOTICE)。

## 4. 严禁提交

- UWA AVS3 参考源码/预编译库（路径见上）
- `cleanroom/`、`report/`、`HANDOFF.md`、`doc/` 等内部资料（公开仓库不需要）
- `keystore.properties`、`*.jks`、`local.properties`
- 测试音频/模型文件
- 任何没有明确 MIT/兼容许可的第三方实现

仓库已提供 `scripts/check-license-boundary.sh`，CI 会在构建前检查 tracked 文件是否包含上述禁止路径。
