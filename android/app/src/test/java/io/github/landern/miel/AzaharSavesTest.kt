package io.github.landern.miel

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import org.junit.Assert.assertEquals
import org.junit.Test

class AzaharSavesTest {
    private fun header(magic: String, at: Int, id: Long): ByteArray {
        val bytes = ByteArray(0x120)
        magic.toByteArray().copyInto(bytes, 0x100)
        ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).putLong(at, id)
        return bytes
    }

    @Test
    fun readsTheTitleIdFromCartridgeAndProgramHeaders() {
        assertEquals(
            0x0004000000055D00,
            AzaharSaves.titleId(header("NCSD", 0x108, 0x0004000000055D00)),
        )
        assertEquals(
            0x0004000000054000,
            AzaharSaves.titleId(header("NCCH", 0x118, 0x0004000000054000)),
        )
        assertEquals("not a 3DS ROM", 0L, AzaharSaves.titleId(header("PK\u0003\u0004", 0x108, 1)))
    }
}
