/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.remote

import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttributeView
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileStore
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [RemoteFileSystemProvider] against a [RemoteFileSystemProviderInterface] that serves the
 * Linux provider, with every argument, result and exception marshalled through a real
 * [android.os.Parcel] (see [MarshallingBinder]). This is the path the root and Shizuku providers
 * take, which otherwise needs a rooted device.
 */
@RunWith(AndroidJUnit4::class)
class RemoteFileSystemProviderTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path
    private lateinit var provider: TestRemoteFileSystemProvider

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "RemoteFileSystemProviderTest").apply {
            deleteRecursively()
            mkdirs()
        }
        root = Paths.get(directory.path)
        provider = TestRemoteFileSystemProvider(
            RemoteFileSystemProviderInterface(LinuxFileSystemProvider).asBinder()
        )
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun writeFile(name: String, text: String): Path {
        File(directory, name).writeText(text)
        return root.resolve(name)
    }

    @Test
    fun readsAFileThroughARemoteInputStream() {
        val file = writeFile("file", "content of the file")

        provider.newInputStream(file).use {
            assertEquals("content of the file", it.readBytes().decodeToString())
        }
    }

    @Test
    fun aRemoteInputStreamSkipsAndReportsWhatIsAvailable() {
        val file = writeFile("file", "0123456789")

        provider.newInputStream(file).use { stream ->
            assertEquals(10, stream.available())
            assertEquals(4L, stream.skip(4))
            assertEquals('4'.code, stream.read())
            val buffer = ByteArray(3)
            assertEquals(3, stream.read(buffer, 0, 3))
            assertArrayEquals("567".toByteArray(), buffer)
        }
    }

    @Test
    fun aRemoteByteChannelReadsWritesSeeksAndTruncates() {
        val file = root.resolve("file")

        provider.newByteChannel(
            file,
            setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            )
        ).use { channel ->
            assertEquals(5, channel.write(ByteBuffer.wrap("01234".toByteArray())))
            assertEquals(5L, channel.position())
            channel.position(1)
            val buffer = ByteBuffer.allocate(3)
            assertEquals(3, channel.read(buffer))
            assertArrayEquals("123".toByteArray(), buffer.array())
            channel.truncate(2)
            assertEquals(2L, channel.size())
            assertTrue(channel.isOpen)
        }

        assertEquals("01", File(directory, "file").readText())
    }

    @Test
    fun listsADirectoryThroughAParcelledStream() {
        writeFile("one", "one")
        writeFile("two", "two")

        val names = provider.newDirectoryStream(root, filesAcceptAllFilter).use { stream ->
            stream.map { it.fileName.toString() }.sorted()
        }

        assertEquals(listOf("one", "two"), names)
    }

    @Test
    fun aFilterThatCannotBeParcelledIsRefused() {
        try {
            provider.newDirectoryStream(root) { it.fileName.toString() == "one" }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.endsWith("is not Parcelable"))
        }
    }

    @Test
    fun createsADirectoryAndALink() {
        val subDirectory = root.resolve("sub")
        provider.createDirectory(subDirectory)
        assertTrue(
            provider.readAttributes(subDirectory, BasicFileAttributes::class.java).isDirectory
        )

        val file = writeFile("file", "content")
        val link = root.resolve("link")
        provider.createSymbolicLink(link, Paths.get("file"))
        assertEquals("file", provider.readSymbolicLink(link).toString())
        // isSameFile() does not follow links, so the link is not the file it points at.
        assertFalse(provider.isSameFile(file, link))
        // Two different spellings of the same path are the same file, by inode.
        assertTrue(provider.isSameFile(file, root.resolve(".").resolve("file")))
    }

    /**
     * SELinux does not let the app hard link inside its own data directory, so what comes back
     * over the binder is the mapped failure.
     */
    @Test
    fun aRefusedHardLinkReportsTheMappedException() {
        val file = writeFile("file", "content")
        val hardLink = root.resolve("hard")

        try {
            provider.createLink(hardLink, file)
            fail("expected AccessDeniedException")
        } catch (e: AccessDeniedException) {
            assertEquals(hardLink.toString(), e.file)
            assertEquals(file.toString(), e.otherFile)
        }
    }

    @Test
    fun readingALinkThatIsNotALinkReportsTheMappedException() {
        val file = writeFile("file", "content")

        try {
            provider.readSymbolicLink(file)
            fail("expected NotLinkException")
        } catch (e: NotLinkException) {
            assertEquals(file.toString(), e.file)
        }
    }

    @Test
    fun deletesAFileAndReportsAMissingOne() {
        val file = writeFile("file", "content")

        provider.delete(file)

        assertFalse(File(directory, "file").exists())
        try {
            provider.delete(file)
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(file.toString(), e.file)
        }
    }

    @Test
    fun copiesAFileAndReportsProgressBack() {
        val source = writeFile("source", "x".repeat(200_000))
        val target = root.resolve("target")
        var reported = 0L

        provider.copy(source, target, ProgressCopyOption(0) { reported += it })

        assertEquals(200_000L, File(directory, "target").length())
        assertEquals(200_000L, reported)
    }

    @Test
    fun movesAFile() {
        val source = writeFile("source", "content")
        val target = root.resolve("target")

        provider.move(source, target, StandardCopyOption.REPLACE_EXISTING)

        assertFalse(File(directory, "source").exists())
        assertEquals("content", File(directory, "target").readText())
    }

    @Test
    fun aFailingCopyReportsItsExceptionBack() {
        val source = writeFile("source", "source")
        val target = writeFile("target", "target")

        try {
            provider.copy(source, target)
            fail("expected FileAlreadyExistsException")
        } catch (e: FileAlreadyExistsException) {
            assertEquals(target.toString(), e.otherFile)
        }
    }

    @Test
    fun reportsHiddenNamesAndAccess() {
        val file = writeFile("file", "content")

        assertFalse(provider.isHidden(file))
        assertTrue(provider.isHidden(root.resolve(".hidden")))
        provider.checkAccess(file)
        try {
            provider.checkAccess(root.resolve("missing"))
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(root.resolve("missing").toString(), e.file)
        }
    }

    @Test
    fun readsPosixAttributesThroughTheParcel() {
        val file = writeFile("file", "content")

        val attributes = provider.readAttributes(file, PosixFileAttributes::class.java)

        assertEquals(7L, attributes.size())
        assertTrue(attributes.isRegularFile)
        assertTrue(PosixFileModeBit.OWNER_READ in attributes.mode()!!)
        assertEquals(
            provider.readAttributes(file, BasicFileAttributes::class.java).fileKey(),
            attributes.fileKey()
        )
    }

    @Test
    fun readsAttributesOfALinkItselfWithNoFollowLinks() {
        writeFile("file", "content")
        val link = root.resolve("link")
        provider.createSymbolicLink(link, Paths.get("file"))

        assertTrue(
            provider.readAttributes(
                link,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            ).isSymbolicLink
        )
        assertTrue(provider.readAttributes(link, BasicFileAttributes::class.java).isRegularFile)
    }

    @Test
    fun readsTheFileStoreThroughTheParcel() {
        val fileStore = provider.getFileStore(root)

        assertTrue(fileStore is PosixFileStore)
        assertTrue(fileStore.totalSpace > 0)
        assertFalse(fileStore.isReadOnly)
    }

    @Test
    fun searchReportsMatchesThroughTheCallback() {
        writeFile("needle.txt", "x")
        writeFile("other.txt", "x")
        val found = mutableListOf<Path>()

        provider.search(root, "needle", 0) { synchronized(found) { found += it } }

        assertEquals(
            listOf(root.resolve("needle.txt")),
            synchronized(found) { found.toList() }
        )
    }

    @Test
    fun observingAPathDeliversChangesBackOverTheCallback() {
        var changes = 0
        val observable = provider.observe(root, 0)
        try {
            observable.addObserver { synchronized(observable) { changes++ } }

            File(directory, "created").writeText("content")

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
    fun theOperationsTheRemoteProviderDoesNotForwardAreRefused() {
        val file = writeFile("file", "content")

        try {
            provider.newFileChannel(file, setOf(StandardOpenOption.READ))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
        try {
            provider.readAttributes(file, "basic:*")
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
        try {
            provider.setAttribute(file, "basic:size", 0L)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test
    fun theRemoteInterfaceIsCreatedOnceAndKept() {
        val binder = RemoteFileSystemProviderInterface(LinuxFileSystemProvider).asBinder()
        var creations = 0
        val remoteInterface = RemoteInterface {
            creations++
            IRemoteFileSystemProvider.Stub.asInterface(MarshallingBinder(binder))
        }

        assertFalse(remoteInterface.has())
        remoteInterface.get()
        remoteInterface.get()

        assertTrue(remoteInterface.has())
        assertEquals(1, creations)
    }

    private class TestRemoteFileSystemProvider(binder: IBinder) :
        RemoteFileSystemProvider(
            RemoteInterface {
                IRemoteFileSystemProvider.Stub.asInterface(MarshallingBinder(binder))
            }
        ) {
        override fun getScheme(): String = "file"

        override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
            throw UnsupportedOperationException()

        override fun getFileSystem(uri: URI): FileSystem = throw UnsupportedOperationException()

        override fun getPath(uri: URI): Path = throw UnsupportedOperationException()

        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption
        ): V? = throw UnsupportedOperationException()
    }
}
