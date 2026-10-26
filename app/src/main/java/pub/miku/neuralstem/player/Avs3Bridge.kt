package pub.miku.neuralstem.player

import android.app.Application
import android.net.Uri
import android.util.Log

/**
 * ★`main` 源集 ↔ `ncm` 源集的**反射桥**。
 *
 * ## 为什么需要它
 * AVS3 的全部实现都在 `app/src/ncm/`（只在 `-Pwith_ncm=true` 时参与编译），
 * 而 `PlayerViewModel` 在 `main` 源集 —— **默认构建下 `ncm` 源集根本不存在**，
 * 直接 `import` 会编译失败。
 *
 * 所以走反射。这与项目里 `NcmMemoryDataSource` / `NcmImportViewHolder` 的处理方式一致。
 *
 * ## 代价与取舍
 * 反射没有编译期检查 —— 方法签名改了不会报错，只在运行时炸。
 * 所以这里每个反射点都：
 * 1. 用 `runCatching` 包住，失败时**优雅降级**（当作「不是 AVS3」或「解不了」）
 * 2. 类名/方法名集中在本文件，改的时候只需改一处
 *
 * ## 默认构建下的行为
 * - [shouldIntercept] 永远 false（类找不到）→ 走原播放路径，完全无副作用
 * - [prepare] 返回 Failed → UI 提示不可播
 */
object Avs3Bridge {

    private const val TAG = "Avs3Bridge"

    private const val CLS_COORDINATOR =
        "pub.miku.neuralstem.experimental.ncm.Avs3PlaybackCoordinator"

    private const val CLS_PROGRESS =
        "pub.miku.neuralstem.experimental.ncm.Avs3PlaybackCoordinator\$Progress"

    /** 这个构建里到底有没有 AVS3 支持。 */
    val available: Boolean by lazy {
        runCatching { Class.forName(CLS_COORDINATOR) }.isSuccess
    }

    /**
     * 是否需要把 uri 交给 AVS3 流程处理。
     *
     * 判断依据：文件是 `.m4a`，且内部 codec 是 `av3a`。
     * 注意**不能只看扩展名** —— 实测 220 个 m4a 里 219 个是 av3a，
     * 但也有真 AAC 的 m4a，那些应该走原生路径（更快、无需解码）。
     */
    fun shouldIntercept(uri: Uri): Boolean {
        if (!available) return false
        return runCatching {
            val cls = Class.forName(CLS_COORDINATOR)
            val inst = cls.getField("INSTANCE").get(null)
            cls.getMethod("isAvs3Source", Uri::class.java)
                .invoke(inst, uri) as? Boolean ?: false
        }.onFailure {
            Log.w(TAG, "isAvs3Source 反射失败", it)
        }.getOrDefault(false)
    }

    /** 转换结果（与 ncm 侧 `Avs3PlaybackCoordinator.Prepared` 一一对应）。 */
    sealed interface Prepared {
        data class Ready(val uri: Uri) : Prepared
        data class Failed(val message: String) : Prepared
    }

    /**
     * 把源 uri 转成可播 uri（缓存命中直接返回；否则解码 + 写缓存）。
     *
     * ⚠️ **阻塞调用**（解码要几十秒），必须在 `Dispatchers.IO` 里跑。
     *
     * @param onProgress (标识, 0..100)。标识用的是**源路径**，
     *        与 ncm 侧 `Progress.onProgress(uri: String, ...)` 的约定一致
     *        （那边用 path 当 key，因为缓存键由 path 算）。
     * @param shouldCancel 每帧询问一次，返回 true 就中止
     */
    fun prepare(
        app: Application,
        srcUri: Uri,
        srcPath: String,
        onProgress: ((String, Int) -> Unit)?,
        shouldCancel: () -> Boolean,
    ): Prepared {
        if (!available) return Prepared.Failed("此构建未包含 AVS3 解码器")

        return runCatching {
            val cls = Class.forName(CLS_COORDINATOR)
            val inst = cls.getField("INSTANCE").get(null)
            val progressCls = Class.forName(CLS_PROGRESS)

            // Progress 是 fun interface → 用动态代理实现它
            val progressProxy = onProgress?.let { cb ->
                java.lang.reflect.Proxy.newProxyInstance(
                    progressCls.classLoader,
                    arrayOf(progressCls),
                ) { _, method, args ->
                    when (method.name) {
                        "onProgress" -> {
                            val id = args?.getOrNull(0) as? String
                            val pct = args?.getOrNull(1) as? Int
                            if (id != null && pct != null) cb(id, pct)
                            null
                        }
                        "hashCode" -> System.identityHashCode(progressCls)
                        "equals" -> false
                        "toString" -> "Avs3ProgressProxy"
                        else -> null
                    }
                }
            }

            val res = cls.getMethod(
                "prepare",
                Application::class.java,
                Uri::class.java,
                String::class.java,
                progressCls,
                kotlin.jvm.functions.Function0::class.java,
            ).invoke(
                inst, app, srcUri, srcPath, progressProxy,
                object : kotlin.jvm.functions.Function0<Boolean> {
                    override fun invoke(): Boolean = shouldCancel()
                },
            ) ?: return Prepared.Failed("prepare 返回 null")

            // 解包 Prepared.Ready / Failed
            val rc = res.javaClass
            when (rc.simpleName) {
                "Ready" -> Prepared.Ready(rc.getMethod("getUri").invoke(res) as Uri)
                "Failed" -> Prepared.Failed(
                    rc.getMethod("getMessage").invoke(res) as? String ?: "未知错误",
                )
                else -> Prepared.Failed("未知返回类型 ${rc.simpleName}")
            }
        }.getOrElse {
            Log.w(TAG, "prepare 反射失败", it)
            Prepared.Failed(it.message ?: "解码失败")
        }
    }
}
