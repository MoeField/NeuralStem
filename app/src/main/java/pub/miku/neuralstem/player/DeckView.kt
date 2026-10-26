package pub.miku.neuralstem.player

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import pub.miku.neuralstem.ui.theme.TonearmColor
import pub.miku.neuralstem.ui.theme.WavePlayed
import pub.miku.neuralstem.ui.theme.WaveRemaining
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 阶段 UI-V：**黑胶转盘**（三环同心）。
 *
 * ## 定稿设计（用户逐轮确认）
 *
 * ```
 *              12点
 *          ╱  ②中环  ╲
 *    9点 ─(分界线)   (唱臂)─ 2点   ① 外环（不转）：整曲波形 + 进度分色 + 点/拖 seek
 *          ╲  ③内圈  ╱             ② 中环（不自转，波形随播放滚动）
 *              6点                  ③ 内圈（自转）：封面 / 曲名
 * ```
 *
 * - **①外环**：整曲波形铺满一圈，已播[0,progress]亮色 / 未播暗色；**不转**；
 *   点/拖此环 = seek（角度 → 整曲比例）。
 * - **②中环**：**不自转**；显示 **[当前 - 过去θpast, 当前 + 未来θfuture]** 的**局部波形**，
 *   按播放推进而"滚动"。**唱臂固定在 2 点**（= 当前进度锚点）。分界线在 9 点。
 *   7点半→8点用**扇形遮罩**（过渡带）。
 * - **③内圈**：封面（裁圆）或缺省**曲名环**；**匀速自转**（一圈 = [secPerTurn] ÷ speed）。
 *
 * ## 转速语义（用户定稿）
 * - 基准一圈时长 [secPerTurn] 秒（默认 18，设置可调 9–36 step3）；
 * - **实际一圈 = secPerTurn ÷ speed**（0.5× → 36s，2.0× → 9s）；
 * - 暂停则停转。内圈与中环"滚动窗口"共用该时长。
 *
 * ## 角度分配（定稿）
 * - 唱臂 2 点（60°）；分界线 9 点（270°）；
 * - **已播**（2点→9点顺时针，跨 6 点）= 210°？——不，用户定：**未来多放**：
 *   实际采用 **过去 165° / 未来 180° / 扇形 15°**（以唱臂为 0 参考）。
 */
