/*
 * ★本地占位版本：代替 Dreamello/av3a 仓库里的 av3adecoder/libavs3_debug/avs3_debug.h
 * （2026-10-26）
 *
 * ## 为什么可以这样
 * 全工程只有 `common/fd_spectrum_shaping_com.c` 一个文件 `#include "avs3_debug.h"`，
 * 而且该文件**一处 ASLOG_* 宏都没用到**（grep 实证：只匹配到 include 那一行本身）。
 * 也就是说这是一个「顺手写上、实际没用」的 include。
 *
 * 官方的 avs3_debug 模块提供的是桌面端的日志/进程快照工具（ASLOG_Init /
 * ASLOG_UpdateProcSnap / ASLOG_ErrorNotify 等），Android 上既不适用也不需要。
 *
 * ## 为什么不直接删掉那行 include
 * 删了会改动上游源码，将来对照官方仓库 diff 时会多出无谓差异。
 * 这里放一个空头，既不改动上游文件，又能让编译通过；
 * 万一以后真有人用到 ASLOG_*，会因为找不到符号而立刻暴露（而不是静默失效）。
 *
 * 如果将来需要真正的调试日志，把官方 `libavs3_debug/avs3_debug.{c,h}` 补进来，
 * 用 -I 把本文件的优先级降下去即可（本文件放在 common/ 下，仅当官方目录不存在时才被找到）。
 */

#ifndef AVS3_DEBUG_H_LOCAL_STUB
#define AVS3_DEBUG_H_LOCAL_STUB

/* 故意不定义任何宏：确保「有人真用到 ASLOG_*」时编译立刻报错。 */

#endif /* AVS3_DEBUG_H_LOCAL_STUB */
