package pub.miku.neuralstem.player.pcm

/**
 * 共享 PCM 解码的源身份。
 *
 * 只做纯数据，不依赖 Android Context；由调用方把 Uri/File 解析成稳定 key。
 * 波形和分离必须使用同一个 [cacheKey]，否则会各自解码。
 */
data class SourceDescriptor(
    /** 稳定内容标识：建议 `pcm-v1|scheme|uri/path|size|lastModified`。 */
    val cacheKey: String,
    val displayName: String = "",
    val kind: Kind = Kind.OTHER,
    val sizeBytes: Long? = null,
    val lastModifiedMs: Long? = null,
    /** 实际可解码位置：file path 或 content uri。 */
    val location: String? = null,
) {
    enum class Kind {
        LOCAL_FILE,
        CONTENT_URI,
        RAW_BYTES,
        WAV_FILE,
        OTHER,
    }

    /**
     * WAV 直接随机读，不需要 PCM cache。
     * content:// 没有 size 时不做缓存，避免无法稳定失效。
     */
    val pcmCacheable: Boolean
        get() = when (kind) {
            Kind.WAV_FILE, Kind.OTHER -> false
            Kind.CONTENT_URI -> sizeBytes != null
            Kind.LOCAL_FILE, Kind.RAW_BYTES -> true
        }
}
