/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.text.Collator
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.provider.common.EncryptedFileAttributes
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListItemContentsTest {
    @Test fun reListedFileWithFreshAttributesObjectsIsTheSame() {
        val old = fileItem()
        val new = fileItem()
        // Attribute classes have no equals(), which is what used to rebind every row.
        assertFalse(old == new)
        assertTrue(old.hasSameListContentsAs(new))
    }

    @Test fun sizeChangeIsDifferent() {
        assertFalse(fileItem().hasSameListContentsAs(fileItem(size = 2048)))
    }

    @Test fun lastModifiedChangeIsDifferent() {
        assertFalse(fileItem().hasSameListContentsAs(fileItem(lastModified = 2000)))
    }

    @Test fun pathChangeIsDifferent() {
        assertFalse(fileItem().hasSameListContentsAs(fileItem(path = "/dir/b.jpg")))
    }

    @Test fun mimeTypeChangeIsDifferent() {
        assertFalse(
            fileItem().hasSameListContentsAs(fileItem(mimeType = "image/png".asMimeType()))
        )
    }

    @Test fun hiddenChangeIsDifferent() {
        assertFalse(fileItem().hasSameListContentsAs(fileItem(isHidden = true)))
    }

    @Test fun typeChangeIsDifferent() {
        assertFalse(
            fileItem().hasSameListContentsAs(
                fileItem(type = Type.DIRECTORY, mimeType = MimeType.DIRECTORY)
            )
        )
    }

    @Test fun encryptionChangeIsDifferent() {
        assertFalse(fileItem().hasSameListContentsAs(fileItem(isEncrypted = true)))
        assertTrue(
            fileItem(isEncrypted = true).hasSameListContentsAs(fileItem(isEncrypted = true))
        )
    }

    @Test fun symbolicLinkTargetChangeIsDifferent() {
        assertTrue(symbolicLink("x.jpg").hasSameListContentsAs(symbolicLink("x.jpg")))
        assertFalse(symbolicLink("x.jpg").hasSameListContentsAs(symbolicLink("y.jpg")))
    }

    @Test fun symbolicLinkBreakingIsDifferent() {
        assertFalse(symbolicLink("x.jpg").hasSameListContentsAs(symbolicLink("x.jpg", false)))
    }

    @Test fun symbolicLinkTargetAttributesChangeIsDifferent() {
        assertFalse(
            symbolicLink("x.jpg").hasSameListContentsAs(
                symbolicLink("x.jpg", targetAttributes = TestAttributes(size = 1))
            )
        )
    }

    private fun fileItem(
        path: String = "/dir/a.jpg",
        type: Type = Type.REGULAR_FILE,
        size: Long = 1024,
        lastModified: Long = 1000,
        isEncrypted: Boolean = false,
        isHidden: Boolean = false,
        mimeType: MimeType = "image/jpeg".asMimeType()
    ): FileItem = FileItem(
        TestPath(path),
        Collator.getInstance().getCollationKey(path),
        TestAttributes(type, size, lastModified, isEncrypted),
        null,
        null,
        isHidden,
        mimeType
    )

    private fun symbolicLink(
        target: String,
        isTargetReadable: Boolean = true,
        targetAttributes: BasicFileAttributes = TestAttributes()
    ): FileItem = FileItem(
        TestPath("/dir/link"),
        Collator.getInstance().getCollationKey("link"),
        TestAttributes(Type.SYMBOLIC_LINK, 0, 1000),
        target,
        if (isTargetReadable) targetAttributes else null,
        false,
        "image/jpeg".asMimeType()
    )

    private enum class Type { REGULAR_FILE, DIRECTORY, SYMBOLIC_LINK }

    private class TestAttributes(
        private val type: Type = Type.REGULAR_FILE,
        private val size: Long = 1024,
        private val lastModified: Long = 1000,
        private val isEncrypted: Boolean = false
    ) : BasicFileAttributes,
        EncryptedFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(lastModified)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(lastModified)

        override fun creationTime(): FileTime = FileTime.fromMillis(lastModified)

        override fun isRegularFile(): Boolean = type == Type.REGULAR_FILE

        override fun isDirectory(): Boolean = type == Type.DIRECTORY

        override fun isSymbolicLink(): Boolean = type == Type.SYMBOLIC_LINK

        override fun isOther(): Boolean = false

        override fun size(): Long = size

        override fun fileKey(): Any? = null

        override fun isEncrypted(): Boolean = isEncrypted
    }
}
