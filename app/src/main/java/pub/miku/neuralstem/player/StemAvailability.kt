package pub.miku.neuralstem.player

import java.io.File

/**
 * ★「这条 stem 还在磁盘上吗」的**单一真源**（2026-11-01）。
 *
 * ## 为什么需要它（用户实报）
 * 清理缓存后，分离开关**仍然可以打开** —— 但打开后没有声音。
 *
 * 根因：`PlayerViewModel.lastStems` 是**内存里的 `File` 引用**，而
 * `SeparationRepository.clearCache()` 只删磁盘目录、**完全不通知 VM**：
 *
 * ```
 * separate()        → lastStems = [vocals.wav, accompaniment.wav]
 * clearCache()      → 磁盘目录删掉（lastStems 仍非空）
 * 开关 toggle(true) → 判「非空」→ 放行 → loadMultiTrack → 文件不存在 → 无声
 * ```
 *
 * ## 为什么必须在**使用处**校验，而不是在清理时通知
 * 缓存至少有三种消失方式，**没有一种会通知到这里**：
 * 1. 分离页 / 设置页的「清理缓存」按钮
 * 2. `Avs3PcmCache.evictIfNeeded()` 的 LRU 淘汰
 * 3. 系统清理（存储空间不足等）
 *
 * 所以唯一可靠的点是**每次要用之前**问一句「文件还在不在」。
 *
 * ## 抽成对象的原因
 * 原本这段逻辑内联在 `PlayerViewModel.loadMultiTrack` 里，
 * 依赖 Android Context 无法单测。抽出来后可以 JVM 单测覆盖
 * （见 `StemAvailabilityTest`）。
 */
object StemAvailability {

    /** 过滤掉磁盘上已不存在的 stem，保持原有顺序。 */
    fun alive(
        stems: List<Triple<String, String, File>>,
    ): List<Triple<String, String, File>> = stems.filter { it.third.exists() }

    /**
     * 是否还有**全部** stem 可用。
     *
     * 用于「开关该不该点亮」这类只需要布尔判断的场合。
     */
    fun allAlive(stems: List<Triple<String, String, File>>?): Boolean =
        !stems.isNullOrEmpty() && stems.all { it.third.exists() }
}
