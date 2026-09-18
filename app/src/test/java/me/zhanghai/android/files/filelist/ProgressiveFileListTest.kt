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
}