@Composable
fun DeckView(
    // ★ 波形也用 **lambda provider**：流式提取期间每 ~300ms 变一次（新对象），
    //   若作为参数会引起**整块重组**（实测 3-4 次/s）→ 卡。改为 draw 阶段读 → 只重绘。
    waveformProvider: () -> WaveformData?,
    // ★F5.1：多轨时各 stem 波形（空列表=单轨，不叠加）。同样用 lambda → draw 阶段读。
    stemWaveformProvider: () -> List<WaveformData?> = { emptyList() },
    cover: ImageBitmap?,
    songName: String,
    // ★ 进度流在**本组件内部 collect**（不靠父层 collect），避免父（PlayerScreen）
    //   每 100ms 重组 → 连带 DeckView 重组（“时间+转盘一起卡”的根因）。
    //   collect 到的 State **只在 draw 阶段读** → 仅重绘、不重组自身。
    progressFlow: StateFlow<PlayerViewModel.Progress>,
    // ★ UI 时钟锚点流：每帧用本地时钟外推得平滑时间轴（详见 PlayerViewModel.UiClock）。
    anchorFlow: StateFlow<PlayerViewModel.UiClock?>,
    speed: Float,
    isPlaying: Boolean,
    secPerTurn: Int,
    onTogglePlayPause: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    modifier: Modifier = Modifier,
    playedColor: Color = WavePlayed,
    unplayedColor: Color = WaveRemaining,
    /**
     * ★G-3 视觉引导（2026-10-20）：边播时**分离已推进到哪里**（0..1 全曲比例）。
     *
     * ## 为何需要
     * 边分离边播时，用户看到的是“多轨在放”，但不知道**后面还有多少没分离好**。
     * 只能靠屏幕上的文字（“分离中 42%”）→ 视线要在波形和文字间跳。
     * 直接在外环上标一条线，眼睛不用离开转盘。
     *
     * ## null = 边播未激活（普通预分离 / 无分离）→ 不画标记
     */
    separatedFraction: Float? = null,
    /** 边播进行中（用于把标记画得显眼一点；完成后只是条淡线）。 */
    isEdgeSeparating: Boolean = false,
) {
    // ★ 进度：在本组件 collect（重组范围仅限本组件），仅在 draw 阶段读其值。
    val progressState = progressFlow.collectAsState()

    // ★ 平滑位置：锚点外推 + 重锚突变的逐帧“缓追”（见 SmoothPosition.kt）→ 不卡不跳。
    //   resetKey = 曲名+时长：切歌/重导入时重置（否则旧歌状态带入新歌 → 波形错乱）。
    //
    //   ★2026-10-26：**snapOnSeek = false**。
    //   转盘只有“点外环 → seek”（没有拖动过程），
    //   用户要的正是“转盘一圈圈平滑转到目标”的磁带倒带感。
    //   若也直接对齐，转盘会硬跳一下，手感全没。
    //   （进度条则相反：那里有拖动参照，平滑反而会先冲高→用 snapOnSeek=true）
    val smoothPos = rememberSmoothPosition(
        anchorFlow,
        resetKey = songName to progressState.value.durationMs,
        snapOnSeek = false,
    )

    // ★ 每帧失效（驱动连续重绘）；实际位置由平滑值给出（见下方 draw）。
    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(isPlaying) {
        while (true) {
            withFrameNanos { if (isPlaying) frameTick++ }
        }
    }

    // ★ 曲名环缓存（无封面时用）：避免每帧对每字符 save/rotate/drawText。
    //   key = (songName, radius, color)；radius 在 draw 时才知道，故用“懒重建”holder。
    val songRingCache = remember { SongRingCache() }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val side = maxWidth
        // ⚠ pointerInput 的 key 用 Unit（不用 durationMs）：seek 时 duration 会短暂变化，
        //   若当 key 会导致**手势模块重建 → 视觉闪**。tap/滑手势不依赖 duration。
        Canvas(
            modifier = Modifier
                .size(side)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { off ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val r = hypot((off.x - c.x).toDouble(), (off.y - c.y).toDouble()).toFloat()
                            val maxR = min(c.x, c.y)
                            when {
                                r < maxR * 0.34f -> onTogglePlayPause()          // 内圈
                                else -> onSeekFraction(angleFraction(off, c))    // 外环/其他 → seek
                            }
                        },
                    )
                }
                // 盘上左右滑 = 换碟（上/下一首）
                .pointerInput(Unit) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onHorizontalDrag = { _, dx -> acc += dx },
                        onDragEnd = {
                            if (acc > 120f) onPrev() else if (acc < -120f) onNext()
                        },
                    )
                },
        ) {
            // ★ 在 draw 阶段才读进度（不在 composition 阶段）
            @Suppress("UNUSED_EXPRESSION")
            frameTick   // 读一下 → 每帧失效（驱动连续重绘）
            // ★ 位置：由 rememberSmoothPosition（锚点外推 + 缓追）给出 → 不卡不跳。
            val durationMs = progressState.value.durationMs
            val positionMs = if (smoothPos.value > 0f) {
                smoothPos.value.toLong()
            } else {
                progressState.value.positionMs
            }
            // ★ 波形也在 draw 阶段读（流式提取期间每 ~300ms 变，只应触发重绘、不重组）
            val waveform = waveformProvider()
            val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)
            val maxR = min(cx, cy)

            // 半径分层
            val rInner = maxR * 0.32f           // ③ 内圈（封面/曲名）
            val rMid0 = maxR * 0.42f            // ② 中环内
            val rMid1 = maxR * 0.62f            // ② 中环外
            val rOut0 = maxR * 0.68f            // ① 外环内
            val rOut1 = maxR * 0.92f            // ① 外环外

            // ---- ① 外环：整曲波形 + 进度分色（不转）----
            //   ★ 角度按**真实全曲时长**（player duration）映射，**只画已解码部分**；
            //     否则流式增量时“前 2s 波形会铺满整圈” → 乱跳。
            //   ★F5.1：多轨时叠加各 stem 波形（不同颜色）；单轨回退为原曲波形。
            val stems = stemWaveformProvider()
            if (stems.any { it != null }) {
                drawStemOverviewRing(
                    stems, durationMs, center, rOut0, rOut1, progress, StemColors,
                )
                // 外环底圈轮廓（不影响叠加可读性）
                drawCircle(
                    color = unplayedColor.copy(alpha = 0.5f),
                    radius = (rOut0 + rOut1) / 2f, center = center, style = Stroke(1f),
                )
            } else {
                drawOverviewRing(waveform, durationMs, center, rOut0, rOut1, progress, playedColor, unplayedColor)
            }

            // ---- ② 中环：局部波形（不自转；唱臂 2 点 = 当前进度）----
            drawLocalRing(
                wf = waveform, center = center, rInner = rMid0, rOut1 = rMid1,
                positionMs = positionMs, secPerTurn = secPerTurn, speed = speed,
                playedColor = playedColor, unplayedColor = unplayedColor,
            )

            // ---- ③ 内圈：封面 / 曲名环（随盘自转）----
            // 转角由 positionMs 直算（与中环同源）：一圈 = (secPerTurn/speed) 秒。
            val turnMs = (secPerTurn.toDouble() / speed.coerceAtLeast(0.01f)).coerceAtLeast(0.01) * 1000.0
            val rotationDeg = (((positionMs % turnMs.toLong()) / turnMs) * 360.0).toFloat()
            rotate(degrees = rotationDeg, pivot = center) {
                if (cover != null) {
                    drawCircleImage(cover, center, rInner)
                } else {
                    // ★ 曲名环：缓存为 ImageBitmap（不然每帧对每字 save/rotate/drawText 很贵）
                    val bmp = songRingCache.bitmapFor(songName, rInner * 0.9f, playedColor)
                    if (bmp != null) {
                        drawImage(
                            image = bmp,
                            topLeft = Offset(center.x - bmp.width / 2f, center.y - bmp.height / 2f),
                        )
                    } else {
                        drawSongNameRing(songName, center, rInner * 0.9f, playedColor)
                    }
                }
            }
            drawCircle(color = playedColor, radius = rInner, center = center, style = Stroke(width = 2f))

            // ★G-3 视觉引导：在**外环**上标“分离已推进到哪”（2026-10-20）
            //   画在外环外侧一点（rOut1*1.04），不压波形本体。
            //   为何用角度线而不是扇形：扇形遮罩会抢掉波形可读性；一条线足矣。
            if (separatedFraction != null && separatedFraction < 1f) {
                drawSeparationMarker(
                    center = center,
                    radius = rOut1 * 1.04f,
                    fraction = separatedFraction,
                    color = if (isEdgeSeparating) SeparationActiveColor else SeparationDoneColor,
                )
            }

            // ---- 扇形遮罩：位于“过去结束 / 未来开始”交界的 15° 过渡带。
            //   ★ 位置：与**唱臂轴对称**（唱臂 1点半 → 对侧 7点半）；幅度偏“未播”侧 → 7点~7点半。
            //     边缘为离散（每度一条线）→ 左右**各补 1°**（210/225 → 209/226）使边缘不“缺角”。
            drawMaskSector(center, rMid0, rOut1, 209f, 226f, Color(0x66B0BEC5))

            // ---- 唱臂：2 点方向固定（从外缘伸向内圈的小杆）；酒红色（机械件，与波形区分）----
            drawTonearm(center, rOut1, rInner, TonearmColor)
        }
    }
}

