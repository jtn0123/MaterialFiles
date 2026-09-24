/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttributeView
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.checkAccess
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.createSymbolicLink
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.getFileStore
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.isSameFile
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLink
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.provider.common.setMode
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Linux provider's operations, each of which is a syscall against a real file.
 */
@RunWith(AndroidJUnit4::class)
class LinuxFileSystemProviderTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "LinuxFileSystemProviderTest").apply {
            deleteRecursively()
            mkdirs()
        }
        root = Paths.get(directory.path)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private val provider get() = root.fileSystem.provider()

    @Test
    fun resolvesAFileUriBackToTheSamePath() {
        val path = root.resolve("a b+c")

        assertEquals(path, Paths.get(path.toUri()))
        assertEquals(path, provider.getPath(URI.create(path.toUri().toString())))
    }

    @Test
    fun rejectsAUriOfAnotherScheme() {
        try {
            provider.getPath(URI.create("content://authority/1"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("content"))
        }
    }

    @Test
    fun theFileSystemIsASingletonThatCannotBeCreatedAgain() {
        val uri = URI.create("file:///")

        assertEquals(root.fileSystem, provider.getFileSystem(uri))
        try {
            provider.newFileSystem(uri, emptyMap<String, Any>())
            fail("expected FileSystemAlreadyExistsException")
        } catch (e: FileSystemAlreadyExistsException) {
            // Expected.
        }
    }

    @Test
    fun createsAndListsADirectory() {
        val subDirectory = root.resolve("sub").createDirectory()
        File(directory, "sub/one").writeText("one")
        File(directory, "sub/two").writeText("two")

        val names = subDirectory.newDirectoryStream().use { stream ->
            stream.map { it.fileName.toString() }.sorted()
        }

        assertEquals(listOf("one", "two"), names)
    }

    @Test
    fun aDirectoryStreamSkipsDotAndDotDot() {
        val names = root.newDirectoryStream().use { it.toList() }

        assertEquals(emptyList<Path>(), names)
    }

    @Test
    fun createsADirectoryWithTheRequestedMode() {
        val subDirectory = root.resolve("sub").createDirectory()
        subDirectory.setMode(
            setOf(
                PosixFileModeBit.OWNER_READ,
                PosixFileModeBit.OWNER_WRITE,
                PosixFileModeBit.OWNER_EXECUTE
            )
        )

        assertEquals(
            setOf(
                PosixFileModeBit.OWNER_READ,
                PosixFileModeBit.OWNER_WRITE,
                PosixFileModeBit.OWNER_EXECUTE
            ),
            subDirectory.readAttributes(PosixFileAttributes::class.java).mode()
        )
    }

    @Test
    fun readsBackTheTargetOfASymbolicLink() {
        val link = root.resolve("link").createSymbolicLink(Paths.get("target"))

        assertEquals("target", link.readSymbolicLink().toString())
        assertTrue(
            link.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                .isSymbolicLink
        )
    }

    @Test
    fun readingALinkThatIsNotALinkFails() {
        File(directory, "file").writeText("content")

        try {
            root.resolve("file").readSymbolicLink()
            fail("expected NotLinkException")
        } catch (e: NotLinkException) {
            assertEquals(root.resolve("file").toString(), e.file)
        }
    }

    /**
     * SELinux does not let the app hard link inside its own data directory, and external storage
     * has no hard links at all, so only the failure can be checked here: the link is asked for in
     * a directory that does not exist.
     */
    @Test
    fun creatingAHardLinkInAMissingDirectoryFails() {
        File(directory, "file").writeText("content")
        val file = root.resolve("file")
        val link = root.resolve("missing").resolve("link")

        try {
            provider.createLink(link, file)
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(link.toString(), e.file)
        }
        assertFalse(link.exists())
    }

    @Test
    fun differentFilesAreNotTheSameFile() {
        File(directory, "one").writeText("one")
        File(directory, "two").writeText("two")

        assertFalse(root.resolve("one").isSameFile(root.resolve("two")))
        assertTrue(root.resolve("one").isSameFile(root.resolve("one")))
    }

    /**
     * Both paths have to belong to a provider that can be run as root, so a path of another
     * provider is refused before it ever gets compared.
     */
    @Test
    fun aPathOfAnotherProviderIsRefusedBeforeBeingCompared() {
        File(directory, "one").writeText("one")
        val contentPath = Paths.get(URI.create("content://authority/1"))

        try {
            provider.isSameFile(root.resolve("one"), contentPath)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("$contentPath is not a RootablePath", e.message)
        }
    }

    @Test
    fun namesStartingWithADotAreHidden() {
        assertTrue(root.resolve(".hidden").isHidden)
        assertFalse(root.resolve("shown").isHidden)
        assertFalse(Paths.get("/").isHidden)
    }

    @Test
    fun deletesAFileAndThenFailsToDeleteItAgain() {
        File(directory, "file").writeText("content")
        val file = root.resolve("file")

        file.delete()

        assertFalse(file.exists())
        try {
            file.delete()
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(file.toString(), e.file)
        }
    }

    @Test
    fun theFileStoreOfTheDataDirectoryIsWritableAndHasSpace() {
        val fileStore = root.getFileStore()

        assertFalse(fileStore.isReadOnly)
        assertTrue(fileStore.totalSpace > 0)
        assertTrue(fileStore.usableSpace > 0)
        assertNotNull(fileStore.name())
    }

    @Test
    fun checkAccessTellsReadableFromExecutable() {
        File(directory, "file").writeText("content")
        val file = root.resolve("file")
        file.setMode(setOf(PosixFileModeBit.OWNER_READ, PosixFileModeBit.OWNER_WRITE))

        file.checkAccess()
        file.checkAccess(AccessMode.READ, AccessMode.WRITE)
        try {
            file.checkAccess(AccessMode.EXECUTE)
            fail("expected AccessDeniedException")
        } catch (e: AccessDeniedException) {
            assertEquals(file.toString(), e.file)
        }
    }

    @Test
    fun checkAccessOfAMissingFileFails() {
        try {
            root.resolve("missing").checkAccess()
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(root.resolve("missing").toString(), e.file)
        }
    }

    @Test
    fun aByteChannelReadsWritesAndSeeks() {
        val file = root.resolve("file")

        file.newByteChannel(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { channel ->
            assertEquals(5, channel.write(ByteBuffer.wrap("01234".toByteArray())))
            channel.position(1)
            val buffer = ByteBuffer.allocate(3)
            assertEquals(3, channel.read(buffer))
            assertArrayEquals("123".toByteArray(), buffer.array())
            channel.truncate(2)
            assertEquals(2, channel.size())
        }

        assertArrayEquals("01".toByteArray(), file.readAllBytes())
    }

    @Test
    fun deleteOnCloseRemovesTheFileImmediately() {
        val file = root.resolve("file")

        file.newByteChannel(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.DELETE_ON_CLOSE
        ).use { channel ->
            channel.write(ByteBuffer.wrap("content".toByteArray()))
            assertEquals(7, channel.size())
        }

        assertFalse(file.exists())
    }

    @Test
    fun anUnsupportedAttributeTypeIsRefused() {
        File(directory, "file").writeText("content")
        val file = root.resolve("file")

        try {
            file.readAttributes(UnsupportedAttributes::class.java)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
        try {
            provider.readAttributes(file, "posix:*")
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
        try {
            provider.setAttribute(file, "posix:permissions", emptySet<Any>())
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test
    fun anUnsupportedAttributeViewIsNull() {
        val file = root.resolve("file")

        assertNotNull(provider.getFileAttributeView(file, BasicFileAttributeView::class.java))
        assertNull(provider.getFileAttributeView(file, UnsupportedView::class.java))
    }

    @Test
    fun searchFindsMatchingNamesUnderTheDirectory() {
        root.resolve("sub").createDirectory()
        File(directory, "sub/needle.txt").writeText("x")
        File(directory, "sub/other.txt").writeText("x")
        val found = mutableListOf<Path>()

        root.search("needle", 0) { synchronized(found) { found += it } }

        assertEquals(
            listOf(root.resolve("sub").resolve("needle.txt")),
            synchronized(found) {
                found.toList()
            }
        )
    }

    private interface UnsupportedAttributes : BasicFileAttributes

    private interface UnsupportedView : FileAttributeView
}
