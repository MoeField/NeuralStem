package pub.miku.neuralstem.playlist

/**
 * ★把「原始文件名」拆成「歌名 + 艺人」，供歌单两行显示。
 *
 * ## 为什么需要
 * 网易云的下载文件名是 `艺人1 艺人2 艺人3 - 歌名 (版本信息)` 这种格式。
 * 实测这批 1002 首里：
 * - **最长 165 字**
 * - **51% 超过 30 字**
 *
 * 主要原因就是**把一堆合作艺人全塞在标题前面**。例：
 * ```
 * acaer AIKA Airuei Anemoi Kotori Polarr ... Trifect Winamp Boys - If
 *                    ↑ 15 个艺人名，占了大半行
 * ```
 *
 * 直接截断（Ellipsis）会把真正有用的**歌名**切掉，只剩艺人列表 —— 反而更难认。
 *
 * ## 拆分规则（简单但够用）
 * 1. 找 `" - "`（空格-连字符-空格）作为分隔符
 * 2. 左边 = 艺人，右边 = 歌名
 * 3. **只有当左边较短（≤24 字）时才拆** —— 否则可能是歌名本身含连字符
 * 4. 找不到分隔符 / 不满足条件 → 整体当歌名，艺人留空
 *
 * ## 为什么不解析括号里的版本信息
 * `(Remix)` / `（Original Mix）` 这类信息对辨认歌曲帮助不大，
 * 而且括号嵌套复杂（全角/半角混用）。保留在歌名里，靠 UI 截断即可。
 */
object TrackTitleFormatter {

    /** 艺人部分的最大长度；超过就不认为是「艺人 - 歌名」格式。 */
    private const val MAX_ARTIST_LEN = 24

    /** 显示用的两行结构。 */
    data class Formatted(
        /** 歌名（主要信息）。 */
        val title: String,
        /** 艺人（可能为空）。 */
        val artist: String,
        /** 状态后缀，如 `[需解码]` / `[加密]`；无则空。 */
        val badge: String,
    ) {
        val hasArtist: Boolean get() = artist.isNotBlank()
    }

    /**
     * 拆分。
     *
     * @param raw 原始标题（可能带 `[需解码]` 之类的后缀）
     */
    fun format(raw: String): Formatted {
        // 1) 先摘出状态后缀（我们自己加的，不是文件名的一部分）
        val (body, badge) = splitBadge(raw)

        // 2) 找 " - " 分隔（全角/半角都试）
        val sepIdx = listOf(" - ", " － ", " – ").map { body.indexOf(it) }
            .filter { it > 0 }
            .minOrNull()

        if (sepIdx == null) {
            return Formatted(body.trim(), "", badge)
        }

        val left = body.substring(0, sepIdx).trim()
        val right = body.substring(sepIdx + 3).trim()

        // 3) 左边太长就不拆（可能整个是歌名，或分隔符在歌名内部）
        if (left.length > MAX_ARTIST_LEN || right.isEmpty()) {
            return Formatted(body.trim(), "", badge)
        }

        return Formatted(title = right, artist = left, badge = badge)
    }

    /** 拆出我们自己加的后缀。 */
    private fun splitBadge(raw: String): Pair<String, String> {
        for (b in listOf("[需解码]", "[加密]", "[未知]")) {
            if (raw.endsWith(" $b")) {
                return raw.removeSuffix(" $b").trim() to b
            }
        }
        return raw to ""
    }
}
