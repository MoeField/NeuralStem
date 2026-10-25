package pub.miku.neuralstem.experimental.ncm

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * NCM 容器格式的**互操作性解析**实现（源码级，默认不编译）。
 *
 * ============================ 性质声明（必读） ============================
 * 这是一个**格式互操作性**实现，依据开源社区（ncmdump 系列）对网易云音乐
 * `.ncm` 容器格式的公开分析编写。它不是对本项目主线功能的扩展，
 * **默认不参与编译、不打包、不进主流程 UI**（见 app/build.gradle.kts 的
 * `-Pwith_ncm=true` 开关）。
 *
 * 合规提示：绕过 DRM 在多数法域可能构成「规避技术措施」。请仅对
 * **你自己已购买/已下载**的音乐文件在本地使用，**不要导出、不要传播**。
 * ==========================================================================
 *
 * ## 格式概述（依据公开分析）
 * ```
 * CTENFDAM (8B 魔数) | 2B | keyLen(4B LE) | keyData(keyLen, 每字节 XOR 0x64)
 *   | metaLen(4B LE) | metaData(AES-128-ECB 加密的 JSON)
 *   | CRC(4B) | 5B | imgLen(4B LE) | imgData | audioData(RC4 加密)
 * ```
 * - 元数据：AES-128-ECB 解密（密钥为客户端硬编码值）
 * - 音频：RC4 流密码解密；密钥由元数据里的 `key` 字段做 XOR/变换得到
 *
 * ## 本实现约束（刻意的）
 * - **只在内存中解密**，返回字节数组；**不写文件、不导出**
 * - 不做批量/持久化
 * - 仅在 `WITH_NCM` 构建中存在
 */
object NcmDecoder {

    private val TAG = "NcmDecoder"

    /**
     * 解码链路的分步诊断缓冲。
     *
     * ## 为何要这个
     * vivo/Android16 会吞 app 的 logcat（坑 52），而解码是多步黑盒
     * （AES 解密钥 → 去前缀 → KSA → RC4 解音频）—— 出错时看不出哪一步不对。
     * 把中间量写进 result 文件，一次性看到全部关键字节。
     *
     * 仅 debug 诊断用（线程不安全）。
     */
    private val diagBuf = StringBuilder()

    private fun diag(line: String) {
        diagBuf.append(line).append('\n')
        android.util.Log.i(TAG, line)
    }

    /** 读取并清空诊断缓冲（供 [NcmVerifier] 写文件）。 */
    fun drainDiagnostics(): String = diagBuf.toString().also { diagBuf.clear() }

