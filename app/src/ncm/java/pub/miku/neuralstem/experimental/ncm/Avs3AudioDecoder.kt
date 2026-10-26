package pub.miku.neuralstem.experimental.ncm

import android.util.Log
import java.io.Closeable

/**
 * ★AVS3-P3（av3a）音频解码器封装。
 *
 * ## 为什么需要
 * 用户 98% 的网易云 .ncm 解密出来是 **AVS3-P3 编码的 m4a**，
 * Android 的 `MediaExtractor` 完全认不出（`codec_tag_string = av3a`）。
 *
 * ## 底层
 * `Dreamello/av3a` 仓库提供了 Android 预编译库（无需自己编）：
 * - `libAVS3AudioDec.so`（324KB，核心解码器）
 * - `libVMFPlatForm.so`（185KB，平台层）
 *
 * 函数签名以**官方头文件**为准（不再靠反汇编猜）：
 * - `av3adecoder/avs3Decoder/include/avs3_decoder_interface.h`
 * - 常量定义：`av3adecoder/libavs3_common/avs3_cnst_com.h`
 * ```c
 * void* avs3_create_decoder(void);
 * void  avs3_destroy_decoder(void* dec);
 * int   parse_header(void* dec, unsigned char* in, int inLen, int isInitFrame,
 *                    int* lenConsumed, unsigned short* crc);
 * int   avs3_decode(void* dec, unsigned char* in, int inLen,
 *                   unsigned char* out, int* outLen, int* consumed);
 * ```
 *
 * ## ★返回值语义（最容易搞错的一点）
 * 官方常量：`AVS3_FALSE=0` / `AVS3_TRUE=1` / `AVS3_DATA_NOT_ENOUGH=2`。
 * `parse_header` 的文档注释也明确写着 `0: failed / 1: success / 2: data not enough`。
 * **1 才是成功**，`avs3_decode` 同理。别按「0 = OK」的直觉写判断。
 *
 * ## 使用
 * ```kotlin
 * Avs3AudioDecoder().use { dec ->
 *   dec.decodeFrame(frameBytes)   // → PCM16 LE，交织
 * }
 * ```
 */
class Avs3AudioDecoder : Closeable {

    private var handle: Long = 0
    /** 每次解码的 PCM 输出缓冲（复用，避免频繁分配）。 */
    private var outBuf: ByteArray? = null
    private val outInfo = IntArray(2)
    /** 失败次数（日志节流用）。 */
    private var failCnt = 0

    /** 解码器是否可用（库能否加载）。 */
    val available: Boolean

    init {
        available = runCatching { nativeInit() == 0 }.getOrDefault(false)
        if (!available) Log.w(TAG, "AVS3 解码库不可用（仅 arm64-v8a 有）")
    }

    /** 建解码器。返回是否成功。 */
    fun open(): Boolean {
        if (!available) return false
        handle = nativeCreate()
        Log.i(TAG, "open → handle=$handle")
        return handle != 0L
    }

    /**
     * ★按官方顺序初始化（`parse_header(init=1)` → `parse_header(init=0)`）。
     *
     * ## 为何要两次
     * 从 `avs3_dec_lib.c` 的官方封装可看出：
     * ```c
     * // Avs3DecoderLibCreate:
     * Avs3ParseBsFrameHeader(dec, hdr, 1, NULL);   // ← init=1：解析头+初始化
     * Avs3InitDecoder(dec, &fModel, path);
     * // Avs3DecoderLibParseHeader:
     * Avs3ParseBsFrameHeader(dec, hdr, 0, &crc);   // ← init=0：算 CRC
     * bytesPerFrame = ceil(bitsPerFrame / 8);
     * ```
     *
     * @return 0 成功；负数失败（-1 未 open / -2 native init 失败 / -99 异常）
     */
    fun initSequence(headerFrame: ByteArray): Int {
        if (handle == 0L) return -1
        val rc = runCatching { nativeInitSequence(handle, headerFrame) }
            .onFailure { Log.e(TAG, "initSequence EXCEPTION", it) }
            .getOrDefault(-99)
        Log.i(TAG, "initSequence rc=$rc")
        return rc
    }

    /**
     * ★初始化/解析帧头（单次）。
     *
     * ```c
     * int parse_header(h, data, len, isInitFrame, &lenConsumed, &crc);
     * ```
     *
     * ## 之前崩溃的根因（2026-10-25）
     * 靠反汇编猜签名时漏了第 6 参 `unsigned short* crc`，
     * 库往随机地址写 → 前 60 帧碰巧能跑，之后 `SIGSEGV fault addr 0x1202`。
     * 签名现已按官方头文件 `avs3_decoder_interface.h` 对齐。
     *
     * @param isInitFrame true = 初始化帧（sequence header）
     * @return 库的原始返回值：**0 = 失败，1 = 成功，2 = 输入不足**
     */
    fun parseHeader(frame: ByteArray, isInitFrame: Boolean = true): Int {
        if (handle == 0L) return -1
        val rc = runCatching { nativeParseHeader(handle, frame, if (isInitFrame) 1 else 0) }
            .onFailure { Log.e(TAG, "parseHeader EXCEPTION", it) }
            .getOrDefault(-99)
        return rc
    }

