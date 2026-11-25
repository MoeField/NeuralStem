package pub.miku.neuralstem.spike

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** P1-A：缓存命中时从完整 WAV 头读回真实 totalFrames，从而还原 coverage.complete。 */
class SeparationRepositoryCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `完整 WAV 读回正确帧数`() {
        val f = tmp.newFile("vocals.wav")
        val frames = 1000
        WavWriter(f, 2, 44100).use { w -> w.append(FloatArray(frames * 2)) }
        assertEquals(frames, SeparationRepository.readWavTotalFrames(f))
    }

    @Test
    fun `损坏或空文件回退 0`() {
        val f = tmp.newFile("broken.wav")
        f.writeText("not a wav")
        assertEquals(0, SeparationRepository.readWavTotalFrames(f))
    }
}
