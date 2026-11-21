package pub.miku.neuralstem.player

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pub.miku.neuralstem.spike.WavWriter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * B3 仪器化 smoke：多轨引擎进入/播放/全静音/退出。
 *
 * 不走 Compose UI（避免测试环境重组 busy），直接驱动 MultiTrackController：
 * - load 两段合成 WAV → isReady
 * - play → 位置推进
 * - 所有轨 volume=0 → 位置仍推进（#14 回归）
 * - pause/close 不崩
 */
@RunWith(AndroidJUnit4::class)
class MultiTrackEngineSmokeTest {

    private fun wav(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.cacheDir, name)
        WavWriter(f, channels = 2, sampleRate = 44_100).use { w ->
            w.append(FloatArray(2 * 44_100 * 5) { 0.01f })
        }
        return f
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @Test
    fun 多轨播放与全静音位置推进() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val c = MultiTrackController(ctx)
        try {
            val a = wav("engine_vocals.wav")
            val b = wav("engine_accomp.wav")
            c.load(
                listOf(
                    Triple("vocals", "人声", Uri.fromFile(a)),
                    Triple("accompaniment", "伴奏", Uri.fromFile(b)),
                ),
            )
            assertTrue("mixer ready", c.isReady())
            c.play()
            Thread.sleep(1_500)
            val p1 = c.currentPositionMs()
            assertTrue("position advanced: $p1", p1 > 500)

            c.trackList.forEach { it.volume = 0f }
            c.refreshVolumes()
            val before = c.currentPositionMs()
            Thread.sleep(1_500)
            val after = c.currentPositionMs()
            assertTrue("all-mute must keep clock: $before -> $after", after > before + 500)

            c.pause()
            TimeUnit.MILLISECONDS.sleep(100)
            c.close()
        } finally {
            runCatching { c.close() }
        }
    }
}
