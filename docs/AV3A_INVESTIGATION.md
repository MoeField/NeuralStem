# AV3A / Audio Vivid 调查记录

> 调查日期：2026-11-18  
> 范围：AV3A 格式定位、FFmpeg 上游/OpenHarmony/Media3 的支持状态、GitHub 上 `av3a` 仓库的实际内容、许可与对 NeuralStem 的建议。  
> 结论只描述本次实际检查到的代码/文档；外部仓库后续可能变化。

## 1. 结论

1. **AV3A 不是平台私有/加密格式**，它是 AVS3-P3（Audio Vivid）音频，属于公开标准体系：行业标准 **GY/T 363-2023《三维声编解码及渲染》**；MP4 中 `av3a` 是标准 sample entry，不是 DRM。
2. **FFmpeg 上游没有 AV3A 解码器**。当前 master（`RELEASE = 8.0.git`）只有 AVS3 **视频**的 `libuavs3d` wrapper、`avs3` parser 和 AVS3 demux/mux；没有 `av3a` codec entry。
3. **OpenHarmony 的 FFmpeg patch 没有 AV3A decoder**。它加的是 `av3a_parser`、`av3adec` demuxer、MP4/TS muxer，以及 `AV_CODEC_ID_AVS3DA`；`libavcodec` 中没有 AV3A decoder。它可以把 AV3A 音轨**解封装/转封装**，不能单独把 AV3A 变成 PCM。
4. GitHub 上带 AV3A **解码**的项目，绝大多数依赖外部 native decoder：央视频 SO、UWA reference/`libav3adec`、`libarcdav3a`，或来源不明的二进制品。它们不是可以直接进 F-Droid 的自由解码器。
5. **NeuralStem 继续不随 APK 分发 AV3A 解码器**。若要支持，优先级应是“许可明确的独立 decoder”，而不是找某个 FFmpeg/Media3 fork 直接搬。

## 2. AV3A 是什么

- AV3A 是 **AVS3-P3 / Audio Vivid** 的音频码流/格式。
- 它支持 channel-based、object-based、scene-based/HOA 等三维声形态；公开资料可见 5.1/7.1/5.1.4/7.1.4、16/24-bit 等配置。
- `.av3a` 常见为裸 AATF 流；MP4 中则使用 `av3a` sample entry（`dca3` 配置 box）。
- “普通播放器打不开”的常见原因是缺少 decoder/container 支持，不是格式本身被加密。
- 格式标准公开，不等于 decoder/reference code 可以自由再分发；标准实施还可能涉及专利。

## 3. FFmpeg 上游

检查的源码：

- [`libavcodec/allcodecs.c`](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/allcodecs.c)
  - 与 AVS 相关的只有 `ff_libuavs3d_decoder`（AVS3-P2 **视频**）。
- [`libavcodec/Makefile`](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/Makefile)
  - `libuavs3d.o`
  - `avs3_parser.o`
  - 没有 `av3a` decoder/parser 的默认条目。
- [`libavformat/allformats.c`](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/allformats.c)
  - `avs3` demux/mux（视频裸流），不是 AV3A 音频。
- `libavformat/mov.c`
  - 当前上游没有 `av3a` / `dca3` 的 sample-entry 处理。

因此：

```text
标准 FFmpeg：AVS3-P2 视频有（libuavs3d）；AV3A 音频没有
```

定制 FFmpeg 仍可解 AV3A，但需要外部 decoder：

- UWA 文档的 `--enable-libav3adec` + `libav3adec`；
- 或 `WoKee/ffmpeg-av3a` 的 `libarcdav3a` wrapper；
- 或 `nilaoda/FFmpeg-Builds` 等预编译包。

## 4. OpenHarmony FFmpeg 实际内容