/**
 * ① 外环：整曲概览。
 *
 * ★ 角度按**真实全曲时长** [playerDurationMs] 映射；**只画波形已覆盖（已解码）的部分**，
 *   其余角度留空。否则流式增量时“前几秒的波形会铺满整圈” → 视觉乱跳。
 * 每根线按“角度 → 时间 → 包络下标”取值（`wf.indexForPositionMs` 只在其 duration 内有效）。
 */
private fun DrawScope.drawOverviewRing(
    wf: WaveformData?,
    playerDurationMs: Long,
    center: Offset,
    rInner: Float,
    rOut1: Float,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
) {
    if (wf == null || wf.pointCount == 0) {
        drawCircle(unplayedColor, (rInner + rOut1) / 2f, center, style = Stroke(2f))
        return
    }
    // ★ 最多画 ~360 条（每度一条）：外环是“概览”，不需要全部分辨率。
    val lines = 360
    val perDeg = 360f / lines
    // 已解码时长（wf.durationMs）；超过此的时间（角度）无波形数据。
    val decodedMs = wf.durationMs.coerceAtLeast(1L)
    for (i in 0 until lines) {
        val frac = i.toFloat() / lines                       // 0..1 全曲比例
        val tMs = (frac * (if (playerDurationMs > 0) playerDurationMs else decodedMs)).toLong()
        if (tMs > decodedMs) continue                        // 未解码 → 不画（留空）
        val srcIdx = wf.indexForPositionMs(tMs)
        val j2 = wf.indexForPositionMs(tMs + 500L).coerceAtMost(wf.pointCount)
        var amp = 0f
        var k = srcIdx
        while (k < j2 && k < wf.pointCount) {
            val a = (wf.max[k] - wf.min[k]) * 0.5f
            if (a > amp) amp = a
            k++
        }
        amp = amp.coerceIn(0f, 1f)
        val deg = -90f + i * perDeg
        val rad = Math.toRadians(deg.toDouble())
        val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
        val endR = rInner + (rOut1 - rInner) * (0.15f + 0.85f * amp)
        val played = frac <= progress
        drawLine(
            if (played) playedColor else unplayedColor,
            Offset(center.x + dx * rInner, center.y + dy * rInner),
            Offset(center.x + dx * endR, center.y + dy * endR),
            strokeWidth = 2f,
        )
    }
}

