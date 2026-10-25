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
 * 仓库**没有头文件**，函数签名是从导出符号反汇编推出来的：
 * ```c
 * void* avs3_create_decoder(void);                       // 无参，返回 opaque
 * int   avs3_decode(void* dec, const void* in, int inLen,
 *                   void* out, int* outSize1, int* outSize2);
 *        // 返回 1 成功 / 0 参数无效 / 2 输入不足
 *        // *outSize1 = PCM16 字节数
 * void  avs3_destroy_decoder(void* dec);
 * ```
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
     * @return 0 成功
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
     *
     * @param isInitFrame true = 初始化帧（sequence header）
     * @return 库的原始返回值（0 = 成功）
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
        // ★ n == 0 是**正常情况**：首帧只是 sequence header，不产 PCM。
        //   实测：`avs3_decode` 返回 1（成功）但 *outSize1 = 0。
        if (n == 0) return EMPTY
        // 若本次用满缓冲，下次扩容（应对高声道数）
        if (n >= cap) {
            outBuf = ByteArray(cap * 2)
            Log.i(TAG, "outBuf 扩容到 ${cap * 2}")
        }
        return out.copyOf(n)
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
        /** 默认输出缓冲：8 声道 × 2048 帧 × 2 字节 = 32KB，留足余量。 */
        private const val DEFAULT_OUT_CAP = 64 * 1024

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
