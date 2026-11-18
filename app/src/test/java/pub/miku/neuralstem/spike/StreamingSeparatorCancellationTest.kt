package pub.miku.neuralstem.spike

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

/** P1.4 回归：StreamingSeparator 在下一块开始前响应取消。 */
class StreamingSeparatorCancellationTest {

    private class IdentitySeparator : ChunkSeparator {
        override fun separate(
            block: FloatArray,
            channels: Int,
            sampleRate: Int,
        ): SherpaSourceSeparation.SeparationResult =
            SherpaSourceSeparation.SeparationResult(block.copyOf(), block.copyOf(), sampleRate)
    }

    @Test
    fun `取消谓词为真时不再进入下一块`() {
        val dir = createTempDir(prefix = "stream-cancel-")
        val input = File(dir, "in.wav")
        val vocals = File(dir, "v.wav")
        val accomp = File(dir, "a.wav")
        try {
            WavWriter(input, 1, 44_100).use { it.append(FloatArray(20_000) { 0.01f }) }
            var blocks = 0
            WavReader(input).use { reader ->
                WavWriter(vocals, 1, 44_100).use { vw ->
                    WavWriter(accomp, 1, 44_100).use { aw ->
                        val ex = runCatching {
                            StreamingSeparator(
                                reader = WavFrameSource(reader),
                                vocalsWriter = vw,
                                accompWriter = aw,
                                separator = IdentitySeparator(),
                                chunkFrames = 8_000,
                                overlapFrames = 2048,
                                isCancelled = { blocks++ > 0 },
                            ).run()
                        }.exceptionOrNull()
                        assertTrue("应抛 CancellationException，实际 $ex", ex is CancellationException)
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
