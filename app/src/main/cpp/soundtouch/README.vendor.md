# SoundTouch（内置源码）

本目录为 **SoundTouch 2.4.1** 的内置源码副本，用于替换 media3 内置的 Sonic，
解决「频繁变速变调 → Sonic 预填充饥饿 → StuckPlayerDetector 报 `ERROR_CODE_TIMEOUT`」问题。

## 来源

- 官方仓库：https://codeberg.org/soundtouch/soundtouch
- 标签/版本：**2.4.1**（`include/SoundTouch.h` 中 `SOUNDTOUCH_VERSION "2.4.1"`）
- 获取方式：`git clone --depth 1 https://codeberg.org/soundtouch/soundtouch.git`
- 裁剪：仅保留运行所需的 `include/` 与 `source/SoundTouch/` 中的核心文件
  （文件清单参照官方 `source/Android-lib/jni/Android.mk`）。

## 许可证（★ 必须保留）

**GNU Lesser General Public License v2.1（LGPL-2.1）**，完整文本见 [COPYING.TXT](COPYING.TXT)。

> Copyright (c) Olli Parviainen

按 LGPL 要求：
- 本目录的 LICENSE（`COPYING.TXT`）**必须随分发保留**。
- 本项目以**动态链接**方式使用本库（编译为 `libneuralstem_soundtouch.so`），
  满足 LGPL 的“可替换库”要求。
- 若需闭源商业分发，官方另提供商业授权（见上方官网）。

## 构建

由 [`../../../CMakeLists.txt`](../CMakeLists.txt) 的 `soundtouch` 目标从源码编译，
再与 JNI 包装 `soundtouch_jni.cpp` 一起编成 `neuralstem_soundtouch` 共享库。

> 注：`cpu_detect_x86.cpp` / `sse_optimized.cpp` / `mmx_optimized.cpp` 为 x86 优化，
> 在 ARM 上 `SOUNDTOUCH_ALLOW_X86_OPTIMIZATIONS` 被 `#undef`（见 `include/STTypes.h`），
> 编译为空实现，属正常。
