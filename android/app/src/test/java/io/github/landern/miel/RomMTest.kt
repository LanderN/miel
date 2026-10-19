package io.github.landern.miel

import org.junit.Assert.assertEquals
import org.junit.Test

class RomMTest {
    @Test
    fun savesSyncTowardsTheSideThatChanged() {
        assertEquals("identical saves", "no_op", RomM.decide("a", "a", ""))
        assertEquals("only on the server", "download", RomM.decide("", "a", ""))
        assertEquals("only here", "upload", RomM.decide("a", "", ""))
        assertEquals("changed here", "upload", RomM.decide("b", "a", "a"))
        assertEquals("changed on the server", "download", RomM.decide("a", "b", "a"))
        assertEquals("changed on both", "conflict", RomM.decide("b", "c", "a"))
        assertEquals("differ without a last sync", "conflict", RomM.decide("b", "c", ""))
    }
}
