package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRangeTest {

    @Test
    fun `解析 Content-Range 起点`() {
        assertEquals(1024L, parseContentRangeStart("bytes 1024-2047/2048"))
        assertEquals(0L, parseContentRangeStart("bytes 0-99/100"))
        assertNull(parseContentRangeStart(null))
        assertNull(parseContentRangeStart(""))
        assertNull(parseContentRangeStart("items 1-2/3"))
    }
}
