package pub.miku.neuralstem.experimental.ncm

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

    /** 客户端硬编码的 AES-128 密钥（公开分析所得）。 */
    private val CORE_KEY = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57,
    )

    private val META_PREFIX = byteArrayOf(
        0x23, 0x35, 0x34, 0x4C, 0x79, 0x65, 0x72, 0x54,
        0x65, 0x61, 0x6D, 0x2D, 0x4E, 0x43, 0x4E, 0x50,
    )

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
        // 用 skip 精确推进，避免依赖 BufferedInputStream 的语义
        val magic = ByteArray(8); buf.read(magic)
        require(magic.contentEquals("CTENFDAM".toByteArray(Charsets.US_ASCII))) { "不是 NCM 文件" }

        buf.skip(2)

        // --- 解密 AES 密钥 ---
        val keyLen = readIntLe(buf)
        val keyData = ByteArray(keyLen); buf.read(keyData)
        for (i in keyData.indices) keyData[i] = (keyData[i].toInt() xor 0x64).toByte()

        val keyBox = aesEcbDecrypt(keyData, CORE_KEY)
        val keyStream = stripMetaPrefix(keyBox)          // 去 17 字节前缀 → RC4 key
        val rc4Key = buildRc4Key(keyStream)

        // --- 元数据 ---
        val metaLen = readIntLe(buf)
        val metaEnc = ByteArray(metaLen); buf.read(metaEnc)
        val metaJson = runCatching {
            String(aesEcbDecrypt(metaEnc, CORE_KEY), Charsets.UTF_8)
        }.getOrNull()

        // --- 跳过 CRC(4) + 5 字节 + 封面 ---
        buf.skip(4)
        buf.skip(5)
        val imgLen = readIntLe(buf)
        buf.skip(imgLen.toLong())

        // --- 音频：RC4 解密 ---
        val audioEnc = buf.readBytes()
        val audio = rc4(rc4Key, audioEnc)

        val fmt = sniffFormat(audio)
        val (title, artist) = parseMeta(metaJson)
        android.util.Log.i(
            TAG,
            "decoded ncm: format=$fmt audioBytes=${audio.size} title=$title artist=$artist",
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
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /** 去 META_PREFIX（若存在）+ 额外的 3 字节，得到 RC4 key 的原始字节。 */
    private fun stripMetaPrefix(data: ByteArray): ByteArray {
        val start = if (data.size > 17 && data.copyOfRange(0, 16).contentEquals(META_PREFIX)) 17 else 0
        return data.copyOfRange(start, data.size)
    }

    /** 把密钥字节展开成 256 字节的 RC4 密钥盒（KSA）。 */
    private fun buildRc4Key(seed: ByteArray): ByteArray {
        val box = ByteArray(256) { it.toByte() }
        var j = 0
        for (i in 0 until 256) {
            j = (j + (box[i].toInt() and 0xFF) + (seed[i % seed.size].toInt() and 0xFF)) and 0xFF
            val t = box[i]; box[i] = box[j]; box[j] = t
        }
        return box
    }

    /** RC4 PRGA：用已初始化的 keybox 生成密钥流并与数据异或。 */
    private fun rc4(keyBoxSeed: ByteArray, data: ByteArray): ByteArray {
        val s = keyBoxSeed.copyOf()
        var i = 0; var j = 0
        val out = ByteArray(data.size)
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + (s[i].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            val kk = s[((s[i].toInt() and 0xFF) + (s[j].toInt() and 0xFF)) and 0xFF].toInt() and 0xFF
            out[k] = (data[k].toInt() xor kk).toByte()
        }
        return out
    }

    /** 通过魔数嗅探真实格式。 */
    private fun sniffFormat(audio: ByteArray): String = when {
        audio.size >= 4 && audio[0] == 'f'.code.toByte() && audio[1] == 'L'.code.toByte() &&
            audio[2] == 'a'.code.toByte() && audio[3] == 'C'.code.toByte() -> "flac"
        audio.size >= 3 && audio[0] == 'I'.code.toByte() &&
            audio[1] == 'D'.code.toByte() && audio[2] == '3'.code.toByte() -> "mp3"
        audio.size >= 2 && (audio[0].toInt() and 0xFF) == 0xFF &&
            (audio[1].toInt() and 0xE0) == 0xE0 -> "mp3"
        else -> "bin"
    }

    /** 极简 JSON 取值（避免引入解析依赖）：仅取 "musicName"/"artist" 字符串。 */
    private fun parseMeta(json: String?): Pair<String?, String?> {
        if (json.isNullOrBlank()) return null to null
        val title = Regex("\"musicName\"\\s*:\\s*\\[\\s*\\[\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        val artist = Regex("\"artist\"\\s*:\\s*\\[\\s*\\[\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        return title to artist
    }
}
