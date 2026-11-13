package pub.miku.neuralstem.spike

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentTrackBridgeMetaTest {

    @Test
    fun reads_bridged_metadata_and_source_hash() {
        val f = File.createTempFile("bridge", ".sepmeta")
        try {
            Properties().apply {
                setProperty("uri", "content://music/who.flac")
                setProperty("displayName", "who.flac")
                setProperty("ext", "flac")
                setProperty("hash", "0123456789abcdef")
            }.also { props -> f.outputStream().use { props.store(it, "test") } }

            val src = CurrentTrackBridge.readBridgedSource(f)!!
            assertEquals("content://music/who.flac", src.uri)
            assertEquals("who.flac", src.displayName)
            assertEquals("flac", src.extension)
            assertEquals("0123456789abcdef", src.contentHash)
            assertEquals("0123456789abcdef", CurrentTrackBridge.sourceHash(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun malformed_metadata_returns_null() {
        val f = File.createTempFile("bridge", ".sepmeta")
        try {
            f.writeText("not a properties file")
            assertNull(CurrentTrackBridge.readBridgedSource(f))
            assertNull(CurrentTrackBridge.sourceHash(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun non_meta_file_returns_null() {
        val f = File.createTempFile("bridge", ".flac")
        try {
            f.writeText("raw")
            assertNull(CurrentTrackBridge.readBridgedSource(f))
        } finally {
            f.delete()
        }
    }
}
