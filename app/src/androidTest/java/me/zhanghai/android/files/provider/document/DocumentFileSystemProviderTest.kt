/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document

import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.URI
import java8.nio.file.AccessMode
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.PosixFileAttributeView
import java8.nio.file.attribute.PosixFileAttributes
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.remote.filesAcceptAllFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The storage access framework provider. The tree it is pointed at here belongs to an authority
 * that is not installed, so every call that has to reach the document provider comes back as a
 * file system exception instead of leaking a platform one; the rest of the provider answers on
 * its own.
 */
@RunWith(AndroidJUnit4::class)
class DocumentFileSystemProviderTest {
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
    private lateinit var root: Path
    private lateinit var file: Path

    @Before
    fun setUp() {
        root = treeUri.createDocumentTreeRootPath()
        file = root.resolve("file")
    }

    @After
    fun tearDown() {
        // Whatever the test left in the provider's map, this is it.
        treeUri.createDocumentTreeRootPath().fileSystem.close()
    }

    @Test
    fun aFileSystemIsKeptPerTreeAndFoundBackByItsUri() {
        val uri = file.toUri()

        assertEquals(file, provider.getPath(uri))
        assertEquals(root.fileSystem, provider.getFileSystem(uri))
        try {
            provider.newFileSystem(uri, emptyMap<String, Any>())
            fail("expected FileSystemAlreadyExistsException")
        } catch (e: FileSystemAlreadyExistsException) {
            assertEquals(treeUri.toString(), e.message)
        }

        root.fileSystem.close()

        try {
            provider.getFileSystem(uri)
            fail("expected FileSystemNotFoundException")
        } catch (e: FileSystemNotFoundException) {
            assertEquals(treeUri.toString(), e.message)
        }
        assertNotNull(provider.newFileSystem(uri, emptyMap<String, Any>()))
    }

    @Test
    fun aUriOfAnotherSchemeOrWithoutAQueryIsRefused() {
        assertThrows<IllegalArgumentException> { provider.getPath(URI.create("file:///file")) }
        assertThrows<IllegalArgumentException> {
            provider.getPath(URI.create(file.toUri().toString().substringBefore('?')))
        }
        assertThrows<IllegalArgumentException> { provider.getPath(URI.create("document:opaque")) }
    }

    @Test
    fun aPathOfAnotherProviderIsRefused() {
        val linuxPath = Paths.get("/")

        assertThrows<ProviderMismatchException> { provider.delete(linuxPath) }
        assertThrows<ProviderMismatchException> { provider.isHidden(linuxPath) }
        assertThrows<ProviderMismatchException> { provider.createLink(file, linuxPath) }
        assertThrows<ProviderMismatchException> { linuxPath.documentUri }
        assertThrows<ProviderMismatchException> { linuxPath.documentTreeUri }
        assertThrows<ProviderMismatchException> { linuxPath.isLocalDocument }
    }

    @Test
    fun linksAreNotSupportedAtAll() {
        assertThrows<UnsupportedOperationException> { provider.createLink(file, root) }
        assertThrows<UnsupportedOperationException> { provider.readSymbolicLink(file) }
        assertThrows<UnsupportedOperationException> {
            provider.createSymbolicLink(file, ByteStringPath("target".toByteString()))
        }
        assertThrows<ProviderMismatchException> {
            provider.createSymbolicLink(file, Paths.get("/target"))
        }
    }

    @Test
    fun theOperationsADocumentTreeHasNoAnswerForAreRefused() {
        assertThrows<UnsupportedOperationException> { provider.getFileStore(file) }
        assertThrows<UnsupportedOperationException> { provider.readAttributes(file, "*") }
        assertThrows<UnsupportedOperationException> { provider.setAttribute(file, "name", 1) }
        assertThrows<UnsupportedOperationException> {
            provider.readAttributes(file, PosixFileAttributes::class.java)
        }
        assertThrows<UnsupportedOperationException> {
            provider.createDirectory(file, unsupportedAttribute)
        }
        assertThrows<UnsupportedOperationException> {
            provider.newByteChannel(file, setOf(StandardOpenOption.READ), unsupportedAttribute)
        }
        assertThrows<UnsupportedOperationException> {
            provider.newInputStream(file, StandardOpenOption.WRITE)
        }
        assertThrows<UnsupportedOperationException> {
            provider.newInputStream(file, StandardOpenOption.APPEND)
        }
        assertThrows<UnsupportedOperationException> { file.toRealPath() }
        assertThrows<UnsupportedOperationException> { file.toFile() }
    }

    @Test
    fun onlyDocumentAttributeViewsAreOffered() {
        assertNull(provider.getFileAttributeView(file, PosixFileAttributeView::class.java))
        assertNotNull(provider.getFileAttributeView(file, DocumentFileAttributeView::class.java))
        assertNotNull(provider.getFileAttributeView(file, FileAttributeView::class.java))
        assertTrue(provider.supportsFileAttributeView(FileAttributeView::class.java))
        assertFalse(provider.supportsFileAttributeView(PosixFileAttributeView::class.java))
    }

    @Test
    fun onlyTheSamePathIsTheSameFile() {
        assertTrue(provider.isSameFile(file, root.resolve("file")))
        assertFalse(provider.isSameFile(file, root.resolve("other")))
    }

    @Test
    fun namesStartingWithADotAreHidden() {
        assertTrue(provider.isHidden(root.resolve(".hidden")))
        assertFalse(provider.isHidden(file))
        assertFalse(provider.isHidden(root))
    }

    @Test
    fun theTreeUriIsCarriedByEveryPathOfIt() {
        assertEquals(treeUri, root.documentTreeUri)
        assertEquals(treeUri, file.documentTreeUri)
        // Only the storage and downloads providers are local; an unknown one is not.
        assertFalse(file.isLocalDocument)
    }

    @Test
    fun whatNeedsTheDocumentProviderFailsWhenItIsNotThere() {
        assertThrows<IOException> { provider.delete(file) }
        assertThrows<IOException> { provider.createDirectory(root.resolve("directory")) }
        assertThrows<IOException> { provider.newDirectoryStream(root, filesAcceptAllFilter) }
        assertThrows<IOException> { provider.newInputStream(file) }
        assertThrows<IOException> { provider.newOutputStream(file) }
        assertThrows<IOException> { provider.newByteChannel(file, setOf(StandardOpenOption.READ)) }
        assertThrows<IOException> { provider.checkAccess(file) }
        assertThrows<IOException> { provider.checkAccess(file, AccessMode.READ) }
        assertThrows<IOException> {
            provider.readAttributes(
                file,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
        }
        assertThrows<IOException> { provider.copy(file, root.resolve("copy")) }
        assertThrows<IOException> { provider.move(file, root.resolve("moved")) }
        assertThrows<IOException> { file.documentUri }
        assertThrows<IOException> { file.openDocumentParcelFileDescriptor("r") }
        assertThrows<IOException> {
            file.getDocumentThumbnail(THUMBNAIL_SIZE, THUMBNAIL_SIZE, CancellationSignal())
        }
    }

    private val provider = DocumentFileSystemProvider

    private val unsupportedAttribute = object : FileAttribute<String> {
        override fun name(): String = "name"

        override fun value(): String = "value"
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) {
                return
            }
            throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
        fail("expected ${T::class.java.simpleName}")
    }

    companion object {
        private const val THUMBNAIL_SIZE = 64
        private const val AUTHORITY = "me.zhanghai.android.files.test.absent.documents"
    }
}
