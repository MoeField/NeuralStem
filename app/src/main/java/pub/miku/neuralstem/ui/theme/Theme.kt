package pub.miku.neuralstem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 阶段 UI / U0：**仅深色**主题（DJ 控制台风格）。
 *
 * 用户决策：「只提供深色吧」。
 * - 不再依赖系统深浅色（去掉 `isSystemInDarkTheme`）；
 * - 不再使用**动态取色**（`dynamicDarkColorScheme`）—— 那会覆盖我们的青绿强调色；
 * - 保留 `darkTheme` 形参仅为**兼容旧调用点**（已恒为 true）。
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

@Composable
fun NeuralStemTheme(
    // 兼容旧调用点；恒为深色
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = NeuralStemDark,
        typography = Typography,
        content = content,
    )
}