/** ★F5.1：各 stem 叠加色（human 辨识度优先；半透明 → 重叠处可叠加看出）。 */
private val StemColors: List<Color> = listOf(
    Color(0xFF4FC3F7),   // vocals —— 青
    Color(0xFFFFB74D),   // accompaniment —— 橙
    Color(0xFF81C784),   // drums —— 绿
    Color(0xFFBA68C8),   // bass —— 紫
    Color(0xFFE57373),   // other —— 红
)

/**
 * ★G-3 视觉引导：分离进度标记的颜色。
 *
 * - **进行中**：黄色（= “还在干活”，与播放本身的青色/橙色区分开）
 * - **已完成**：灰色淡线（仅作为“本次边播从哪开始”的记忆，不抢注意力）
 *
 * 为何不用红色：红色在本项目已被 “Starved / 错误” 占用（播放页文案用 error 色）。
 */
private val SeparationActiveColor = Color(0xFFFFD54F)   // 琥珀黄
private val SeparationDoneColor = Color(0x55607D8B)     // 淡灰蓝（半透明）


/**
 * ★F5.1：外环**多轨叠加**——每条 stem 用不同颜色、按不同半径层画同一时间轴包络。
 *
 * 几何：第 k 轨占据该半径带内的一小段（带内均分），使“各轨波形”互相可见而不完全覆盖。
 * 进度分色：未播部分统一降透明度；已播部分更亮（保持“已播/未播”语义）。
 */