    /**
     * 解码一帧 av3a 码流。
     *
     * @param frame 裸 av3a 帧（不含 ADTS/MP4 头）
     * @return PCM16 LE 交织字节；失败返回 null
     */
    fun decodeFrame(frame: ByteArray): ByteArray? {
        if (handle == 0L) return null
        val cap = outBuf?.size ?: DEFAULT_OUT_CAP.also { outBuf = ByteArray(it) }
        val out = outBuf!!
        val n = runCatching {
            nativeDecode(handle, frame, frame.size, out, cap, outInfo)
        }.getOrElse {
            // ★别静默：把异常完整打出来（排查时唯一线索）
            Log.e(TAG, "decodeFrame EXCEPTION: ${it.javaClass.simpleName}: ${it.message}", it)
            return null
        }
        if (n < 0) {
            if (failCnt < 5) {
                Log.w(TAG, "decodeFrame rc=$n (frame=${frame.size}B outCap=$cap)")
                failCnt++
            }
            return null
        }
        // ★ n == 0 有两种可能，都**不是失败**：
        //   ① 成功但本帧无输出（首帧只含 sequence header 时 lenOut=0）；
        //   ② rc=DATA_NOT_ENOUGH，即输入不够 —— 说明 access unit 被切错了，
        //      native 侧会打 LOGW(inLen/consumed)，这里同样按「空帧」继续。
        //   顺序很重要：先判 n < 0（硬失败）再判 n == 0，别调过来。
        if (n == 0) return EMPTY
        // 若本次用满缓冲，下次扩容（应对高声道数）
        if (n >= cap) {
            outBuf = ByteArray(cap * 2)
            Log.i(TAG, "outBuf 扩容到 ${cap * 2}")
        }
        // ★把库实际消费的字节数留给调用方（诊断用）：
        //   avs3_decode 不校验码流，bytesPerFrame 完全由帧头比特率决定，
        //   所以「消费字节数 vs 喂进去的字节数」是判断帧边界的唯一信号。
        lastConsumed = outInfo[1]
        return out.copyOf(n)
    }

    /** 上一帧库实际消费的输入字节数（{@link decodeFrame} 后有效）。 */
    var lastConsumed: Int = 0
        private set

    /**
     * 源声道数（实测 12 = `MC_7_1_4` 空间音频）。
     *
     * 从解码器结构体里读 `numChansOutput` —— 官方库**没有导出 getConfig 符号**，
     * 只能按结构体偏移读（偏移是 static_assert 实测的，不是猜的，见 native 注释）。
     *
     * @return >0 声道数；-1 未知（尚未 `initSequence`，或值不合理）
     */
    fun sourceChannels(): Int {
        if (handle == 0L) return -1
        return runCatching { nativeGetChannels(handle) }.getOrDefault(-1)
    }

    /** 解码器输出的采样率（实测 44100）。@return >0；-1 未知 */
    fun sourceSampleRate(): Int {
        if (handle == 0L) return -1
        return runCatching { nativeGetSampleRate(handle) }.getOrDefault(-1)
    }

    /** 拿到 PCM 后，用这个判断是否需要重采样/声道转换。 */
    fun lastFrameBytes(): Int = outInfo[0]

    override fun close() {
        if (handle != 0L) {
            runCatching { nativeDestroy(handle) }
            handle = 0L
        }
    }

    private external fun nativeInit(): Int
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeParseHeader(handle: Long, frame: ByteArray, isInitFrame: Int): Int
    private external fun nativeInitSequence(handle: Long, headerFrame: ByteArray): Int
    private external fun nativeGetChannels(handle: Long): Int
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeDecode(
        handle: Long,
        inBytes: ByteArray,
        inLen: Int,
        outPcm: ByteArray,
        outCap: Int,
        outInfo: IntArray,
    ): Int

    companion object {
        private const val TAG = "Avs3Dec"
        /**
         * 默认输出缓冲大小。
         *
         * ## 为什么 256KB（而不是原来的 64KB）
         * 单帧 PCM 字节数 = `numChansOutput × frameLength × 2`（16bit LE）。
         * 本项目实际码流是 12 声道（帧头 channelNumIdx=10 → `CHANNEL_CONFIG_MC_7_1_4`）：
         * - frameLength=1024 → 12×1024×2 = 24,576 B
         * - frameLength=2048 → 12×2048×2 = 49,152 B
         *
         * 64KB 对 12ch/2048 只剩 15KB 余量，太紧；而规范里还有
         * `MC_22_2`（24 声道）：24×2048×2 = 98,304 B，64KB 直接溢出。
         * 直接给 256KB，余量足够容纳现有全部声道配置，代价只是一次分配。
         */
        private const val DEFAULT_OUT_CAP = 256 * 1024

        /**
         * “成功但无 PCM”的哨兵值（首帧 sequence header 返回这个）。
         *
         * 调用方应区分：「返回 EMPTY」= 正常，继续喂下一帧；
         * 「返回 null」= 真失败。
         */
        val EMPTY = ByteArray(0)

        /** 库是否已加载（加载一次即可）。 */
        @Volatile
        private var libLoaded = false

        init {
            // 先加载依赖的 libc++_shared（VMF 和 AudioDec 都要）
            runCatching { System.loadLibrary("c++_shared") }
            runCatching {
                System.loadLibrary("VMFPlatForm")
                System.loadLibrary("AVS3AudioDec")
                System.loadLibrary("neuralstem_avs3")
                libLoaded = true
                Log.i(TAG, "native libs loaded")
            }.onFailure { Log.e(TAG, "loadLibrary failed", it) }
        }
    }
}
