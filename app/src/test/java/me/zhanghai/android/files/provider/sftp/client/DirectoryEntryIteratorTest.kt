/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.IOException
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** An SFTP listing is read a batch at a time, as the entries are asked for. */
class DirectoryEntryIteratorTest {
    private class FakeDirectory(vararg batches: List<String>) : DirectoryBatchReader {
        private val remaining = ArrayDeque(batches.toList())
        var readCount = 0
        var closeCount = 0
        var readFailure: IOException? = null
        var closeFailure: IOException? = null

        override fun readBatch(): List<DirectoryEntry>? {
            readCount++
            readFailure?.let { throw it }
            return remaining.removeFirstOrNull()?.map { DirectoryEntry(it, attributes(it)) }
        }

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }

    private data class TestPath(override val remotePath: String) : Client.Path {
        override val authority: Authority = Authority("host", 22, "user")

        override fun resolve(other: String): Client.Path = TestPath("$remotePath/$other")

        override fun toString(): String = remotePath
    }

    private val path = TestPath("/home/user")

    private val cached = mutableMapOf<Client.Path, FileAttributes>()

    private fun open(directory: FakeDirectory): DirectoryEntryIterator =
        openDirectoryEntryIterator(path, { directory }) { entry, attributes ->
            cached[entry] = attributes
        }

    @Test
    fun entriesComeInTheOrderOfTheirBatchesWithTheirAttributes() {
        val directory = FakeDirectory(listOf("a", "b"), emptyList(), listOf("c"))
        val names = open(directory).asSequence().map { it.remotePath }.toList()
        assertEquals(listOf("/home/user/a", "/home/user/b", "/home/user/c"), names)
        assertEquals(3, cached.size)
        assertEquals(1L, cached[TestPath("/home/user/a")]!!.size)
        assertEquals(3L, cached[TestPath("/home/user/c")]!!.size)
    }

    @Test
    fun onlyTheFirstBatchIsReadBeforeTheFirstEntryIsShown() {
        val directory = FakeDirectory(listOf("a"), listOf("b"), listOf("c"))
        val iterator = open(directory)
        assertEquals(1, directory.readCount)
        assertEquals(TestPath("/home/user/a"), iterator.next())
        assertEquals(1, directory.readCount)
        assertEquals(TestPath("/home/user/b"), iterator.next())
        assertEquals(2, directory.readCount)
    }

    @Test
    fun anEmptyDirectoryHasNoEntriesAndIsNotReadAgain() {
        val directory = FakeDirectory()
        val iterator = open(directory)
        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasNext())
        assertEquals(1, directory.readCount)
        assertThrows(NoSuchElementException::class.java) { iterator.next() }
    }

    @Test
    fun aDirectoryThatCannotBeOpenedFailsRightAway() {
        val failure = SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
        val thrown = assertThrows(ClientException::class.java) {
            openDirectoryEntryIterator(path, { throw failure }) { _, _ -> }
        }
        assertSame(failure, thrown.cause)
        assertTrue(thrown.toFileSystemException(path.toString()) is NoSuchFileException)
    }

    @Test
    fun aFailedFirstBatchFailsRightAwayAndClosesTheDirectory() {
        val directory = FakeDirectory().apply {
            readFailure = SFTPException(Response.StatusCode.PERMISSION_DENIED, "Denied")
            closeFailure = IOException("Connection closed")
        }
        val thrown = assertThrows(ClientException::class.java) { open(directory) }
        assertSame(directory.readFailure, thrown.cause)
        assertSame(directory.closeFailure, thrown.suppressed.single())
        assertEquals(1, directory.closeCount)
    }

    @Test
    fun aFailedLaterBatchIsTheProvidersExceptionForTheDirectory() {
        val directory = FakeDirectory(listOf("a"))
        val iterator = open(directory)
        iterator.next()
        directory.readFailure = SFTPException(Response.StatusCode.PERMISSION_DENIED, "Denied")
        val thrown = assertThrows(DirectoryIteratorException::class.java) { iterator.hasNext() }
        val cause = thrown.cause
        assertTrue(cause.toString(), cause is AccessDeniedException)
        assertEquals("/home/user", (cause as FileSystemException).file)
    }

    @Test
    fun aClosedDirectoryIsNotReadAnyMoreAndClosesOnce() {
        val directory = FakeDirectory(listOf("a"), listOf("b"))
        val iterator = open(directory)
        iterator.next()
        iterator.close()
        iterator.close()
        assertFalse(iterator.hasNext())
        assertEquals(1, directory.readCount)
        assertEquals(1, directory.closeCount)
    }

    companion object {
        private fun attributes(name: String): FileAttributes =
            FileAttributes.Builder().withSize((name[0] - 'a' + 1).toLong()).build()
    }
}
