package pub.miku.neuralstem.experimental.ncm

import android.util.Log

/**
 * ★NCM 播放的**分流决策器**。
 *
 * ## 为何需要"分流"
 * NCM 只是个**容器壳**，里面装的可能是多种格式：
 *
 * | 解密后魔数 | 实际格式 | Android 原生能播？ |
 * |---|---|---|
 * | `fLaC` | FLAC | ✅ 直接能播 |
 * | `ID3` / `0xFFEx` | MP3 | ✅ 直接能播 |
 * | `ftyp` + `av3a` | AVS3-P3 | ❌ 需自编译解码器 |
 * | `ftyp` + `mp4a`/`alac` | AAC / ALAC | ✅ 直接能播 |
 *
 * 实测：60 个 NCM 样本里 59 个是 av3a，**1 个是 flac**。
 * ⇒ "解出来一定是 AVS3" 是**错的**。若统一按 AVS3 处理，
 *   flac/mp3 那批会被白白送进解码器（甚至解码失败）。
 *
 * ## 三个分流结果
 * ```
 * Native(format, mime) → 解密后直接交给 ExoPlayer（带正确 MIME）
 * Avs3                 → 解密后再走 AVS3 解码链
 * Unsupported          → 抱错
 * ```
 */
object NcmPlaybackPlanner {

    private const val TAG = "NcmPlanner"

    /** 分流结果。 */
    sealed interface Plan {
        /**
         * 原生可播。
         *
         * @param format `flac` / `mp3` / `m4a`（用于选 MIME 与日志）
         * @param mime ExoPlayer 用的 MIME
         */
        data class Native(val format: String, val mime: String) : Plan

        /** 解出来的 m4a 里是 AVS3 → 需要再解码。 */
        data object Avs3 : Plan

        /** 无法处理。 */
        data class Unsupported(val reason: String) : Plan
    }

    /**
     * 把 [NcmDecoder.Decoded] 映射成分流结果。
     *
     * @param audioBytes 解密后的音频字节（用于探 m4a 内部的 codec）
     */
    fun decide(format: String, audioBytes: ByteArray): Plan {
        return when (format.lowercase()) {
            "flac" -> Plan.Native("flac", "audio/flac")
            "mp3" -> Plan.Native("mp3", "audio/mpeg")
            "m4a" -> {
                // ★m4a 里可能是 av3a，也可能是 mp4a/alac —— 必须探，不能猜。
                val codec = runCatching { Mp4CodecProbe.probeCodec(audioBytes) }.getOrNull()
                Log.i(TAG, "m4a 内部 codec = $codec")
                when (codec) {
                    "av3a" -> Plan.Avs3
                    "mp4a", "alac" -> Plan.Native("m4a", "audio/mp4")
                    null -> Plan.Unsupported("m4a 里探不到 codec")
                    else -> Plan.Unsupported("不支持的 codec: $codec")
                }
            }
            else -> Plan.Unsupported("未知格式: $format")
        }
    }
}
