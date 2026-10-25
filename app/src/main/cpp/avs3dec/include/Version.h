#pragma once
#include "Instruction.h"

/*
 * ★本地修正（2026-10-26）：官方原版这里用了两个独立的 `#ifdef` 块
 * （`#ifdef linux` 和 `#ifdef ARCH_AARCH64`），两段里都定义了
 * VER_MAJOR/VER_MINOR/... 和 GENERIC_VERION，并各自展开一次
 * `GENERIC_VERION(1,0,0,6)`。
 *
 * 问题：`Instruction.h` 在 aarch64 上只会定义 ARCH_AARCH64（不会定义 linux），
 * 本来不会冲突；但 **Android NDK 的 clang 为 aarch64 目标时会预置
 * `linux` 宏**，于是两个分支同时成立 → `VERSION_AVS3AudioDec_1_0_0_6`
 * 重定义，编译直接报错。
 *
 * 修法：把第二个 `#ifdef ARCH_AARCH64` 改成 `#elif defined(ARCH_AARCH64)`。
 * 这样两段互斥，语义完全不变（同一个版本号，只是不会重复展开），
 * 也保留了原文件想在 x86_64/aarch64 上都能用的意图。
 */
#if defined(linux) || defined(ARCH_AARCH64)
#ifdef __cplusplus
extern "C" {
#endif

#define VER_MAJOR				1
#define VER_MINOR				0
#define VER_RELEASE				0
#define VER_BUILD				6

#define GENERIC_VERION(Major, Minor, Release, Build)\
void VERSION_AVS3AudioDec_##Major##_##Minor##_##Release##_##Build(){ }

GENERIC_VERION(1, 0, 0, 6)

#ifdef __cplusplus
}
#endif
#endif