private fun DrawScope.drawStemOverviewRing(
    stems: List<WaveformData?>,
    playerDurationMs: Long,
    center: Offset,
    rInner: Float,
    rOut1: Float,
    progress: Float,
    colors: List<Color>,
) {
    val list = stems.filterNotNull()
    if (list.isEmpty()) return
    val lines = 360
    val perDeg = 360f / lines
    val slot = (rOut1 - rInner) / list.size          // 每轨的半径带宽
    list.forEachIndexed { trackIdx, wf ->
        if (wf.pointCount == 0) return@forEachIndexed
        val baseColor = colors[trackIdx % colors.size]
        val bandInner = rInner + slot * trackIdx
        val bandOuter = bandInner + slot
        val decodedMs = wf.durationMs.coerceAtLeast(1L)
        for (i in 0 until lines) {
            val frac = i.toFloat() / lines
            val tMs = (frac * (if (playerDurationMs > 0) playerDurationMs else decodedMs)).toLong()
            val deg = -90f + i * perDeg
            val rad = Math.toRadians(deg.toDouble())
            val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
            val from = Offset(center.x + dx * bandInner, center.y + dy * bandInner)
            // ★G-3-ux2：未分离到这里（或该轨完全没有波形）→ 只画**淡基线**，
            //   告知用户“这段还没分离”，而不是完全空白（易被当成 bug）。
            if (tMs > decodedMs) {
                drawLine(
                    // 0.16：既能看出“还有一段”，又不会跟真波形抢注意力。
                    // （0.10 在浅色主题下几乎看不见，实测两轨都要 0.16 才一致可辨。）
                    baseColor.copy(alpha = 0.16f),
                    from,
                    Offset(center.x + dx * bandOuter, center.y + dy * bandOuter),
                    strokeWidth = 1.2f,
                )
                continue
            }
            val srcIdx = wf.indexForPositionMs(tMs)
            val j2 = wf.indexForPositionMs(tMs + 500L).coerceAtMost(wf.pointCount)
            var amp = 0f
            var k = srcIdx
            while (k < j2 && k < wf.pointCount) {
                val a = (wf.max[k] - wf.min[k]) * 0.5f
                if (a > amp) amp = a
                k++
            }
            amp = amp.coerceIn(0f, 1f)
            val endR = bandInner + (bandOuter - bandInner) * (0.15f + 0.85f * amp)
            val played = frac <= progress
            val alpha = if (played) 1f else 0.45f
            drawLine(
                baseColor.copy(alpha = alpha),
                from,
                Offset(center.x + dx * endR, center.y + dy * endR),
                strokeWidth = 2f,
            )
        }
    }
}

/**
 * ② 中环：局部滚动窗。
 *
 * - 一圈 = [secPerTurn] ÷ [speed] 秒（定稿语义）。
 * - **唱臂在 2 点（60°）= 当前进度**；从唱臂向**顺时针** = **未来**（180°），
 *   向**逆时针** = **过去**（165°）；余下 15°（7点半→8点方向）为扇形遮罩。
 *
 * 实现：以唱臂角(60°) 为 0 参考，屏幕角 θ∈[0,360) 顺时针；
 *   - Δ = (θ - 60°) mod 360，映射到 [-past, +future] 的时间偏移：
 *     Δ ≤ 180 → 未来（0..+180°）；Δ > 180 → 过去（-180..0）。
 */
private fun DrawScope.drawLocalRing(
    wf: WaveformData?,
    center: Offset,
    rInner: Float,
    rOut1: Float,
    positionMs: Long,
    secPerTurn: Int,
    speed: Float,
    playedColor: Color,
    unplayedColor: Color,
) {
    val w = wf ?: return
    val n = w.pointCount
    if (n == 0 || w.durationMs <= 0) return

    val turnMs = (secPerTurn * 1000.0 / speed.coerceAtLeast(0.01f)).toLong()   // 一圈毫秒
    val pastDeg = 165f        // 过去占用角度
    val futureDeg = 180f      // 未来占用角度
    // ⚠ 统一基准：12 点 = -90°（与外环 drawOverviewRing 一致）。
    //   “1 点半方向” = 12点(-90°) + 45° = -45°。
    val tonearmDeg = -45f     // 唱臂在 1 点半

    // ★ 只遍历窗口内的下标（不再全表 O(n)）；窗口 = [pos - pastMs, pos + futureMs]
    val perPointMs = w.durationMs.toDouble() / n
    val pastMs = turnMs * pastDeg / 360.0
    val futureMs = turnMs * futureDeg / 360.0
    val iStart = ((positionMs - pastMs) / perPointMs).toInt().coerceIn(0, n - 1)
    val iEnd = ((positionMs + futureMs) / perPointMs).toInt().coerceIn(0, n - 1)
    for (i in iStart..iEnd) {
        val tMs = i * perPointMs
        val rel = tMs - positionMs                       // <0 过去, >0 未来
        // ★ 黑胶语义（用户定稿）：唱片**顺时针**自转，唱臂固定。
        //   ⇒ 未播（未来）在唱臂的**逆时针上游**，顺时针转过来恰好到达唱臂；
        //     已播（过去）在唱臂的**顺时针下游**（已转过唱臂）。
        //   数学角 cos/sin（y 向上）：角度减小 = 屏幕逆时针，角度增大 = 屏幕顺时针。
        val deg: Float = if (rel >= 0) {
            tonearmDeg - (rel / turnMs * 360.0).toFloat()          // 未来 → 逆时针（上游）
        } else {
            tonearmDeg + (-rel / turnMs * 360.0).toFloat()         // 过去 → 顺时针（下游）
        }
        val rad = Math.toRadians(deg.toDouble())
        val amp = ((w.max[i] - w.min[i]) * 0.5f).coerceIn(0f, 1f)
        val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
        val endR = rInner + (rOut1 - rInner) * (0.15f + 0.85f * amp)
        val played = rel <= 0
        drawLine(
            if (played) playedColor else unplayedColor,
            Offset(center.x + dx * rInner, center.y + dy * rInner),
            Offset(center.x + dx * endR, center.y + dy * endR),
            strokeWidth = 3f,
        )
    }
}

