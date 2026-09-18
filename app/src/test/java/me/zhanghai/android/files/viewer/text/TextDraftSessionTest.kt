package me.zhanghai.android.files.viewer.text

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextDraftSessionTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun newSessionReadsAfterPreviouslyQueuedWriteAndClear() = runBlocking {
        val errors = java.util.concurrent.CopyOnWriteArrayList<Exception>()
        val first = TextDraftSession(TextDraftStore(directory.root, "file:///a")) { errors.add(it) }
        assertNull(first.read())
        val draft = TextDraft("ordered edit", "UTF-8", 0, 0)
        first.write(draft)
        val second =
            TextDraftSession(TextDraftStore(directory.root, "file:///a")) { errors.add(it) }
        assertEquals(draft, second.read())
        second.clear()
        val third = TextDraftSession(TextDraftStore(directory.root, "file:///a")) { errors.add(it) }
        assertNull(third.read())
        assertEquals(emptyList<Exception>(), errors.toList())
    }
}
