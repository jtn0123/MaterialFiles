/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.text.Collator
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListAdapterMenuTest {
    private val file = FileItem(
        TestPath("/dir/a.txt"),
        Collator.getInstance().getCollationKey("a.txt"),
        TestAttributes(),
        null,
        null,
        false,
        MimeType.GENERIC
    )

    @Test
    fun eachMenuItemRunsItsOwnAction() {
        val expected = mapOf(
            R.id.action_open_with to "openFileWith",
            R.id.action_cut to "cutFile",
            R.id.action_copy to "copyFile",
            R.id.action_delete to "confirmDeleteFile",
            R.id.action_rename to "showRenameFileDialog",
            R.id.action_extract to "extractFile",
            R.id.action_archive to "showCreateArchiveDialog",
            R.id.action_share to "shareFile",
            R.id.action_copy_path to "copyPath",
            R.id.action_add_bookmark to "addBookmark",
            R.id.action_create_shortcut to "createShortcut",
            R.id.action_properties to "showPropertiesDialog"
        )
        for ((itemId, action) in expected) {
            val listener = RecordingListener()
            assertTrue(listener.onFileItemMenuItemClick(itemId, file))
            assertEquals(listOf(action), listener.calls)
            assertSame(file, listener.file)
        }
    }

    @Test
    fun anUnknownItemIsLeftToOthers() {
        val listener = RecordingListener()
        assertFalse(listener.onFileItemMenuItemClick(R.id.action_select_all, file))
        assertEquals(emptyList<String>(), listener.calls)
    }

    private class RecordingListener : FileListAdapter.Listener {
        val calls = mutableListOf<String>()
        var file: FileItem? = null

        private fun record(name: String, file: FileItem) {
            calls += name
            this.file = file
        }

        override fun clearSelectedFiles() {
            calls += "clearSelectedFiles"
        }

        override fun selectFile(file: FileItem, selected: Boolean) = record("selectFile", file)

        override fun selectFiles(files: FileItemSet, selected: Boolean) {
            calls += "selectFiles"
        }

        override fun openFile(file: FileItem) = record("openFile", file)

        override fun openFileWith(file: FileItem) = record("openFileWith", file)

        override fun cutFile(file: FileItem) = record("cutFile", file)

        override fun copyFile(file: FileItem) = record("copyFile", file)

        override fun confirmDeleteFile(file: FileItem) = record("confirmDeleteFile", file)

        override fun showRenameFileDialog(file: FileItem) = record("showRenameFileDialog", file)

        override fun extractFile(file: FileItem) = record("extractFile", file)

        override fun showCreateArchiveDialog(file: FileItem) =
            record("showCreateArchiveDialog", file)

        override fun shareFile(file: FileItem) = record("shareFile", file)

        override fun copyPath(file: FileItem) = record("copyPath", file)

        override fun addBookmark(file: FileItem) = record("addBookmark", file)

        override fun createShortcut(file: FileItem) = record("createShortcut", file)

        override fun showPropertiesDialog(file: FileItem) = record("showPropertiesDialog", file)
    }

    private class TestAttributes : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)

        override fun creationTime(): FileTime = FileTime.fromMillis(0)

        override fun isRegularFile(): Boolean = true

        override fun isDirectory(): Boolean = false

        override fun isSymbolicLink(): Boolean = false

        override fun isOther(): Boolean = false

        override fun size(): Long = 0

        override fun fileKey(): Any? = null
    }
}
