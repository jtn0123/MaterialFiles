package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextDraftStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun newStoreInstanceRecoversLargeUnicodeDraftAndCursor() {
        val draft = TextDraft("hello 🌎".repeat(20000), "UTF-16", 4, 9)
        TextDraftStore(directory.root, "file:///a").write(draft)
        assertEquals(draft, TextDraftStore(directory.root, "file:///a").read())
        assertNull(TextDraftStore(directory.root, "file:///b").read())
    }

    @Test fun savedOrDiscardedDraftDoesNotReturn() {
        val store = TextDraftStore(directory.root, "file:///a")
        store.write(TextDraft("work", "UTF-8", 0, 0))
        store.clear()
        assertNull(TextDraftStore(directory.root, "file:///a").read())
    }

    @Test fun staleEditorCannotReplaceOrDeleteNewerDraft() {
        val oldEditor = TextDraftStore(directory.root, "file:///a")
        oldEditor.write(TextDraft("first", "UTF-8", 0, 0))
        val newEditor = TextDraftStore(directory.root, "file:///a")
        newEditor.read()
        val newer = TextDraft("newer edits", "UTF-8", 0, 0)
        newEditor.write(newer)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            oldEditor.write(TextDraft("stale", "UTF-8", 0, 0))
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { oldEditor.clear() }
        assertEquals(newer, TextDraftStore(directory.root, "file:///a").read())
    }
}
