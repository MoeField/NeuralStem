package pub.miku.neuralstem.playlist

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantReleaseFlagsTest {

    @Test
    fun `按实际 READ WRITE 组合`() {
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            grantReleaseFlags(read = true, write = true),
        )
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, grantReleaseFlags(true, false))
        assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, grantReleaseFlags(false, true))
        assertEquals(0, grantReleaseFlags(false, false))
    }
}
