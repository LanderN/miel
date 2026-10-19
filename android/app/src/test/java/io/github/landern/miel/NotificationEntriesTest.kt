package io.github.landern.miel

import org.junit.Assert.*
import org.junit.Test

class NotificationEntriesTest {
    @Test fun staleCompletionCannotDismissNewDownload() {
        val entries = NotificationEntries<String, Any>()
        val completed = Any()
        val downloading = Any()
        entries.put("art", completed)
        entries.put("art", downloading)
        assertFalse(entries.expire("art", completed))
        assertSame(downloading, entries.latest)
        assertTrue(entries.expire("art", downloading))
        assertNull(entries.latest)
    }

    @Test fun cancellingOneProducerKeepsTheOther() {
        val entries = NotificationEntries<String, Any>()
        val metadata = Any()
        val art = Any()
        entries.put("metadata", metadata)
        entries.put("art", art)
        entries.remove("metadata")
        assertSame(art, entries.latest)
        entries.remove("art")
        assertNull(entries.latest)
    }

    @Test fun latestUpdateWinsAndExpiredBackgroundEntryDoesNotReturn() {
        val entries = NotificationEntries<String, Any>()
        val metadata = Any()
        val art = Any()
        entries.put("metadata", metadata)
        entries.put("art", art)
        assertTrue(entries.expire("metadata", metadata))
        assertSame(art, entries.latest)
        entries.remove("art")
        assertNull(entries.latest)
    }
}