    private fun ByteArray.hex(n: Int = 16): String =
        take(n).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /** 客户端硬编码的 AES-128 密钥（公开分析所得）。 */
    private val CORE_KEY = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57,
    )

    private val META_PREFIX = byteArrayOf(
        0x23, 0x35, 0x34, 0x4C, 0x79, 0x65, 0x72, 0x54,
        0x65, 0x61, 0x6D, 0x2D, 0x4E, 0x43, 0x4E, 0x50,
    )

    // ---------------- 格式常量（对照 taurusxin/ncmdump + xiSage/ncmdump-net 核定） ----------------

    /** key 区 XOR 掩码。 */
    private const val KEY_XOR_MASK = 0x64

    /** 元数据区 XOR 掩码（★与 key 区不同，旧实现错用了 0x64）。 */
    private const val META_XOR_MASK = 0x63

    /** key 解密后的固定前缀（17 字节）。 */
    private const val KEY_PREFIX = "neteasecloudmusic"

    /** 元数据（明文）的固定前缀，后面跟 Base64。 */
    private const val META_PREFIX_TEXT = "163 key(Don't modify):"

    /** 元数据 AES 解密后的 JSON 前缀。 */
    private const val MUSIC_PREFIX = "music:"

    /** 解密元数据用的 AES 密钥（**与 CORE_KEY 不同**）。 */
    private val MODIFY_KEY = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28,
    )

    private const val MAX_KEY_LEN = 1024
    private const val MAX_META_LEN = 1 shl 20   // 1 MB

    /** 解密结果：音频字节 + 元信息（不落盘）。 */
    data class Decoded(
        val audioBytes: ByteArray,
        val format: String,     // "mp3" / "flac" ...
        val title: String?,
        val artist: String?,
    )

    /** 是否为 NCM 文件（仅看魔数，读 8 字节）。 */
    fun isNcm(input: InputStream): Boolean {
        val magic = ByteArray(8)
        val n = input.read(magic)
        return n == 8 && magic.contentEquals("CTENFDAM".toByteArray(Charsets.US_ASCII))
    }

    /**
     * 解密一个 NCM 流，返回内存中的音频字节。
     *
     * ⚠️ 调用方负责**不把结果写到文件**（本项目约定：仅内存播放）。
     *
     * @throws IllegalArgumentException 非 NCM 或损坏
     */
    fun decode(input: InputStream): Decoded {
        val raw = input.readBytes()
        val buf = ByteArrayInputStream(raw)

        // ---------- 1. 魔数 ----------
        val magic = ByteArray(8); buf.read(magic)
        require(magic.contentEquals("CTENFDAM".toByteArray(Charsets.US_ASCII))) { "不是 NCM 文件" }
        buf.skip(2)   // 魔数后的 2 字节 gap

        // ---------- 2. 解出 keyBox ----------
        val keyLen = readIntLe(buf)
        require(keyLen in 1..MAX_KEY_LEN) { "key 长度非法: $keyLen" }
        val keyData = ByteArray(keyLen); buf.read(keyData)
        for (i in keyData.indices) keyData[i] = (keyData[i].toInt() xor KEY_XOR_MASK).toByte()

        val keyPlainRaw = aesEcbDecrypt(keyData, CORE_KEY)
        val keyPlain = pkcs7Unpad(keyPlainRaw)
        diag("keyPlainRaw.len=${keyPlainRaw.size} afterUnpad=${keyPlain.size} head=${keyPlain.hex(20)}")
        // 校验并跳过 "neteasecloudmusic"（17B）
        require(keyPlain.size > KEY_PREFIX.length) { "解密后的 key 太短" }
        val prefix = String(keyPlain, 0, KEY_PREFIX.length, Charsets.UTF_8)
        require(prefix == KEY_PREFIX) { "key 前缀不对: $prefix" }
        val keyBytes = keyPlain.copyOfRange(KEY_PREFIX.length, keyPlain.size)
        val keyBox = buildKeyBox(keyBytes)
        diag("keyBytes.len=${keyBytes.size} keyBox[:16]=${keyBox.hex(16)}")

        // ---------- 3. 解出元数据 ----------
        val metaLen = readIntLe(buf)
        val metaJson = if (metaLen <= 0) {
            null
        } else {
            require(metaLen <= MAX_META_LEN) { "元数据长度非法: $metaLen" }
            val modify = ByteArray(metaLen); buf.read(modify)
            for (i in modify.indices) modify[i] = (modify[i].toInt() xor META_XOR_MASK).toByte()
            // modify 内容 = "163 key(Don't modify):" + Base64(AES(JSON))
            val text = String(modify, Charsets.UTF_8)
            runCatching {
                require(text.startsWith(META_PREFIX_TEXT)) { "元数据前缀不对" }
                val b64 = text.substring(META_PREFIX_TEXT.length)
                val encrypted = Base64.decode(b64, Base64.DEFAULT)
                val plain = aesEcbDecrypt(encrypted, MODIFY_KEY)
                val json = String(plain, Charsets.UTF_8)
                // 去掉 "music:" 前缀
                if (json.startsWith(MUSIC_PREFIX)) json.substring(MUSIC_PREFIX.length) else json
            }.getOrElse {
                diag("metadata 解不出（不影响音频）: ${it.message}")
                null
            }
        }

        // ---------- 4. 跳过封面 ----------
        //
        // ## ★ 字节布局（逐字节验证 + 参考 `Calvin-Vollerei/ncmdump-local`）
        // 读完 meta 后，当前位置 `off`：
        // ```
        // off+0 .. off+4     四个字节（该实现叫它 trailer[0:4]；实测是 CRC32）
        // off+4              0x01
        // off+5 .. off+9     cover 长度（LE）
        // off+9 ...          封面图片（JPEG 开头 ff d8 ff）
        // ```
        // 实测本文件：`off=676` → `06 9e 50 4c | 01 | df aa 00 00 | 00 ff d8 ff`
        // 即 coverLen = 0x0000aadf = 43743 ✓
        //
        // ## 为何不读 imageLen
        // 有的文件在 coverLen 后还有一个 imageLen；有的没有。
        // 参考实现只读 coverLen，然后**用试探法**选音频起点（两种布局差 4 字节）：
        // - `off+9+max(coverLen,4)`（本文件是这种）
        // - `off+9+coverLen+4`（另一批 300 个文件，见上游 issue #2）
        // ⇒ 两种都试，取解出头部可识别的那种。
        buf.skip(4)                                  // off+0..4
        buf.skip(1)                                  // off+4 的 0x01
        val coverLen = readIntLe(buf)                // off+5..9
        val afterTrailer = raw.size - buf.available() + coverLen
        diag("coverLen=$coverLen afterTrailer=$afterTrailer")

        // 试两种起点，选能解出可识别音频头的那个
        var audioStart = -1
        for (extra in intArrayOf(0, 4)) {
            val cand = afterTrailer + extra
            if (cand >= raw.size) continue
            val probe = ByteArray(minOf(32, raw.size - cand))
            System.arraycopy(raw, cand, probe, 0, probe.size)
            val head = decryptAudio(keyBox, probe)
            val f = sniffFormat(head)
            diag("  candidate start=$cand (extra=$extra) -> ${head.hex(8)} fmt=$f")
            if (f != "bin") { audioStart = cand; break }
        }
        if (audioStart < 0) {
            audioStart = afterTrailer
            diag("  no candidate sniffed OK, fallback start=$audioStart")
        }
        buf.skip((audioStart - (raw.size - buf.available())).toLong())

        // ---------- 5. 解音频 ----------
        val audioEnc = buf.readBytes()
        val audio = decryptAudio(keyBox, audioEnc)

        val fmt = sniffFormat(audio)
        val (title, artist) = parseMeta(metaJson)
        diag(
            "result: fmt=$fmt audioBytes=${audio.size} decHead=${audio.hex(16)} " +
                "title=$title artist=$artist",
        )
        return Decoded(audio, fmt, title, artist)
    }

    // ---------------- 内部工具 ----------------

    private fun readIntLe(inStream: InputStream): Int {
        val b = ByteArray(4); inStream.read(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * 切 PKCS#7 填充（填充值 1..16；超出范围视为无填充）。
     *
     * ## ★ 必须手动 unpad（2026-10-25 真机修正）
     * 参考实现：`key_data = pkcs7_unpad(aes_ecb_decrypt(CORE_KEY, key_blob))[17:]`
     * 注意顺序：**先 unpad，再跳 17**。
     *
     * 旧实现用 `NoPadding` 但忘了 unpad → key 尾部多出填充字节
     * （实测 `keyData.len=127`，正确应 110）→ KSA 全错 → 音频乱码。
     */
    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xFF
        return if (pad in 1..16 && pad <= data.size) data.copyOfRange(0, data.size - pad) else data
    }

    /**
     * 构造 keyBox：**RC4 KSA + 额外一次重排**。
     *
     * ## ★ 已在真机上验证（2026-10-25）
     *
     * ### 前三次都错在哪里
     * - 一版：只做 RC4 KSA（少了重排）
     * - 二版：抄 C++/C# 的“合并版单循环”，但两边对 `lastByte / c` 的含义抄得不对
     * - 三版：把 KSA 结果当密钥流直接 XOR（少了重排）
     *
     * ### 正确写法（逐行对应 `Calvin-Vollerei/ncmdump-local` 的 `_build_keybox`）
     * ```python
     * for i in range(256):            # ← 标准 RC4 KSA
     *     j = (box[i] + j + key_data[i % n]) & 0xFF
     *     box[i], box[j] = box[j], box[i]
     * for i in range(256):            # ← 额外重排（这步之前一直漏）
     *     t = (i + 1) & 0xFF
     *     out[i] = box[(box[t] + box[(t + box[t]) & 0xFF]) & 0xFF]
     * ```
     *
     * ### 为何不直接信 C++/C# 版
     * 它们把两步合并成一个循环，语义等价但极易抄错（我就是抄错了两次）。
     * 这个两步骤的版本已在真机文件上 **解出 ftyp/m4a 头**，故以它为准。
     */
    private fun buildKeyBox(keyData: ByteArray): ByteArray {
        val n = keyData.size
        require(n > 0) { "密钥为空" }

        // 第一步：标准 RC4 KSA
        val box = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (box[i] + j + (keyData[i % n].toInt() and 0xFF)) and 0xFF
            val t = box[i]; box[i] = box[j]; box[j] = t
        }

        // 第二步：额外重排
        val out = ByteArray(256)
        for (i in 0 until 256) {
            val t = (i + 1) and 0xFF
            out[i] = box[(box[t] + box[(t + box[t]) and 0xFF]) and 0xFF].toByte()
        }
        return out
    }

    /**
     * 解密音频：**简单的重复 256 字节 keyBox XOR**。
     *
     * ```python
     * stream = keybox * (n // 256) + keybox[:n % 256]
     * out    = data XOR stream
     * ```
     * 即 `out[i] = data[i] ^ keyBox[i & 0xff]`。
     *
     * ## ★ 之前写成「嵌套查表 + i+1」是错的
     * 那个公式来自 C# 版 `KeyBoxCipher`，但它作用的是 **KSA 中间态**，不是最终密钥流。
     * 用了正确 keyBox 后，只需**直接模 256 取下标**即可。
     *
     * 这也是它能做流式/随机访问的原因：第 i 字节只依赖 `i & 0xff`，无状态。
     */
    fun decryptAudio(keyBox: ByteArray, data: ByteArray, startPosition: Long = 0L): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            val k = keyBox[((startPosition + i) and 0xFF).toInt()].toInt() and 0xFF
            out[i] = (data[i].toInt() xor k).toByte()
        }
        return out
    }

    /**
     * 通过魔数嗅探真实格式。
     *
     * 顺序与 `Calvin-Vollerei/ncmdump-local` 的 `sniff_format` 一致；
     * **m4a 必须靠 `ftyp` 在第 4~8 字节判定**（真机上第一首就是 m4a：
     * `00 00 00 1c 66 74 79 70 69 73 6f 6d` = box size + "ftyp" + "isom"）。
     */
    private fun sniffFormat(audio: ByteArray): String {
        if (audio.size < 4) return "bin"
        fun ch(i: Int) = audio[i].toInt() and 0xFF
        return when {
            ch(0) == 0x66 && ch(1) == 0x4C && ch(2) == 0x61 && ch(3) == 0x43 -> "flac"
            ch(0) == 0x49 && ch(1) == 0x44 && ch(2) == 0x33 -> "mp3"
            ch(0) == 0xFF && (ch(1) and 0xE0) == 0xE0 -> "mp3"
            ch(0) == 0x4F && ch(1) == 0x67 && ch(2) == 0x67 && ch(3) == 0x53 -> "ogg"
            audio.size >= 12 && ch(4) == 0x66 && ch(5) == 0x74 &&
                ch(6) == 0x79 && ch(7) == 0x70 -> "m4a"
            ch(0) == 0x52 && ch(1) == 0x49 && ch(2) == 0x46 && ch(3) == 0x46 &&
                audio.size >= 12 && ch(8) == 0x57 && ch(9) == 0x41 &&
                ch(10) == 0x56 && ch(11) == 0x45 -> "wav"
            ch(0) == 0x4D && ch(1) == 0x41 && ch(2) == 0x43 && ch(3) == 0x20 -> "ape"
            ch(0) == 0x46 && ch(1) == 0x52 && ch(2) == 0x4D && ch(3) == 0x38 -> "dff"
            else -> "bin"
        }
    }

    /** 极简 JSON 取值（避免引入解析依赖）：仅取 "musicName"/"artist" 字符串。 */
    private fun parseMeta(json: String?): Pair<String?, String?> {
        if (json.isNullOrBlank()) return null to null
        val title = Regex("\"musicName\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        val artist = Regex("\"artist\"\\s*:\\s*\\[\\s*\\[\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: Regex("\"artist\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        return title to artist
    }
}