仓库：[openharmony/third_party_ffmpeg](https://gitee.com/openharmony/third_party_ffmpeg)

实际检查到的文件/宏：

```text
libavcodec/av3a_parser.c       # parser
libavcodec/av3a.h              # 表/结构
libavformat/av3adec.c          # demuxer
libavformat/rawenc.c           # av3a muxer
libavformat/mov.c/movenc.c     # MP4 av3a/dca3
libavformat/mpegts.c/mpegtsenc.c
BUILD.gn: CONFIG_AV3A_PARSER / OHOS_AV3A_DEMUXER
```

没有：

```text
libavcodec/av3a_decoder.c
CONFIG_AV3A_DECODER
AV3A 的 PCM 解码实现
```

`libavcodec/Makefile` 里只有：

```make
OBJS-$(CONFIG_AV3A_PARSER) += av3a_parser.o
```

`av3adec.c` 文件头写的是 `AV3A Demuxer`。  
所以它能做的是：

```text
识别 AV3A 音轨
切 AATF frame
从 TS/MP4 解封装
把 AV3A 重新封装成 MP4/TS
```

不能做的是：

```text
AV3A compressed frame -> PCM
```

### 能不能挪到 Android？

- 代码是 C，FFmpeg 主体 LGPL-2.1+；`av3a_parser.c`/`av3adec.c` 文件头也是 LGPL-2.1+。
- 从许可上，比抓出来的 `.so` 干净；可以作为 Android FFmpeg 的 parser/demux patch。
- 但它只解决“读得出来”，不解决“解得出来”；而且仍要处理 FFmpeg 版本差异、标准/专利问题。
- 对 NeuralStem 当前 Media3 架构，移植 parser/demux 也还要写 Media3 extractor；收益不足以抵消维护成本。

## 5. GitHub `av3a` 搜索的 27 个仓库

搜索：[GitHub search: av3a repositories](https://github.com/search?q=av3a&type=repositories)  
本次 API 查询 `total_count = 27`。它们不是 27 个解码器：

- **16 个与 AV3A 相关**
- **10 个无关课程/网站项目**
- **1 个 SEO 垃圾仓库**

### 5.1 解码器/参考实现（6）

| 仓库 | 内容 | 备注 |
|---|---|---|
| [nilaoda/av3a_decoder](https://github.com/nilaoda/av3a_decoder) | Python AV3A 解码器 | 已归档；README 提到源码泄露后失去意义 |
| [1254qwer/avs3a-rust](https://github.com/1254qwer/avs3a-rust) | 纯 Rust 解码器，裸流/MP4/PCM | MIT；仍在开发；模型/表另取 |
| [Zencok/baka-av3a](https://github.com/Zencok/baka-av3a) | 纯 Rust 解码器，MP4/渐进/CLI | GPL-3.0；模型外部 |
| [baiji6/MOLAN-AV3A](https://github.com/baiji6/MOLAN-AV3A) | 纯 C 解码器，MP4/M4A/裸流 | 基于 AVS3A_RMv3.3.1 reference；模型嵌入；NOASSERTION |
| [Dreamello/av3a](https://github.com/Dreamello/av3a) | UWA 参考 codec + 双耳渲染 + FFmpeg wrapper | reference lineage；无明确 license |
| [Rafa00127/av3a_dec](https://github.com/Rafa00127/av3a_dec) | MusicPlayer2 用 Windows DLL | reference 核心；模型外置；NOASSERTION |

### 5.2 FFmpeg/播放器集成/预编译包（9）

| 仓库 | 内容 |
|---|---|
| [wuxianlin/ijkplayer-av3a](https://github.com/wuxianlin/ijkplayer-av3a) | IJKPlayer fork + 预编译 `libavs3a_decoder.so`/renderer |
| [raddyfiy/av3a_ffmpeg_decoder](https://github.com/raddyfiy/av3a_ffmpeg_decoder) | 预编译 FFmpeg/mpv，可转码 AV3A |
| [WoKee/ffmpeg-av3a](https://github.com/WoKee/ffmpeg-av3a) | FFmpeg fork，含 `libarcdav3a` wrapper / parser |
| [DRX-Lab/Av3a-Decoder](https://github.com/DRX-Lab/Av3a-Decoder) | Python wrapper，调用 nilaoda exe + FFmpeg |
| [ConnorSu/MPV-FFmpeg-AVSP-AVS23-AV3A](https://github.com/ConnorSu/MPV-FFmpeg-AVSP-AVS23-AV3A) | AVS/DRA/AV3A 播放器整合包，主要是网盘分发 |
| [kokikoku/mpv_av3a](https://github.com/kokikoku/mpv_av3a) | GitHub Actions 构建 mpv Android + AV3A |
| [kokikoku/KODI_AV3A](https://github.com/kokikoku/KODI_AV3A) | GitHub Actions 构建 Kodi Android + nilaoda decoder |
| [qichenya/AV3A-Decode](https://github.com/qichenya/AV3A-Decode) | Web 批量转换器，内置带 `libAVS3AudioDec.so` 的 FFmpeg |
| [zmcity/ffmpeg-codec-player](https://github.com/zmcity/ffmpeg-codec-player) | macOS arm64 FFmpeg/mpv 集成，native C++ AV3A 库 |

### 5.3 制作/编码（1）

| 仓库 | 内容 |
|---|---|
| [fengluoxiao/ADM-to-AV3A](https://github.com/fengluoxiao/ADM-to-AV3A) | ADM/Atmos/360RA 等对象音频转 AV3A M4A / AC-4 制作工具 |

### 5.4 无关项目（10）

这些是课程作业、网站或名字碰巧包含 `av3a`：

- [Igor-mapurunga/AV3AWS](https://github.com/Igor-mapurunga/AV3AWS) — Node/Supabase 应用
- [JoaoVictorRabelo/Av3Americo](https://github.com/JoaoVictorRabelo/Av3Americo)
- [pmas98/AV3Algoritmos](https://github.com/pmas98/AV3Algoritmos) — 算法练习
- [Erickjonatthan/AV3ano](https://github.com/Erickjonatthan/AV3ano)
- [LucasGondimS/AV3AWSTESTE](https://github.com/LucasGondimS/AV3AWSTESTE)
- [moaneto/av3alzir](https://github.com/moaneto/av3alzir) — 空仓库
- [rafaelbarreira1/av3ambientes](https://github.com/rafaelbarreira1/av3ambientes) — Node 计算器课程项目
- [ismagoes/Av3Americo](https://github.com/ismagoes/Av3Americo)
- [caiopack/av3Ambientes](https://github.com/caiopack/av3Ambientes) — Express 课程项目
- [joao-alim/av3ambiental](https://github.com/joao-alim/av3ambiental) — 环境法课程网站

### 5.5 垃圾/SEO（1）

- [haron-user/5fe_av3a](https://github.com/haron-user/5fe_av3a) — SEO 外链文本，不是编解码项目

## 6. Media3 与 `rabbitknight/media` fork

官方 Media3：

- [developer.android.com/media/media3](https://developer.android.com/media/media3)
- [androidx/media](https://github.com/androidx/media)
- Google 的 AndroidX 媒体库集合：ExoPlayer、Transformer、MediaSession、UI、extractor、decoder。
- 默认 codec 走 Android MediaCodec；额外 codec 可用 FFmpeg extension。
- **官方 Media3 不支持 AV3A。**

`rabbitknight/media` 的 `av3a_1.4.1` 分支是 Media3 1.4.1 fork：

- 增加 `MimeTypes.AUDIO_AV3A`
- 增加 MPEG-TS `Av3aReader`
- `FfmpegLibrary` 把 AV3A 映射到 `libarcdav3a`
- `build_ffmpeg.sh` 开启 `--enable-libarcdav3a`
- JNI 链接 `libAVS3AudioDec`、`libav3a_binaural_render`
- `Mp4Box` 增加 `TYPE_av3a`，但我检查的 `Mp4Extractor`/`Track` 没有完整 `av3a` 建轨逻辑

结论：它是 **Media3 的 AV3A 接入参考**，依赖外部 native decoder，不是官方 Media3 能力，也不是自带 decoder 的完整实现。

## 7. 许可/可行性矩阵

| 路线 | 解码 | 许可风险 | F-Droid | 结论 |
|---|---|---|---|---|
| FFmpeg 上游 | ❌ | — | — | 只能 AVS3 视频 |
| OpenHarmony FFmpeg patch | ❌（仅 demux/mux/parser） | LGPL-2.1+ | 可用于 demux | 不能单独播放 |
| UWA reference / `libav3adec` | ✅ | Code Sharing Policy | ❌ | 不可再分发 |
| 央视频 SO（nilaoda/ijkplayer） | ✅ | 来源/许可不明 | ❌ | 不要打包 |
| `libarcdav3a`（WoKee/Media3 fork） | ✅ | 外部库、NOASSERTION | ❌ | 仅参考 |
| `1254qwer/avs3a-rust` | ✅ | MIT（模型另核） | 待审 | 最值得继续调查 |
| `Zencok/baka-av3a` | ✅ | GPL-3.0（模型另核） | 待审 | 可调查 |
| `baiji6/MOLAN-AV3A` | ✅ | NOASSERTION | ❌/待审 | 参考实现 lineage |

> 以上不是法律意见；标准实施可能涉及专利，模型/数据表也可能有单独许可。

## 8. 对 NeuralStem 的决定

- 继续 **不随 APK、也不随自建 F-Droid 源分发 AV3A 解码器**。
- 不引入：
  - 央视频 SO / `libAVS3AudioDec.so` / `libav3a_binaural_render.so`
  - `libarcdav3a`
  - 来源不明或泄露的 decoder 源码
- 若未来要做：
  1. 先选许可明确的独立 decoder（优先评估 `1254qwer/avs3a-rust` / `Zencok/baka-av3a`）；
  2. 单独核对模型/数值表/专利；
  3. 再决定 Media3 extractor/FFmpeg demux 的集成方式；
  4. 不把“能 demux”误认为“能 decode”。

## 9. 主要来源

- [GY/T 363-2023 标准页](https://std.samr.gov.cn/hb/search/stdHBDetailed?id=F51E928BFE570642E05397BE0A0A2D79)
- [GB/T 46271—2025（`av3a` sample entry）](https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=C68510AA44216CBD9AB36CB2E61B4170)
- [UWA AVS3-P3 文档](http://www.theuwa.com//upload/ueditor/file/20260528/1779953253856911/363955931ca98853a9b0b26a3c99fc32.pdf)
- [FFmpeg allcodecs.c](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/allcodecs.c)
- [FFmpeg libavcodec/Makefile](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/Makefile)
- [FFmpeg allformats.c](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/allformats.c)
- [OpenHarmony third_party_ffmpeg](https://gitee.com/openharmony/third_party_ffmpeg)
- [OpenHarmony PR 49](https://gitee.com/openharmony/third_party_ffmpeg/pulls/49/files)
- [OpenHarmony PR 128](https://gitee.com/openharmony/third_party_ffmpeg/pulls/128/files)
- [nilaoda/Blog discussion 81](https://github.com/nilaoda/Blog/discussions/81)
- [rabbitknight/media av3a_1.4.1](https://github.com/rabbitknight/media/tree/av3a_1.4.1)
- [官方 Media3](https://developer.android.com/media/media3)
