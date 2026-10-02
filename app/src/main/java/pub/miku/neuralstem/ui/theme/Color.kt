package pub.miku.neuralstem.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 阶段 UI / U0：**仅深色**配色（DJ 控制台风格）。
 *
 * 用户决策：「只提供深色吧」—— 故不再保留浅色分支（原模板的 Purple40 系已移除）。
 * 配色取向：近黑背景 + 青绿强调色（转盘/进度/波形用），符合设备类 App。
 */

// ---- 强调色（青绿）----
val Accent = Color(0xFF00E5C0)          // primary：转盘指针 / 进度 / 高亮
val AccentDim = Color(0xFF00B39A)       // primary 变暗（按下态等）
val OnAccent = Color(0xFF00201B)        // accent 上的文字

// ---- 次要强调（紫）----
val Accent2 = Color(0xFFB388FF)         // secondary
val OnAccent2 = Color(0xFF1E0038)

// ---- 背景/表面（近黑分层）----
val BgDark = Color(0xFF0B0F12)          // 背景
val SurfaceDark = Color(0xFF141A1F)     // 卡片表面
val SurfaceVariantDark = Color(0xFF1D262E) // 卡片内次级面（如分轨行）

// ---- 文字 ----
val OnBg = Color(0xFFE6EDF3)
val OnSurfaceVariantDark = Color(0xFFB0BEC5)

// ---- ★F5.3：浅色配色（跟随系统亮色时用）----
// 亮色下用**更深的青绿**（白底上保证对比度）；其余为浅色分层。
val AccentLight = Color(0xFF009B84)     // primary（亮色）
val OnAccentLight = Color(0xFFFFFFFF)   // accent 上的文字
val Accent2Light = Color(0xFF7C4DFF)    // secondary（亮色）
val OnAccent2Light = Color(0xFFFFFFFF)
val BgLight = Color(0xFFF5F8F9)         // 背景（亮）
val SurfaceLight = Color(0xFFFFFFFF)    // 卡片表面（亮）
val SurfaceVariantLight = Color(0xFFE6EDF0) // 次级面（亮）
val OnBgLight = Color(0xFF1A1C1E)       // 文字（亮）
val OnSurfaceVariantLight = Color(0xFF45494C)

// ---- 波形双色（沿用 WaveformView 语义：已播/未播）----
val WavePlayed = Accent
val WaveRemaining = Color(0xFF37474F)

// ---- 转盘部件 ----
/** 唱臂颜色（珊瑚红：比酒红更亮、与近黑背景区分好；偏橙，不是正红）。
 *  唱臂是“机械件”，与波形强调色（青绿）区分开。 */
val TonearmColor = Color(0xFFD4574E)
