package pub.miku.neuralstem.experimental.ncm

import android.content.Context
import android.view.View
import androidx.compose.ui.platform.ComposeView
import pub.miku.neuralstem.ui.theme.NeuralStemTheme

/**
 * ★把 [NcmImportScreen]（`@Composable`）包成一个 **普通 Android `View`**，
 * 供 `main` 源集的 `SettingsScreen` 用反射调用。
 *
 * ## 为何要这一层
 *
 * | 方案 | 结果 |
 * |---|---|
 * | `SettingsScreen` 直接 `import NcmImportScreen` | ❌ 默认构建（with_ncm=false）编译失败 |
 * | 反射调 `@Composable` 函数 | ❌ 字节码带 `Composer` 参数，调不了 |
 * | **→ 本类：提供一个返回 `View` 的普通函数** | ✅ 反射能调，两种构建都能编译 |
 *
 * `SettingsScreen` 用 `AndroidView(factory = { 反射调 build(...) })` 承载。
 *
 * ## 为何用 `ComposeView` 而不是 `ViewCompositionStrategy`
 * `ComposeView` 自带生命周期；AndroidView 在 factory 里只建一次，
 * 后续重组由 ComposeView 自己的合成器负责 —— 这正是我们要的（避免每帧重建）。
 */
object NcmImportViewHolder {

    /**
     * 建一个承载 NCM 导入页的 View。
     *
     * @param context 宿主 Context
     * @param onBack 返回按钮回调（由 SettingsScreen 传入，用于切回 ROOT 页）
     */
    @JvmStatic
    fun build(context: Context, onBack: Runnable): View = ComposeView(context).apply {
        setContent {
            NeuralStemTheme {
                NcmImportScreen(onBack = { onBack.run() })
            }
        }
    }
}