/**
 * ★G-3 视觉引导：在外环上画一条“分离已推进到哪”的标记。
 *
 * ## 几何
 * 与 [drawOverviewRing] 同坐标系：12 点 = -90°，顺时针增加。
 * `fraction=0` → 12 点；`fraction=1` → 12 点一周后（不画，见调用处）。
 *
 * ## 为何是一条线而不是扇形
 * 外环已被波形占满。再叠一个扇形会抢掉波形可读性（真的试过 → 糊成一团）。
 * 一条细线足够回答“分离到哪了”这个唯一问题。
 *
 * ## 为何画在 rOut1 * 1.04（环外）
 * 避免与波形本身重叠；同时让它看起来像“环的边框上的刻度”。
 */
private fun DrawScope.drawSeparationMarker(
    center: Offset,
    radius: Float,
    fraction: Float,
    color: Color,
) {
    val f = fraction.coerceIn(0f, 1f)
    val deg = -90f + f * 360f          // 12 点起顺时针
    val rad = Math.toRadians(deg.toDouble())
    val dx = cos(rad).toFloat()
    val dy = sin(rad).toFloat()
    // 线段沿径向向外延伸一小段（像刻度）
    val r0 = radius - 6f
    val r1 = radius + 10f
    drawLine(
        color = color,
        start = Offset(center.x + dx * r0, center.y + dy * r0),
        end = Offset(center.x + dx * r1, center.y + dy * r1),
        strokeWidth = 6f,
    )
}

/** 唱臂：1 点半方向（=-45°），从外缘伸向内圈的小杆（固定）。
 *  线宽 9px（曾 5→7→9）；**起点外延**至外环之外（`rOut1 * 1.08`）。 */
private fun DrawScope.drawTonearm(center: Offset, rOut1: Float, rInner: Float, color: Color) {
    val rad = Math.toRadians(-45.0)
    val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
    val rStart = rOut1 * 1.08f   // 往外延伸（超出外环）
    val rEnd = rInner + (rOut1 - rInner) * 0.25f
    drawLine(
        color = color,
        start = Offset(center.x + dx * rStart, center.y + dy * rStart),
        end = Offset(center.x + dx * rEnd, center.y + dy * rEnd),
        strokeWidth = 9f,
    )
}

/** 内圈：圆形封面（圆裁）。 */
private fun DrawScope.drawCircleImage(cover: ImageBitmap, center: Offset, radius: Float) {
    drawCircle(color = Color(0xFF1D262E), radius = radius, center = center)
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        nc.save()
        nc.clipPath(
            android.graphics.Path().apply {
                addCircle(center.x, center.y, radius, android.graphics.Path.Direction.CW)
            },
        )
        val bmp = cover.asAndroidBitmap()
        val dst = RectF(
            center.x - radius, center.y - radius, center.x + radius, center.y + radius,
        )
        nc.drawBitmap(bmp, null, dst, Paint().apply { isAntiAlias = true })
        nc.restore()
    }
}

