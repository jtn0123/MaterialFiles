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
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.provider.common.EncryptedFileAttributes
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileItemAccessibilityTest {
    @Test fun aFileIsReadWithItsTypeTimeAndSize() {
        assertEquals(
            "a.jpg, image/jpeg, time 1000, size 1024",
            fileItem("/dir/a.jpg").getSpokenDescription(TestStrings)
        )
    }

    @Test fun aFolderIsReadWithoutTimeOrSize() {
        assertEquals(
            "Music, $FOLDER",
            directory("/dir/Music").getSpokenDescription(TestStrings)
        )
    }

    @Test fun anAppFolderSaysSo() {
        val file = directory("/Android/data/com.example.app")
        assertEquals(listOf(FileItemSpokenFlag.APP_DIRECTORY), file.spokenFlags)
        assertEquals(
            "com.example.app, $FOLDER, APP_DIRECTORY",
            file.getSpokenDescription(TestStrings)
        )
    }

    @Test fun anEncryptedFileSaysSo() {
        val file = fileItem("/dir/secret.zip", isEncrypted = true)
        assertEquals(listOf(FileItemSpokenFlag.ENCRYPTED), file.spokenFlags)
        assertTrue(file.getSpokenDescription(TestStrings).contains(", ENCRYPTED, "))
    }

    @Test fun aLinkIsReadWithTheTypeOfItsTarget() {
        val link = symbolicLink(TestAttributes())
        assertEquals(listOf(FileItemSpokenFlag.SYMBOLIC_LINK), link.spokenFlags)
        assertEquals(
            "link, image/jpeg, SYMBOLIC_LINK, time 1000, size 1024",
            link.getSpokenDescription(TestStrings)
        )
    }

    @Test fun aLinkToAFolderIsReadAsAFolder() {
        val link = symbolicLink(TestAttributes(Type.DIRECTORY), MimeType.DIRECTORY)
        assertEquals(
            "link, $FOLDER, SYMBOLIC_LINK",
            link.getSpokenDescription(TestStrings)
        )
    }

    @Test fun aBrokenLinkIsReadWithoutAType() {
        val link = symbolicLink(null)
        assertEquals(listOf(FileItemSpokenFlag.BROKEN_SYMBOLIC_LINK), link.spokenFlags)
        assertEquals(
            "link, BROKEN_SYMBOLIC_LINK, time 1000, size 0",
            link.getSpokenDescription(TestStrings)
        )
    }

    @Test fun anOrdinaryFileHasNoFlags() {
        assertTrue(fileItem("/dir/a.jpg").spokenFlags.isEmpty())
        // Only a folder's name is taken for an app's package name.
        assertTrue(fileItem("/dir/com.example.app").spokenFlags.isEmpty())
    }

    @Test fun theSelectionStateIsOnlyAnnouncedWhileSelecting() {
        assertNull(getFileItemSelectionStateRes(isSelected = false, isSelecting = false))
        assertEquals(
            R.string.file_item_state_not_selected,
            getFileItemSelectionStateRes(isSelected = false, isSelecting = true)
        )
        assertEquals(
            R.string.file_item_state_selected,
            getFileItemSelectionStateRes(isSelected = true, isSelecting = true)
        )
    }

    @Test fun outsideSelectionATapOpensAndALongPressSelects() {
        val labels = getFileItemActionLabels(
            isSelectable = true,
            isSelected = false,
            isSelecting = false
        )
        assertEquals(R.string.file_item_action_open, labels.click)
        assertEquals(R.string.select, labels.longClick)
    }

    @Test fun whileSelectingATapTogglesAndALongPressOpens() {
        val unselected = getFileItemActionLabels(
            isSelectable = true,
            isSelected = false,
            isSelecting = true
        )
        assertEquals(R.string.select, unselected.click)
        assertEquals(R.string.file_item_action_open, unselected.longClick)
        val selected = getFileItemActionLabels(
            isSelectable = true,
            isSelected = true,
            isSelecting = true
        )
        assertEquals(R.string.file_item_action_deselect, selected.click)
        assertEquals(R.string.file_item_action_open, selected.longClick)
    }

    @Test fun anUnselectableFileHasNoSelectLabel() {
        val idle = getFileItemActionLabels(
            isSelectable = false,
            isSelected = false,
            isSelecting = false
        )
        assertEquals(R.string.file_item_action_open, idle.click)
        assertNull(idle.longClick)
        val selecting = getFileItemActionLabels(
            isSelectable = false,
            isSelected = false,
            isSelecting = true
        )
        assertNull(selecting.click)
        assertEquals(R.string.file_item_action_open, selecting.longClick)
    }

    private companion object {
        val FOLDER = MimeType.DIRECTORY.value
    }

    private object TestStrings : FileItemSpokenStrings {
        override val separator: String = ", "

        override fun getTypeName(file: FileItem): String = file.mimeType.value

        override fun formatLastModified(file: FileItem): String =
            "time ${file.attributes.lastModifiedTime().toMillis()}"

        override fun formatSize(file: FileItem): String = "size ${file.attributes.size()}"

        override fun getFlagName(flag: FileItemSpokenFlag): String = flag.name
    }

    private fun fileItem(path: String, isEncrypted: Boolean = false): FileItem = FileItem(
        TestPath(path),
        Collator.getInstance().getCollationKey(path),
        TestAttributes(isEncrypted = isEncrypted),
        null,
        null,
        false,
        "image/jpeg".asMimeType()
    )

    private fun directory(path: String): FileItem = FileItem(
        TestPath(path),
        Collator.getInstance().getCollationKey(path),
        TestAttributes(Type.DIRECTORY),
        null,
        null,
        false,
        MimeType.DIRECTORY
    )

    private fun symbolicLink(
        targetAttributes: BasicFileAttributes?,
        mimeType: MimeType = "image/jpeg".asMimeType()
    ): FileItem = FileItem(
        TestPath("/dir/link"),
        Collator.getInstance().getCollationKey("link"),
        TestAttributes(Type.SYMBOLIC_LINK, 0),
        "target",
        targetAttributes,
        false,
        mimeType
    )

    private enum class Type { REGULAR_FILE, DIRECTORY, SYMBOLIC_LINK }

    private class TestAttributes(
        private val type: Type = Type.REGULAR_FILE,
        private val size: Long = 1024,
        private val isEncrypted: Boolean = false
    ) : BasicFileAttributes,
        EncryptedFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(1000)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(1000)

        override fun creationTime(): FileTime = FileTime.fromMillis(1000)

        override fun isRegularFile(): Boolean = type == Type.REGULAR_FILE

        override fun isDirectory(): Boolean = type == Type.DIRECTORY

        override fun isSymbolicLink(): Boolean = type == Type.SYMBOLIC_LINK

        override fun isOther(): Boolean = false

        override fun size(): Long = size

        override fun fileKey(): Any? = null

        override fun isEncrypted(): Boolean = isEncrypted
    }
}
