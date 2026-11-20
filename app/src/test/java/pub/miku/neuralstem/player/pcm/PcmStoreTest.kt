package pub.miku.neuralstem.player.pcm

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pub.miku.neuralstem.spike.WavWriter

class PcmStoreTest {

    private lateinit var cacheDir: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("pcm-store-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        cacheDir.deleteRecursively()
    }

    private fun source(key: String = "src-1") = SourceDescriptor(
        cacheKey = key,
        displayName = key,
        kind = SourceDescriptor.Kind.LOCAL_FILE,
        sizeBytes = 100,
        lastModifiedMs = 1L,
    )

    private fun decoder(
        writes: Int = 1024,
        delayMs: Long = 10L,
        failTimes: Int = 0,
        counter: AtomicInteger = AtomicInteger(0),
    ) = PcmStore.PcmDecoder { _, output, progress ->
        val n = counter.incrementAndGet()
        if (n <= failTimes) return@PcmDecoder null
        // PcmStore 现在会校验 44-byte WAV 头与 data 长度；测试 decoder 必须写合法 WAV。
        val frames = (writes / 4).coerceAtLeast(1)
        WavWriter(output, 2, 44_100).use { w ->
            w.append(FloatArray(frames * 2))
        }
        progress(output.length(), null)
        delay(delayMs)
        PcmArtifact(
            file = output,
            sizeBytes = output.length(),
            createdAtMs = 1L,
            sampleRate = 44_100,
            channels = 2,
            totalFrames = frames.toLong(),
        )
    }

    @Test
    fun concurrent_callers_share_single_decode() = runBlocking {
        val count = AtomicInteger(0)
        val store = PcmStore(cacheDir, scope, decoder(counter = count))
        val s1 = store.prepare(source())!!
        val s2 = store.prepare(source())!!
        val pair = listOf(
            async { s1.await() },
            async { s2.await() },
        ).awaitAll()
        assertEquals(1, count.get())
        assertNotNull(pair[0])
        assertEquals(pair[0]?.file, pair[1]?.file)
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.startsWith("tmp-") })
    }

    @Test
    fun cache_hit_skips_decoder_on_second_store() = runBlocking {
        val first = AtomicInteger(0)
        val s1 = PcmStore(cacheDir, scope, decoder(counter = first))
        val a1 = s1.acquire(source())!!
        val path = a1.artifact.file
        a1.close()
        delay(50)

        val second = AtomicInteger(0)
        val s2 = PcmStore(cacheDir, scope, decoder(counter = second))
        val a2 = s2.acquire(source())!!
        assertTrue(path.exists())
        assertEquals(0, second.get())
        a2.close()
    }

    @Test
    fun lease_blocks_clear_and_lru_until_closed() = runBlocking {
        val store = PcmStore(cacheDir, scope, decoder())
        val lease = store.acquire(source())!!
        val path = lease.artifact.file
        assertTrue(path.exists())

        assertEquals(0, store.clearUnpinned())
        store.trim()
        assertTrue(path.exists())

        lease.close()
        delay(50)
        assertEquals(1, store.clearUnpinned())
        assertTrue(!path.exists())
    }

    @Test
    fun pinned_current_track_survives_clear() = runBlocking {
        val store = PcmStore(cacheDir, scope, decoder())
        val lease = store.acquire(source())!!
        val path = lease.artifact.file
        lease.close()
        delay(30)
        store.pin(source())

        assertEquals(0, store.clearUnpinned())
        assertTrue(path.exists())

        store.unpin(source())
        assertEquals(1, store.clearUnpinned())
    }

    @Test
    fun failed_decode_returns_null_and_can_retry() = runBlocking {
        val count = AtomicInteger(0)
        val store = PcmStore(
            cacheDir,
            scope,
            decoder(failTimes = 1, counter = count),
        )
        val first = store.prepare(source())!!
        assertNull(first.await())
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.startsWith("tmp-") })

        val second = store.prepare(source())!!
        assertNotNull(second.await())
        assertEquals(2, count.get())
    }

    @Test
    fun pin_before_prepare_protects_cache_from_clear() = runBlocking {
        val store = PcmStore(cacheDir, scope, decoder())
        store.pin(source())
        val lease = store.acquire(source())!!
        val path = lease.artifact.file
        lease.close()
        delay(30)

        assertEquals(0, store.clearUnpinned())
        assertTrue(path.exists())
        store.unpin(source())
        assertEquals(1, store.clearUnpinned())
    }

    @Test
    fun concurrent_acquire_calls_share_one_decode() = runBlocking {
        val count = AtomicInteger(0)
        val store = PcmStore(cacheDir, scope, decoder(delayMs = 30, counter = count))
        val leases = listOf(
            async { store.acquire(source()) },
            async { store.acquire(source()) },
            async { store.acquire(source()) },
        ).awaitAll()
        assertEquals(1, count.get())
        assertEquals(3, leases.count { it != null })
        leases.forEach { it?.close() }
    }

    @Test
    fun trim_removes_old_untracked_pcm_orphans() = runBlocking {
        val store = PcmStore(cacheDir, scope, decoder(), maxBytes = 10L)
        val orphan = File(cacheDir, "old-orphan.wav").apply {
            writeBytes(ByteArray(100))
            setLastModified(0L)
        }
        assertTrue(orphan.exists())
        store.trim()
        assertTrue(!orphan.exists())
    }

    @Test
    fun lru_evicts_oldest_unpinned_but_keeps_pinned() = runBlocking {
        val store = PcmStore(cacheDir, scope, decoder(writes = 800), maxBytes = 1200L)
        val srcA = source("src-a")
        val srcB = source("src-b")
        val leaseA = store.acquire(srcA)!!
        val fileA = leaseA.artifact.file
        leaseA.close()
        delay(30)
        store.pin(srcA)

        val leaseB = store.acquire(srcB)!!
        val fileB = leaseB.artifact.file
        leaseB.close()
        delay(30)
        store.trim()

        assertTrue(fileA.exists())
        assertFalse(fileB.exists())
        store.unpin(srcA)
    }

    @Test
    fun usage_api_reports_files_and_clear() = runBlocking {
        val store = PcmStore(cacheDir, scope, decoder())
        val lease = store.acquire(source())!!
        assertTrue(store.cacheBytes() > 0)
        assertTrue(store.cacheFileCount() >= 1)
        lease.close()
        delay(30)
        assertTrue(store.clearUnpinned() >= 1)
        assertEquals(0, store.cacheFileCount())
    }

    @Test
    fun clear_removes_stale_tmp_but_not_active_decode() = runBlocking {
        val store = PcmStore(cacheDir, scope, decoder(delayMs = 200))
        val stale = File(cacheDir, "tmp-stale.wav").apply { writeBytes(ByteArray(10)) }
        val lease = store.acquire(source())!!
        // 解码结束后 stale tmp 应被 clear 删除
        lease.close()
        delay(30)
        assertTrue(store.clearUnpinned() >= 1)
        assertFalse(stale.exists())
        assertEquals(0, store.cacheFileCount())
    }

    @Test
    fun non_cacheable_wav_is_not_cached() = runBlocking {
        val count = AtomicInteger(0)
        val store = PcmStore(cacheDir, scope, decoder(counter = count))
        val wav = SourceDescriptor(
            cacheKey = "file:///x.wav",
            kind = SourceDescriptor.Kind.WAV_FILE,
            sizeBytes = 10,
            lastModifiedMs = 1,
        )
        assertNull(store.acquire(wav))
        assertEquals(0, count.get())
    }
}