/** 内圈：曲名沿圆排列（无封面时）。 */
private fun DrawScope.drawSongNameRing(text: String, center: Offset, radius: Float, color: Color) {
    if (text.isEmpty()) return
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = 30f
        this.color = color.toArgb()
    }
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val chars = text.toCharArray()
        val step = (2 * Math.PI / chars.size).toFloat()
        for ((i, ch) in chars.withIndex()) {
            val ang = -Math.PI.toFloat() / 2f + i * step
            val x = center.x + cos(ang) * radius
            val y = center.y + sin(ang) * radius
            nc.save()
            nc.rotate(Math.toDegrees(ang.toDouble()).toFloat() + 90f, x, y)
            nc.drawText(ch.toString(), x, y, paint)
            nc.restore()
        }
    }
}

/** 触点相对圆心、12 点方向起顺时针的角度比例 (0..1)。 */
private fun angleFraction(p: Offset, center: Offset): Float {
    val dx = p.x - center.x
    val dy = p.y - center.y
    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    while (deg < 0f) deg += 360f
    while (deg >= 360f) deg -= 360f
    return deg / 360f
}

/**
 * 扇形遮罩：在 [r0,r1] 环带内、从 [fromDeg] 顺时针扫到 [toDeg]（12点=0°）填充实色。
 * 方式：外弧正向 + 内弧反向 + close，形成一个环带扇区。
 */
private fun DrawScope.drawMaskSector(
    center: Offset,
    r0: Float,
    r1: Float,
    fromDeg: Float,
    toDeg: Float,
    color: Color,
) {
    // Compose Path.arcTo 的 0° 在 3 点方向；我们“12点=0°”，故转成 -90°，并转成“弧度”
    val startA = fromDeg - 90f
    val sweep = toDeg - fromDeg
    val path = Path().apply {
        arcTo(
            rect = Rect(center.x - r1, center.y - r1, center.x + r1, center.y + r1),
            startAngleDegrees = startA,
            sweepAngleDegrees = sweep,
            forceMoveTo = true,
        )
        arcTo(
            rect = Rect(center.x - r0, center.y - r0, center.x + r0, center.y + r0),
            startAngleDegrees = startA + sweep,
            sweepAngleDegrees = -sweep,
            forceMoveTo = false,
        )
        close()
    }
    drawPath(path, color)
}

/**
 * 曲名环的**位图缓存**（无封面时用）。
 *
 * 原因：直接画曲名环需要逐字符 `canvas.save/rotate/drawText/restore`，
 * 每帧执行代价高（尤其带中文的曲名）。缓存后只需一次 `drawImage`。
 *
 * key = (text, radius, color)；任意一项变则重建。radius 在 draw 阶段才知道，
 * 故不自带 remember，由调用方 `remember { SongRingCache() }` 持有。
 * 仅在 **主/绘制线程**使用（UI 绘制），无需同步。
 */
internal class SongRingCache {
    private var cachedText: String? = null
    private var cachedRadius: Float = -1f
    private var cachedColor: Int = 0
    private var bitmap: ImageBitmap? = null

    fun bitmapFor(text: String, radius: Float, color: Color): ImageBitmap? {
        if (text.isEmpty() || radius <= 1f) return null
        val argb = color.toArgb()
        // ★ 半径用“取整到 px”作 key：避免亚像素抖动导致每帧重建。
        val radiusKey = Math.round(radius).toFloat()
        if (bitmap != null && text == cachedText && radiusKey == cachedRadius && argb == cachedColor) {
            return bitmap
        }
        val side = (radius * 2f).toInt().coerceAtLeast(1)
        val sizePx = side * 2   // 留边，避免字被裁
        val bmp = Bitmap.createBitmap(
            sizePx, sizePx, Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bmp)
        val paint = Paint().apply {
            isAntiAlias = true
            this.color = argb
            textSize = radius * 0.30f
        }
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val chars = text.toCharArray()
        val step = (2 * Math.PI / chars.size).toFloat()
        for ((i, ch) in chars.withIndex()) {
            val ang = -Math.PI.toFloat() / 2f + i * step
            val x = cx + cos(ang) * radius
            val y = cy + sin(ang) * radius
            canvas.save()
            canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat() + 90f, x, y)
            canvas.drawText(ch.toString(), x, y, paint)
            canvas.restore()
        }
        val image = bmp.asImageBitmap()
        cachedText = text; cachedRadius = radiusKey; cachedColor = argb; bitmap = image
        return image
    }
}
