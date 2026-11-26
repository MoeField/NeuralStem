package pub.miku.neuralstem.player

import android.os.Bundle
import android.os.Handler
import androidx.media3.session.MediaSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.3.1 Step 2：MediaSession 快照推送。
 *
 * 从 `PlaybackService` 抽出的单一职责类：只做**去抖 + extras 合并 + 200ms 进度**，
 * 不持有业务状态（数据由 [snapshot] 回调提供，Service/Coordinator 决定内容）。
 *
 * 硬约束（原实现语义，不可丢）：
 * - `request()` 去抖成一帧一次；
 * - 每次写 extras 都基于 `session.sessionExtras` 复制（P2-5，避免抹掉另一条通道的 key）；
 * - 进度 tick 每 200ms 自循环，并在多轨时回调 [onTick]（门面对齐）。
 */
internal class SnapshotPublisher(
    private val handler: Handler,
    private val session: () -> MediaSession?,
    private val eqJson: () -> String,
    private val onTick: () -> Unit,
    private val snapshot: () -> SnapshotData?,
) {

    /** 一次快照的数据；null 表示当前不是多轨（只回传 EQ）。 */
    class SnapshotData(
        val tracksJson: String,
        val originalUri: String?,
        val originalTitle: String?,
        val speed: Float,
        val pitchRate: Float,
        val mixPosMs: Long,
        val mixDurMs: Long,
        val mixPlaying: Boolean,
        val mixEnded: Boolean,
    )

    private val requestRunnable = Runnable { pushNow() }

    /** 去抖请求：一帧内多次调用只推一次。 */
    fun request() {
        handler.removeCallbacks(requestRunnable)
        handler.post(requestRunnable)
    }

    /** 立即推送（保持原 `pushTracksSnapshot()` 的同步语义）。 */
    fun pushNow() {
        val s = session() ?: return
        // P2-5：基于现有 extras 复制，避免整体替换抹掉 KEY_MIX_*。
        val extras = Bundle(s.sessionExtras ?: Bundle())
        val d = snapshot()
        if (d != null) {
            extras.putString(MultiTrackSession.KEY_TRACKS_JSON, d.tracksJson)
            extras.putString(MultiTrackSession.KEY_ORIGINAL_URI, d.originalUri ?: "")
            extras.putString(MultiTrackSession.KEY_ORIGINAL_TITLE, d.originalTitle ?: "")
            extras.putFloat(MultiTrackSession.KEY_SPEED, d.speed)
            extras.putFloat(MultiTrackSession.KEY_PITCH_RATE, d.pitchRate)
        }
        extras.putString(MultiTrackSession.KEY_EQ_JSON, eqJson())
        s.setSessionExtras(extras)
    }

    /** 多轨进度推送：混音器位置/时长/播放态/曲尾。 */
    private val progressRunnable = object : Runnable {
        override fun run() {
            val s = session()
            val d = snapshot()
            if (d != null) {
                val b = Bundle(s?.sessionExtras ?: Bundle())
                b.putLong(MultiTrackSession.KEY_MIX_POS_MS, d.mixPosMs)
                b.putLong(MultiTrackSession.KEY_MIX_DUR_MS, d.mixDurMs)
                b.putBoolean(MultiTrackSession.KEY_MIX_PLAYING, d.mixPlaying)
                b.putBoolean(MultiTrackSession.KEY_MIX_ENDED, d.mixEnded)
                s?.setSessionExtras(b)
                onTick()
            }
            handler.postDelayed(this, PROGRESS_MS)
        }
    }

    fun startProgress() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    fun stopProgress() {
        handler.removeCallbacks(progressRunnable)
    }

    companion object {
        private const val PROGRESS_MS = 200L

        /** 轨道快照 JSON（纯函数，便于 JVM 测试）。 */
        internal fun buildTracksJson(
            tracks: List<MultiTrackController.Track>,
            soloTrackId: String?,
        ): String {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("label", t.label)
                        put("volume", t.volume.toDouble())
                        put("muted", t.muted)
                        put("solo", soloTrackId == t.id)
                        put("uri", t.uri.toString())
                    },
                )
            }
            return arr.toString()
        }
    }
}
