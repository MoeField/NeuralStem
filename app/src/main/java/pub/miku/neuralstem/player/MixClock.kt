package pub.miku.neuralstem.player

/**
 * ★2026-11-10（B2）：多轨混音器位置的**本地插值**（纯函数）。
 *
 * ## 为什么需要
 * 多轨的真源是 `StemMixer`（自己的 AudioTrack），而 `MediaController` 指向的
 * `singlePlayer` 只是静音门面；它的 `currentPosition` 不可用（实测与 mixer 差 1.3s，
 * 变速后按 1×/speed 比例放大）。B2 前 `exitMultiTrack()` 用门面位置当续播点，
 * 1.4× 播 30s 会回退约 12s。
 *
 * Service 每 200ms 把 mixer 位置写进 extras；这里用“最后一次位置 + 单调时钟外推”
 * 得到当前真实位置，供 UI 进度与退出续播共用。
 *
 * 纯 JVM 对象 → 可直接单测。
 */
object MixClock {

    /**
     * @param baseMs 上次收到的 mixer 位置（毫秒）
     * @param atRealtimeMs 收到该位置的 `elapsedRealtime()`；<=0 表示还没收到
     * @param nowRealtimeMs 当前 `elapsedRealtime()`
     * @param speed 混音器当前速度倍率（2× 时 1 秒走 2 秒媒体时间）
     * @param playing 混音器是否在播
     * @param durationMs 曲长；0/未知时只保证非负、不夹上界
     */
    fun positionMs(
        baseMs: Long,
        atRealtimeMs: Long,
        nowRealtimeMs: Long,
        speed: Float,
        playing: Boolean,
        durationMs: Long,
    ): Long {
        val duration = durationMs.coerceAtLeast(0L)
        if (!playing || atRealtimeMs <= 0L) {
            return if (duration > 0L) baseMs.coerceIn(0L, duration) else baseMs.coerceAtLeast(0L)
        }
        val spd = speed.coerceIn(0.05f, 8f)
        val elapsed = (nowRealtimeMs - atRealtimeMs).coerceAtLeast(0L) * spd
        val pos = baseMs + elapsed.toLong()
        return if (duration > 0L) pos.coerceIn(0L, duration) else pos.coerceAtLeast(0L)
    }
}
