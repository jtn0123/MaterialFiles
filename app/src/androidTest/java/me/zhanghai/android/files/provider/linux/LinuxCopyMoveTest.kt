/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.random.Random
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.createSymbolicLink
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.getMode
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLink
import me.zhanghai.android.files.provider.common.setMode
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import me.zhanghai.android.files.provider.linux.syscall.SyscallException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Copies and moves real files through the Linux provider, which does its work with syscalls
 * ([Syscall.sendfile], `rename`, `symlink`, xattrs), so it can only be exercised on a device.
 */
@RunWith(AndroidJUnit4::class)
class LinuxCopyMoveTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path

    /** A directory on external storage, which is a different mount from the app's data dir. */
    private lateinit var otherDirectory: File
    private lateinit var otherRoot: Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
        ).close()
        directory = File(context.filesDir, "LinuxCopyMoveTest").apply {
            deleteRecursively()
            mkdirs()
        }
        root = Paths.get(directory.path)
        otherDirectory = File(
            Environment.getExternalStorageDirectory(),
            "Download/LinuxCopyMoveTest"
        ).apply {
            deleteRecursively()
            mkdirs()
        }
        otherRoot = Paths.get(otherDirectory.path)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        otherDirectory.deleteRecursively()
    }

    private fun writeFile(name: String, bytes: ByteArray): Path {
        File(directory, name).writeBytes(bytes)
        return root.resolve(name)
    }

    private fun writeOtherFile(name: String, bytes: ByteArray): Path {
        File(otherDirectory, name).writeBytes(bytes)
        return otherRoot.resolve(name)
    }

    private fun Path.inode(): Any = readAttributes(BasicFileAttributes::class.java).fileKey()

    @Test
    fun copiesTheContentAndThePermissionsOfARegularFile() {
        val bytes = Random(1).nextBytes(40_000)
        val source = writeFile("source", bytes)
        source.setMode(
            setOf(
                PosixFileModeBit.OWNER_READ,
                PosixFileModeBit.OWNER_WRITE,
                PosixFileModeBit.GROUP_READ
            )
        )
        val target = root.resolve("target")

        source.copyTo(target)

        assertArrayEquals(bytes, target.readAllBytes())
        assertEquals(source.getMode(), target.getMode())
    }

    @Test
    fun copyAttributesAlsoCopiesTheModificationTime() {
        val source = writeFile("source", "content".toByteArray())
        // A whole second in the past, so a target that merely got "now" cannot match by luck.
        val past = System.currentTimeMillis() - 5_000
        File(directory, "source").setLastModified(past)
        val target = root.resolve("target")

        source.copyTo(target, StandardCopyOption.COPY_ATTRIBUTES)

        assertEquals(
            source.getLastModifiedTime().toMillis(),
            target.getLastModifiedTime().toMillis()
        )
    }

    @Test
    fun reportsEveryByteItCopiedAsProgress() {
        val bytes = Random(2).nextBytes(300_000)
        val source = writeFile("source", bytes)
        val target = root.resolve("target")
        var reported = 0L

        source.copyTo(target, ProgressCopyOption(0) { reported += it })

        assertEquals(bytes.size.toLong(), reported)
        assertArrayEquals(bytes, target.readAllBytes())
    }

    @Test
    fun copyingAFileOntoItselfKeepsItAndCountsItsSize() {
        val bytes = "content".toByteArray()
        val source = writeFile("source", bytes)
        var reported = 0L

        source.copyTo(root.resolve("source"), ProgressCopyOption(0) { reported += it })

        assertArrayEquals(bytes, source.readAllBytes())
        assertEquals(bytes.size.toLong(), reported)
    }

    @Test
    fun refusesAnExistingTargetWithoutReplaceExisting() {
        val source = writeFile("source", "source".toByteArray())
        val target = writeFile("target", "target".toByteArray())

        try {
            source.copyTo(target)
            fail("expected FileAlreadyExistsException")
        } catch (e: FileAlreadyExistsException) {
            assertEquals(target.toString(), e.otherFile)
        }
        assertArrayEquals("target".toByteArray(), target.readAllBytes())
    }

    @Test
    fun replaceExistingLeavesNeitherTheOldFileNorAPartFile() {
        val source = writeFile("source", "new content".toByteArray())
        val target = writeFile("target", "old content".toByteArray())

        source.copyTo(target, StandardCopyOption.REPLACE_EXISTING)

        assertArrayEquals("new content".toByteArray(), target.readAllBytes())
        assertEquals(
            listOf("source", "target"),
            directory.list()!!.sorted()
        )
    }

    @Test
    fun copiesADirectoryWithoutItsContent() {
        val source = root.resolve("source").createDirectory()
        File(directory, "source/child").writeBytes("child".toByteArray())
        val target = root.resolve("target")

        source.copyTo(target)

        assertTrue(target.readAttributes(BasicFileAttributes::class.java).isDirectory)
        assertEquals(emptyList<String>(), File(directory, "target").list()!!.toList())
    }

    @Test
    fun copiesASymbolicLinkAsALinkWithNoFollowLinks() {
        writeFile("file", "content".toByteArray())
        val source = root.resolve("link").createSymbolicLink(Paths.get("file"))
        val target = root.resolve("target")

        source.copyTo(target, LinkOption.NOFOLLOW_LINKS)

        assertEquals("file", target.readSymbolicLink().toString())
        assertTrue(
            target.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                .isSymbolicLink
        )
    }

    @Test
    fun copiesWhatASymbolicLinkPointsAtWithoutNoFollowLinks() {
        writeFile("file", "content".toByteArray())
        val source = root.resolve("link").createSymbolicLink(Paths.get("file"))
        val target = root.resolve("target")

        source.copyTo(target)

        assertArrayEquals("content".toByteArray(), target.readAllBytes())
        assertFalse(
            target.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                .isSymbolicLink
        )
    }

    @Test
    fun refusesToCopyASpecialFile() {
        val devNull = Paths.get("/dev/null")

        try {
            devNull.copyTo(root.resolve("target"))
            fail("expected FileSystemException")
        } catch (e: FileSystemException) {
            assertEquals("Cannot copy a special file", e.reason)
        }
        assertFalse(root.resolve("target").exists())
    }

    @Test
    fun copyingFromAMissingSourceFails() {
        try {
            root.resolve("missing").copyTo(root.resolve("target"))
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(root.resolve("missing").toString(), e.file)
        }
    }

    @Test
    fun movingWithinTheSameFilesystemRenamesAndKeepsTheInode() {
        val source = writeFile("source", "content".toByteArray())
        val inode = source.inode()
        val target = root.resolve("target")

        source.moveTo(target)

        assertFalse(source.exists())
        assertArrayEquals("content".toByteArray(), target.readAllBytes())
        assertEquals(inode, target.inode())
    }

    /**
     * External storage is a different mount from the app's data directory, so `rename()` fails
     * with `EXDEV` and the move falls back to copying and deleting. It goes that way round
     * because a move copies the attributes, and external storage keeps no extended attributes to
     * carry over while the data directory has an SELinux label that cannot be written there.
     */
    @Test
    fun movingAcrossFilesystemsCopiesThenDeletes() {
        val bytes = Random(3).nextBytes(50_000)
        val source = writeOtherFile("source", bytes)
        val inode = source.inode()
        val target = root.resolve("target")
        var reported = 0L

        source.moveTo(target, ProgressCopyOption(0) { reported += it })

        assertFalse(source.exists())
        assertArrayEquals(bytes, target.readAllBytes())
        assertNotEquals(inode, target.inode())
        assertEquals(bytes.size.toLong(), reported)
    }

    @Test
    fun anAtomicMoveAcrossFilesystemsIsRefused() {
        val source = writeFile("source", "content".toByteArray())

        try {
            source.moveTo(otherRoot.resolve("target"), StandardCopyOption.ATOMIC_MOVE)
            fail("expected AtomicMoveNotSupportedException")
        } catch (e: AtomicMoveNotSupportedException) {
            assertEquals(source.toString(), e.file)
        }
        assertTrue(source.exists())
        assertFalse(otherRoot.resolve("target").exists())
    }

    @Test
    fun movingOntoAnExistingFileNeedsReplaceExisting() {
        val source = writeFile("source", "source".toByteArray())
        val target = writeFile("target", "target".toByteArray())

        try {
            source.moveTo(target)
            fail("expected FileAlreadyExistsException")
        } catch (e: FileAlreadyExistsException) {
            assertEquals(target.toString(), e.otherFile)
        }

        source.moveTo(target, StandardCopyOption.REPLACE_EXISTING)

        assertFalse(source.exists())
        assertArrayEquals("source".toByteArray(), target.readAllBytes())
    }

    @Test
    fun movingADirectoryAcrossFilesystemsRecreatesItEmpty() {
        val source = otherRoot.resolve("source").createDirectory()
        val target = root.resolve("target")

        source.moveTo(target)

        assertFalse(source.exists())
        assertTrue(target.readAttributes(BasicFileAttributes::class.java).isDirectory)
    }

    @Test
    fun copiesUserExtendedAttributes() {
        val source = writeFile("source", "content".toByteArray())
        val name = "user.materialfiles.test".toByteString()
        try {
            Syscall.lsetxattr(source.toString().toByteString(), name, "value".toByteArray(), 0)
        } catch (e: SyscallException) {
            // Not every filesystem this test can write to supports user xattrs.
            return
        }
        val target = root.resolve("target")

        source.copyTo(target)

        assertArrayEquals(
            "value".toByteArray(),
            Syscall.lgetxattr(target.toString().toByteString(), name)
        )
    }

    @Test
    fun copyAttributesKeepsTheOwnerAndTheMode() {
        val source = writeFile("source", "content".toByteArray())
        source.setMode(setOf(PosixFileModeBit.OWNER_READ, PosixFileModeBit.OWNER_WRITE))
        val target = root.resolve("target")

        source.copyTo(target, StandardCopyOption.COPY_ATTRIBUTES)

        val sourceAttributes = source.readAttributes(PosixFileAttributes::class.java)
        val targetAttributes = target.readAttributes(PosixFileAttributes::class.java)
        assertEquals(sourceAttributes.mode(), targetAttributes.mode())
        assertEquals(sourceAttributes.owner(), targetAttributes.owner())
        assertEquals(sourceAttributes.group(), targetAttributes.group())
    }
}
