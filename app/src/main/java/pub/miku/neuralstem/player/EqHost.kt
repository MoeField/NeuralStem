package pub.miku.neuralstem.player

import android.os.Handler
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.3.1 Step 1：EQ 宿主。
 *
 * 从 `PlaybackService` 抽出的单一职责类：只负责
 * `EqController` 的挂载/重试/快照与 EQ 命令，不持有 Service 生命周期。
 *
 * 服务侧保留同名 thin delegate（`attachSingleEq`/`attachMultiTrackEq`/`eqSnapshotJson`…），
 * 调用点零改动。
 */
internal class EqHost(
    private val handler: Handler,
    private val requestSnapshot: () -> Unit,
    private val isInMultiTrack: () -> Boolean,
    private val currentMultiTrack: () -> MultiTrackController?,
) {
    private val controller = EqController()
    private var retry: Runnable? = null

    val bandCount: Int get() = controller.bandCount

    /** 单轨 EQ：挂到主播放器会话。sessionId=0 时 no-op。 */
    fun attachSingle(sessionId: Int) {
        Log.i(TAG, "attachSingle: audioSessionId=$sessionId")
        if (sessionId != 0) {
            controller.attach("single", sessionId)
            requestSnapshot()
        }
    }

    /**
     * 多轨 EQ：只挂一份在混音输出上。
     * mixer session 尚未就绪时 200ms 重试；重试前确认仍是同一个 multiTrack。
     */
    fun attachMultiTrack(c: MultiTrackController) {
        val s = c.audioSessionId()
        if (s != 0) {
            controller.detachAll() // 清掉单轨那份，避免两个 session 同时生效
            controller.attach("mix", s)
            Log.i(TAG, "multi-track EQ attached to mixer session=$s")
            requestSnapshot()
        } else {
            Log.w(TAG, "multi-track EQ: mixer session 尚未就绪，稍后重试")
            cancelRetry()
            val r = object : Runnable {
                override fun run() {
                    retry = null
                    if (isInMultiTrack() && currentMultiTrack() === c) attachMultiTrack(c)
                }
            }
            retry = r
            handler.postDelayed(r, RETRY_MS)
        }
    }

    fun detachAll() {
        controller.detachAll()
    }

    fun cancelRetry() {
        retry?.let { handler.removeCallbacks(it) }
        retry = null
    }

    /** EQ 状态 JSON（供 UI 渲染面板）。 */
    fun snapshotJson(): String = JSONObject().apply {
        put("available", controller.available)
        put("enabled", controller.isEnabled())
        put("bands", JSONArray().apply { controller.bandFreqsHz.forEach { put(it) } })
        put("levels", JSONArray().apply { controller.readBackLevels().forEach { put(it.toInt()) } })
    }.toString()

    fun setEnabled(on: Boolean) {
        controller.setEnabled(on)
        requestSnapshot()
    }

    /** @return false = band 越界或 EQ 不可用。 */
    fun setBand(band: Int, levelMb: Short): Boolean {
        if (band < 0 || band >= controller.bandCount) return false
        controller.setBandLevel(band, levelMb)
        requestSnapshot()
        return true
    }

    fun reset() {
        controller.reset()
        requestSnapshot()
    }

    /** 诊断/自动化：低+6 / 中-6 / 高+6 dB 并开启。@return false = EQ 不可用。 */
    fun applyTestCurve(): Boolean {
        val n = controller.bandCount
        if (n == 0) return false
        val levels = ShortArray(n) { b ->
            val f = controller.bandFreqsHz.getOrElse(b) { 1000 }
            when {
                f < 300 -> 600
                f < 3000 -> -600
                else -> 600
            }
        }
        controller.setBandLevels(levels)
        controller.setEnabled(true)
        requestSnapshot()
        Log.i(TAG, "EQ_TEST bands=$n set=${levels.toList()} readBack=${controller.readBackLevels().toList()}")
        return true
    }

    /** 默认微笑曲线（低+3/高+3）。@return false = EQ 不可用。 */
    fun applyDefaultCurve(): Boolean {
        if (controller.bandCount == 0) return false
        controller.defaultCurve()
        requestSnapshot()
        Log.i(TAG, "EQ_DEFAULT_CURVE readBack=${controller.readBackLevels().toList()}")
        return true
    }

    companion object {
        private const val TAG = "EqHost"
        private const val RETRY_MS = 200L
    }
}
