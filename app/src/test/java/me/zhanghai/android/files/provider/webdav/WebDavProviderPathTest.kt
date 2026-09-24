/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import java.net.URI
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.LinkOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.DosFileAttributeView
import java8.nio.file.attribute.DosFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.webdav.client.Authentication
import me.zhanghai.android.files.provider.webdav.client.Authenticator
import me.zhanghai.android.files.provider.webdav.client.Authority
import me.zhanghai.android.files.provider.webdav.client.Client
import me.zhanghai.android.files.provider.webdav.client.NoneAuthentication
import me.zhanghai.android.files.provider.webdav.client.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [WebDavFileSystemProvider] answers about paths, attribute views and file systems, and how
 * it searches, against [FakeWebDavServer].
 */
class WebDavProviderPathTest {
    private lateinit var server: FakeWebDavServer

    private lateinit var authority: Authority

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.start()
        authority = Authority(Protocol.DAV, "127.0.0.1", server.port, "test")
        WebDavFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getAuthentication(authority: Authority): Authentication =
                    NoneAuthentication
            }
        )
    }

    @After
    fun tearDown() {
        WebDavFileSystemProvider.getOrNewFileSystem(authority).close()
        server.stop()
        assertEquals(emptyList<Throwable>(), server.failures)
    }

    private fun path(path: String): WebDavPath =
        WebDavFileSystemProvider.getOrNewFileSystem(authority).getPath(path)

    @Test
    fun sameFileMeansEqualPaths() {
        assertTrue(WebDavFileSystemProvider.isSameFile(path("/a/b"), path("/a/./b").normalize()))
        assertFalse(WebDavFileSystemProvider.isSameFile(path("/a/b"), path("/a/c")))
        assertFalse(WebDavFileSystemProvider.isSameFile(path("/a"), TestPath("/a")))
    }

    @Test
    fun aNameStartingWithADotIsHiddenAndTheRootIsNot() {
        assertTrue(WebDavFileSystemProvider.isHidden(path("/dir/.hidden")))
        assertFalse(WebDavFileSystemProvider.isHidden(path("/dir/visible")))
        assertFalse(WebDavFileSystemProvider.isHidden(path("/")))
    }

    @Test
    fun anUnsupportedAttributeViewIsNullAndTheBasicOneIsTheProviders() {
        assertNull(
            WebDavFileSystemProvider.getFileAttributeView(
                path("/file.txt"),
                DosFileAttributeView::class.java
            )
        )
        val view = WebDavFileSystemProvider.getFileAttributeView(
            path("/file.txt"),
            BasicFileAttributeView::class.java
        )
        assertEquals(WebDavFileSystemProvider.scheme, view!!.name())
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.readAttributes(
                path("/file.txt"),
                DosFileAttributes::class.java
            )
        }
    }

    @Test
    fun settingNoTimeAsksNothingOfTheServer() {
        server.addFile("/file.txt", "hello")
        val view = WebDavFileSystemProvider.getFileAttributeView(
            path("/file.txt"),
            BasicFileAttributeView::class.java
        )!!
        val before = server.requests.size
        view.setTimes(null, null, null)
        assertEquals(before, server.requests.size)
        val time = FileTime.fromMillis(0)
        assertThrows(UnsupportedOperationException::class.java) {
            view.setTimes(null, time, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            view.setTimes(null, null, time)
        }
    }

    @Test
    fun theTimeOfALinkItselfCannotBeSet() {
        val view = WebDavFileSystemProvider.getFileAttributeView(
            path("/file.txt"),
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS
        )!!
        assertThrows(UnsupportedOperationException::class.java) {
            view.setTimes(FileTime.fromMillis(0), null, null)
        }
    }

    /**
     * Known limitation: most servers do not let a client set `getlastmodified`, so the client
     * does not even try, and setting the modified time quietly does nothing.
     */
    @Test
    fun settingTheModifiedTimeIsQuietlySkipped() {
        server.addFile("/file.txt", "hello")
        val view = WebDavFileSystemProvider.getFileAttributeView(
            path("/file.txt"),
            BasicFileAttributeView::class.java
        )!!
        view.setTimes(FileTime.fromMillis(0), null, null)
        assertFalse(server.requests.toString(), server.requests.any { it.startsWith("PROPPATCH") })
    }

    @Test
    fun whatWebDavCannotDoIsRefused() {
        val file = path("/file.txt")
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.getFileStore(file)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.readAttributes(file, "basic:size")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.setAttribute(file, "basic:size", 0L)
        }
    }

    @Test
    fun aSearchFindsMatchingNamesThroughoutTheTree() {
        server.addCollection("/dir")
        server.addFile("/dir/match.txt", "one")
        server.addFile("/other.txt", "two")
        server.addFile("/match-too.txt", "three")
        val found = mutableListOf<String>()
        WebDavFileSystemProvider.search(path("/"), "match", 0) { paths ->
            paths.mapTo(found) { it.toString() }
        }
        assertEquals(listOf("/dir/match.txt", "/match-too.txt"), found.sorted())
    }

    @Test
    fun aFileSystemIsCreatedFromAUriOnlyOnce() {
        val uri = URI("dav://someone@127.0.0.1:${server.port + 1}/")
        assertThrows(FileSystemNotFoundException::class.java) {
            WebDavFileSystemProvider.getFileSystem(uri)
        }
        val fileSystem = WebDavFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
        try {
            assertSame(fileSystem, WebDavFileSystemProvider.getFileSystem(uri))
            assertThrows(FileSystemAlreadyExistsException::class.java) {
                WebDavFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
            }
            val expected = Authority(Protocol.DAV, "127.0.0.1", server.port + 1, "someone")
            assertEquals(expected, (fileSystem as WebDavFileSystem).authority)
        } finally {
            fileSystem.close()
        }
        assertFalse(fileSystem.isOpen)
        // Closing twice is harmless, and a closed file system is forgotten.
        fileSystem.close()
        assertThrows(FileSystemNotFoundException::class.java) {
            WebDavFileSystemProvider.getFileSystem(uri)
        }
    }

    @Test
    fun aUriWithoutAPortUsesTheDefaultOfItsScheme() {
        val path = WebDavFileSystemProvider.getPath(URI("davs://example.com/a/b")) as WebDavPath
        try {
            assertEquals(Authority(Protocol.DAVS, "example.com", 443, ""), path.authority)
            assertEquals("/a/b", path.toString())
            assertEquals("https://example.com/a/b", path.url.toString())
        } finally {
            path.fileSystem.close()
        }
    }

    @Test
    fun aUriOfAnotherSchemeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavFileSystemProvider.getPath(URI("ftp://example.com/a"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavFileSystemProvider.getFileSystem(URI("http://example.com/"))
        }
    }
}
