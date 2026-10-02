package pub.miku.neuralstem.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 主题（**F5.3：浅色 + 跟随系统**）。
 *
 * - 深色 = DJ 控制台风格（近黑背景 + 青绿强调）；
 * - 浅色 = 白底 + 更深的青绿（保证白底对比度）；
 * - **跟随系统**：默认 [isSystemInDarkTheme]。
 * - 不使用**动态取色**（`dynamicDarkColorScheme`）—— 会覆盖青绿强调色。
 */
private val NeuralStemDark = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Accent2,
    onSecondary = OnAccent2,
    background = BgDark,
    onBackground = OnBg,
    surface = SurfaceDark,
    onSurface = OnBg,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    // 容器色（底栏 NavigationBar 用 surfaceContainer；不设会落到 M3 基线紫色）
    surfaceContainer = SurfaceVariantDark,
    surfaceContainerHigh = SurfaceDark,
)

/** ★F5.3：浅色方案（白底 + 更深青绿）。 */
private val NeuralStemLight = lightColorScheme(
    primary = AccentLight,
    onPrimary = OnAccentLight,
    secondary = Accent2Light,
    onSecondary = OnAccent2Light,
    background = BgLight,
    onBackground = OnBgLight,
    surface = SurfaceLight,
    onSurface = OnBgLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = SurfaceVariantLight,
    surfaceContainerHigh = SurfaceLight,
)

@Composable
fun NeuralStemTheme(
    /** ★F5.3：默认**跟随系统**（旧调用点不传即自动适配）。 */
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NeuralStemDark else NeuralStemLight,
        typography = Typography,
        content = content,
    )
}
