/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.ClosedFileSystemException
import java8.nio.file.ProviderMismatchException
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.common.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an archive file system does before any entry is read, so without libarchive. */
class ArchiveFileSystemTest {
    private fun newFileSystem(name: String): ArchiveFileSystem =
        ArchiveFileSystemProvider.getOrNewFileSystem(TestPath("/$name"))

    @Test
    fun aClosedArchiveRefusesEveryLookup() {
        val fileSystem = newFileSystem("closed.zip")
        val path = fileSystem.getPath("/a")
        fileSystem.close()
        assertFalse(fileSystem.isOpen)
        assertThrows(ClosedFileSystemException::class.java) { fileSystem.getEntry(path) }
        assertThrows(ClosedFileSystemException::class.java) {
            fileSystem.getDirectoryChildren(fileSystem.rootDirectory)
        }
        assertThrows(ClosedFileSystemException::class.java) { fileSystem.readSymbolicLink(path) }
        assertThrows(ClosedFileSystemException::class.java) { fileSystem.newInputStream(path) }
        assertThrows(ClosedFileSystemException::class.java) { fileSystem.refresh() }
        assertThrows(ClosedFileSystemException::class.java) { fileSystem.addPassword("secret") }
        // Closing twice is fine, and the provider hands out a new file system afterwards.
        fileSystem.close()
        assertNotSame(fileSystem, newFileSystem("closed.zip"))
    }

    @Test
    fun aSymbolicLinkIsRefusedAsReadOnlyOnceItsTargetIsUnderstood() {
        val fileSystem = newFileSystem("links.zip")
        val link = fileSystem.getPath("/link")
        assertThrows(ReadOnlyFileSystemException::class.java) {
            ArchiveFileSystemProvider.createSymbolicLink(link, fileSystem.getPath("/target"))
        }
        assertThrows(ReadOnlyFileSystemException::class.java) {
            ArchiveFileSystemProvider.createSymbolicLink(
                link,
                ByteStringPath("target".toByteString())
            )
        }
        assertThrows(ProviderMismatchException::class.java) {
            ArchiveFileSystemProvider.createSymbolicLink(link, TestPath("/target"))
        }
        fileSystem.close()
    }

    @Test
    fun anArchiveStoreIsReadOnlyAndHasNothingToRefresh() {
        val fileStore = ArchiveFileStore(TestPath("/store.zip"))
        fileStore.refresh()
        assertTrue(fileStore.isReadOnly)
        assertEquals("/store.zip", fileStore.name())
    }
}
