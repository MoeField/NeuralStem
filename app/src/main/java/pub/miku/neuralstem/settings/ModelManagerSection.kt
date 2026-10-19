package pub.miku.neuralstem.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import pub.miku.neuralstem.R

/**
 * ★模型库的**共享小组件**（原 `ModelManagerSection` 的遗留部分）。
 *
 * ## 演进（Step 4，2026-10-20）
 * 模型列表原先内联在设置页（`ModelManagerSection`）。Step 4 把它整体搬到全屏二级页
 * [ModelLibraryScreen]（按体积分节折叠 + 实测 RTF + 删除），本文件只留下两个
 * **被新页复用**的通用件：
 * - [GithubProxySetting]：UVR 模型专用的代理前缀输入
 * - [collectAsStateInComposition]：不引 `lifecycle-runtime-compose` 的替代品
 *
 * 其余（列表卡片、许可弹窗、下载编排）已迁到 [ModelLibraryScreen]。
 */

/**
 * GitHub 代理前缀设置（UVR 模型专用）。
 *
 * ## 为何单独一个设置
 * UVR 系模型**只**在 GitHub Release（hf-mirror 镜像不了），而国内网络常
 * `github.com:443` 连不上。默认填 `https://api.gitproxy.dev`（实测可用），
 * 用户可改成自建代理或清空直连。
 */
@Composable
internal fun GithubProxySetting(repo: SettingsRepository) {
    var value by remember { mutableStateOf(repo.downloadProxy) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_models_proxy),
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            placeholder = { Text("https://api.gitproxy.dev") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { value = repo.downloadProxy }) {
                    Text(stringResource(R.string.settings_models_proxy_reset))
                }
            },
        )
        Text(
            stringResource(R.string.settings_models_proxy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 只在值真的改变时落库（避免每敲一个字符就写 prefs）
        if (value.trim() != repo.downloadProxy) {
            TextButton(onClick = { repo.downloadProxy = value }) {
                Text(stringResource(R.string.settings_models_proxy_save))
            }
        }
    }
}

/**
 * `collectAsStateWithLifecycle` 的轻量替代。
 *
 * 不引 `lifecycle-runtime-compose` 依赖（项目当前没有），
 * 用 `LaunchedEffect` + `mutableStateOf` 实现同等效果：
 * 进入 composition 开始收集，离开自动取消。
 */
@Composable
internal fun <T> StateFlow<T>.collectAsStateInComposition(): State<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(this) {
        collect { state.value = it }
    }
    return state
}
