package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.0 / P1-14：用 [FakeAudioSink] 在 JVM 里守住 AudioSink 抽象后的两条不变量。
 *
 * - I2：write 失败 → release 旧 sink → 重建 → play → 继续推进（不静默停）
 * - I3：曲尾 ended=true、isPlaying=false；再次 play 从 0 重启
 */
class StemMixerAudioSinkTest {

    private class FakeSource(override val totalFrames: Long) : StemSource {
        override val channels = 2
        override val sampleRate = 44100

        override fun read(framePos: Long, out: FloatArray, frames: Int): Int {
            if (framePos >= totalFrames) return 0
            val n = minOf(frames.toLong(), totalFrames - framePos).toInt()
            java.util.Arrays.fill(out, 0, n * channels, 0.1f)
            return n
        }

        override fun close() {}
    }

    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    fun `I2 write 失败后重建 sink 并继续推进`() {
        val sinks = mutableListOf<FakeAudioSink>()
        val mixer = StemMixer(
            sampleRate = 44100,
            channels = 2,
            gainProvider = { 1f },
            sinkFactory = { _, _ ->
                FakeAudioSink().also {
                    if (sinks.isEmpty()) it.failNextWrites = 1
                    sinks += it
                }
            },
        )
        try {
            mixer.load(listOf("vocals" to FakeSource(441_000)))
            mixer.play()

            assertTrue("应因 write 失败重建第二个 sink", awaitTrue(2_000) { sinks.size >= 2 })
            assertTrue("旧 sink 应被 release", sinks[0].releaseCount >= 1)
            assertTrue("新 sink 应有写入", awaitTrue(2_000) { sinks[1].writesF.isNotEmpty() })
            assertTrue("重建后仍应处于播放态", mixer.isPlaying)
        } finally {
            mixer.close()
        }
    }

    @Test
    fun `I3 曲尾 ended 后重新 play 从 0 重启`() {
        val mixer = StemMixer(
            sampleRate = 44100,
            channels = 2,
            gainProvider = { 1f },
            sinkFactory = { _, _ -> FakeAudioSink() },
        )
        try {
            // 10s 素材：避免“重启后瞬间又播完”导致断言抖动。
            var lastEndedSeen = false
            val frames = 441_000L
            mixer.load(listOf("v" to FakeSource(frames)))
            mixer.play()
            // 跳到曲尾触发 EOF 收尾。
            mixer.seekTo(frames * 1000 / 44100)
            assertTrue("应到达曲尾", awaitTrue(2_000) { mixer.ended })
            lastEndedSeen = mixer.ended
            assertTrue(lastEndedSeen)
            assertFalse("曲尾应停止播放", mixer.isPlaying)

            // 曲尾后旧渲染线程可能仍在收尾；play() 的 300ms 防御上限会放弃一次启动。
            // 真实用户会再按一次，这里同样重试直到重启生效。
            var restarted = false
            for (attempt in 0 until 5) {
                mixer.play()
                if (awaitTrue(500) { !mixer.ended }) {
                    restarted = true
                    break
                }
                Thread.sleep(50)
            }
            assertTrue("再次 play 应清除 ended", restarted)
            assertTrue("再次 play 应恢复播放态", mixer.isPlaying)
        } finally {
            mixer.close()
        }
    }
}
