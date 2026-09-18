package me.zhanghai.android.files.filelist

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveFileListTest {
    @Test fun firstFileIsPublishedBeforeTheRestAreRead() {
        val published = mutableListOf<List<Int>>()
        val loader = ProgressiveFileList<Int, Int>({ value ->
            if (value == 2) assertEquals(listOf(listOf(1)), published)
            value
        }, { published += it }, { 0L })
        loader.add(listOf(1, 2, 3))
        assertEquals(listOf(1), published.first())
        assertEquals(listOf(1, 2, 3), loader.snapshot)
    }

    @Test fun failedFirstEntryDoesNotPublishEmptyRowsOrDelayFirstSuccess() {
        val published = mutableListOf<List<Int>>()
        val loader = ProgressiveFileList<Int, Int>({
            if (it == 1) throw IOException("unreadable")
            it
        }, { published += it }, { 900_000_000L })
        loader.add(listOf(1))
        assertTrue(published.isEmpty())
        loader.add(listOf(2))
        assertEquals(listOf(listOf(2)), published)
        assertNotNull(loader.problem)
    }

    @Test fun metadataFailureProducesPartialResultWithError() {
        val loader = ProgressiveFileList<Int, Int>({
            if (it == 2) throw IOException("metadata unavailable")
            it
        }, {})
        loader.add(listOf(1, 2, 3))
        assertEquals(listOf(1, 3), loader.snapshot)
        assertNotNull(loader.problem)
        assertEquals(1, (loader.problem as PartialFileListException).missingCount)
    }

    @Test fun allFailedEntriesAreNotReportedAsAnEmptySuccess() {
        val loader = ProgressiveFileList<Int, Int>({ throw IOException() }, {})
        loader.add(listOf(1, 2))
        assertTrue(loader.snapshot.isEmpty())
        assertEquals(2, (loader.problem as PartialFileListException).missingCount)
    }

    @Test fun publishesAgainOnlyAfterThrottleInterval() {
        var time = 0L
        val published = mutableListOf<List<Int>>()
        val loader = ProgressiveFileList<Int, Int>({ it }, { published += it }, { time })
        loader.add(listOf(1))
        time = 499_999_999L
        loader.add(listOf(2))
        assertEquals(1, published.size)
        time = 500_000_001L
        loader.add(listOf(3))
        assertEquals(listOf(listOf(1), listOf(1, 2, 3)), published)
    }

    @Test fun interruptedMetadataReadPropagatesTheSameException() {
        val interruption = java.io.InterruptedIOException("Canceled")
        val loader = ProgressiveFileList<Int, Int>({ throw interruption }, {})
        org.junit.Assert.assertSame(
            interruption,
            org.junit.Assert.assertThrows(
                java.io.InterruptedIOException::class.java
            ) { loader.add(listOf(1)) }
        )
    }

    @Test fun iteratorFailureRetainsEntriesAndReportsPartialResults() {
        val entries = Iterable {
            object : Iterator<Int> {
                private var read = false
                override fun hasNext(): Boolean {
                    if (read) {
                        throw java8.nio.file.DirectoryIteratorException(
                            IOException("Lost connection")
                        )
                    }
                    return true
                }
                override fun next(): Int {
                    read = true
                    return 1
                }
            }
        }
        val loader = ProgressiveFileList<Int, Int>({ it }, {})
        loader.add(entries)
        assertEquals(listOf(1), loader.snapshot)
        assertEquals(1, (loader.problem as PartialFileListException).missingCount)
    }
}
