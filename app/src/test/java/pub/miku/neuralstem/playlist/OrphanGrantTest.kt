package pub.miku.neuralstem.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class OrphanGrantTest {

    @Test
    fun `只返回未被引用的持久授权`() {
        val persisted = listOf("content://a", "content://b", "content://tree")
        val referenced = setOf("content://a", "content://tree")
        assertEquals(listOf("content://b"), orphanGrantUris(persisted, referenced))
    }

    @Test
    fun `全部被引用时不释放`() {
        assertEquals(
            emptyList<String>(),
            orphanGrantUris(listOf("content://a"), setOf("content://a")),
        )
    }
}
