/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.content

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.checkAccess
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.isSameFile
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.observe
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.readAttributes
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The content provider against a real `content://` URI, served here by MediaStore, which is the
 * kind of URI the app gets from a share or an open intent.
 */
@RunWith(AndroidJUnit4::class)
class ContentFileSystemProviderTest {
    private val contentResolver =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private lateinit var uri: Uri
    private lateinit var path: Path

    @Before
    fun setUp() {
        val displayName = "ContentFileSystemProviderTest-${System.nanoTime()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        contentResolver.openOutputStream(uri)!!.use { it.write(CONTENT) }
        path = Paths.get(URI.create(uri.toString()))
    }

    @After
    fun tearDown() {
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            // Already deleted by the test.
        }
    }

    @Test
    fun theUriRoundTripsThroughThePath() {
        assertTrue(path is ContentPath)
        assertEquals(uri.toString(), path.toString())
        assertEquals(uri, (path as ContentPath).uri)
        assertEquals(path, Paths.get(path.toUri()))
    }

    @Test
    fun theFileNameComesFromTheProvidersDisplayName() {
        assertTrue(path.fileName.toString().startsWith("ContentFileSystemProviderTest-"))
        assertTrue(path.fileName.toString().endsWith(".txt"))
    }

    @Test
    fun rejectsAUriOfAnotherScheme() {
        val provider = path.fileSystem.provider()

        try {
            provider.getPath(URI.create("file:///"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("file"))
        }
        assertEquals(path.fileSystem, provider.getFileSystem(URI.create(uri.toString())))
        try {
            provider.newFileSystem(URI.create(uri.toString()), emptyMap<String, Any>())
            fail("expected FileSystemAlreadyExistsException")
        } catch (e: FileSystemAlreadyExistsException) {
            // Expected.
        }
    }

    @Test
    fun readsTheContentThroughAnInputStream() {
        path.newInputStream().use { assertArrayEquals(CONTENT, it.readBytes()) }
        assertArrayEquals(CONTENT, path.readAllBytes())
    }

    @Test
    fun writingIsRefusedOnAnInputStream() {
        try {
            path.newInputStream(StandardOpenOption.WRITE)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertEquals("WRITE", e.message)
        }
        try {
            // APPEND implies writing, so that is what the stream is refused for.
            path.newInputStream(StandardOpenOption.APPEND)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertEquals("WRITE", e.message)
        }
    }

    @Test
    fun anOutputStreamReplacesTheContent() {
        path.newOutputStream().use { it.write("replaced".toByteArray()) }

        assertArrayEquals("replaced".toByteArray(), path.readAllBytes())
    }

    @Test
    fun aByteChannelReadsAndSeeks() {
        path.newByteChannel(StandardOpenOption.READ).use { channel ->
            assertEquals(CONTENT.size.toLong(), channel.size())
            channel.position(2)
            val buffer = ByteBuffer.allocate(3)
            assertEquals(3, channel.read(buffer))
            assertArrayEquals(CONTENT.copyOfRange(2, 5), buffer.array())
        }
    }

    @Test
    fun aByteChannelWithFileAttributesIsRefused() {
        try {
            path.fileSystem.provider().newByteChannel(
                path,
                setOf(StandardOpenOption.READ),
                UnsupportedAttribute
            )
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test
    fun readsTheSizeAndTheMimeTypeAsAttributes() {
        val attributes = path.readAttributes(BasicFileAttributes::class.java)

        assertEquals(CONTENT.size.toLong(), attributes.size())
        assertTrue(attributes.isRegularFile)
        assertFalse(attributes.isDirectory)
        assertEquals(uri, attributes.fileKey())
    }

    @Test
    fun anUnsupportedAttributeTypeIsRefused() {
        try {
            path.readAttributes(UnsupportedAttributes::class.java)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test
    fun checkAccessAllowsReadingAndWritingButNotExecuting() {
        path.checkAccess()
        path.checkAccess(AccessMode.READ, AccessMode.WRITE)

        try {
            path.checkAccess(AccessMode.EXECUTE)
            fail("expected AccessDeniedException")
        } catch (e: AccessDeniedException) {
            assertEquals(path.toString(), e.file)
        }
        // Checking write access must not have truncated the file.
        assertArrayEquals(CONTENT, path.readAllBytes())
    }

    @Test
    fun checkAccessOfADeletedUriFails() {
        path.delete()

        // The row is gone but the media provider still answers getType(), so only actually
        // opening the content reports the file as unreadable.
        try {
            path.checkAccess(AccessMode.READ)
            fail("expected IOException")
        } catch (e: IOException) {
            // Expected; the resolver maps the missing row to a file system exception.
        }
    }

    @Test
    fun deletingTwiceFails() {
        path.delete()

        try {
            path.delete()
            fail("expected IOException")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun aPathIsOnlyTheSameFileAsItself() {
        assertTrue(path.isSameFile(Paths.get(path.toUri())))
        assertFalse(path.isHidden)
    }

    @Test
    fun theOperationsAContentUriCannotSupportAreRefused() {
        val provider = path.fileSystem.provider()

        assertThrowsUnsupported { provider.newDirectoryStream(path) { true } }
        assertThrowsUnsupported { provider.createDirectory(path) }
        assertThrowsUnsupported { provider.createLink(path, path) }
        assertThrowsUnsupported { provider.readSymbolicLink(path) }
        assertThrowsUnsupported { provider.copy(path, path) }
        assertThrowsUnsupported { provider.move(path, path) }
        assertThrowsUnsupported { provider.getFileStore(path) }
        assertThrowsUnsupported { provider.readAttributes(path, "basic:*") }
        assertThrowsUnsupported { provider.setAttribute(path, "basic:size", 0L) }
        assertThrowsUnsupported { provider.createSymbolicLink(path, path) }
    }

    @Test
    fun observingTheUriCallsBackWhenItChanges() {
        var changes = 0
        val observable = path.observe(0)
        try {
            observable.addObserver { synchronized(observable) { changes++ } }

            contentResolver.openOutputStream(uri, "wt")!!.use { it.write("changed".toByteArray()) }
            contentResolver.notifyChange(uri, null)

            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                synchronized(observable) { changes } == 0
            ) {
                Thread.sleep(50)
            }
            assertTrue(synchronized(observable) { changes } > 0)
        } finally {
            observable.close()
        }
    }

    @Test
    fun aMissingUriHasNoAttributes() {
        val missing = Paths.get(
            URI.create("${MediaStore.Downloads.EXTERNAL_CONTENT_URI}/2147483647")
        )

        try {
            missing.readAttributes(BasicFileAttributes::class.java)
            fail("expected FileSystemException")
        } catch (e: FileSystemException) {
            assertEquals(missing.toString(), e.file)
        }
    }

    private inline fun assertThrowsUnsupported(block: () -> Unit) {
        try {
            block()
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
    }

    private interface UnsupportedAttributes : BasicFileAttributes

    private object UnsupportedAttribute : java8.nio.file.attribute.FileAttribute<Any> {
        override fun name(): String = "unsupported"

        override fun value(): Any = Unit
    }

    companion object {
        private val CONTENT = "content of the test file".toByteArray()
    }
}
