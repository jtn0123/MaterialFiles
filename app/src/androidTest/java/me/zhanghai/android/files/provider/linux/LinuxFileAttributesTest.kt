/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java8.nio.file.AccessDeniedException
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.getFileAttributeView
import me.zhanghai.android.files.provider.common.getFileStore
import me.zhanghai.android.files.provider.common.readAttributes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The POSIX attributes of a real file, the users and groups behind their numeric ids, and the
 * mount the file lives on. All of it comes from syscalls, so it needs a device.
 */
@RunWith(AndroidJUnit4::class)
class LinuxFileAttributesTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path
    private lateinit var file: Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "LinuxFileAttributesTest").apply {
            deleteRecursively()
            mkdirs()
        }
        root = Paths.get(directory.path)
        File(directory, "file").writeText("content")
        file = root.resolve("file")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun view(): PosixFileAttributeView =
        file.getFileAttributeView(PosixFileAttributeView::class.java)!!

    @Test
    fun theModificationAndAccessTimesCanBeSetAndReadBack() {
        val modifiedTime = FileTime.fromMillis(1_000_000_000_000)
        val accessTime = FileTime.fromMillis(1_100_000_000_000)

        view().setTimes(modifiedTime, accessTime, null)

        val attributes = file.readAttributes(PosixFileAttributes::class.java)
        assertEquals(modifiedTime.toMillis(), attributes.lastModifiedTime().toMillis())
        assertEquals(accessTime.toMillis(), attributes.lastAccessTime().toMillis())
    }

    @Test
    fun onlyOneOfTheTimesCanBeSetLeavingTheOtherAlone() {
        val before = file.readAttributes(PosixFileAttributes::class.java).lastAccessTime()
        val modifiedTime = FileTime.fromMillis(900_000_000_000)

        view().setTimes(modifiedTime, null, null)

        val attributes = file.readAttributes(PosixFileAttributes::class.java)
        assertEquals(modifiedTime.toMillis(), attributes.lastModifiedTime().toMillis())
        assertEquals(before.toMillis(), attributes.lastAccessTime().toMillis())
    }

    @Test
    fun askingForNoTimeAtAllDoesNothingAndACreateTimeIsRefused() {
        view().setTimes(null, null, null)

        try {
            view().setTimes(null, null, FileTime.fromMillis(0))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertEquals("createTime", e.message)
        }
    }

    @Test
    fun theModeCanBeSetAndReadBack() {
        val mode = setOf(
            PosixFileModeBit.OWNER_READ,
            PosixFileModeBit.OWNER_WRITE,
            PosixFileModeBit.GROUP_READ,
            PosixFileModeBit.OTHERS_EXECUTE
        )

        view().setMode(mode)

        assertEquals(mode, file.readAttributes(PosixFileAttributes::class.java).mode())
    }

    @Test
    fun theAppCannotGiveItsFilesAwayToAnotherUserOrGroup() {
        try {
            view().setOwner(PosixUser(0, null))
            fail("expected AccessDeniedException")
        } catch (e: AccessDeniedException) {
            assertEquals(file.toString(), e.file)
        }
        try {
            view().setGroup(PosixGroup(0, null))
            fail("expected AccessDeniedException")
        } catch (e: AccessDeniedException) {
            assertEquals(file.toString(), e.file)
        }
    }

    @Test
    fun theOwnerGroupAndSeLinuxContextOfAFileAreRead() {
        val attributes = file.readAttributes(PosixFileAttributes::class.java)

        val processUid = Process.myUid()
        assertEquals(processUid, attributes.owner()!!.id)
        assertEquals(processUid, attributes.group()!!.id)
        assertNotNull(attributes.seLinuxContext())
        assertTrue(attributes.isRegularFile)
        assertEquals("content".length.toLong(), attributes.size())
        assertNotNull(attributes.fileKey())
    }

    @Test
    fun usersAndGroupsAreLookedUpByNameAndReportedMissingOtherwise() {
        val lookupService = root.fileSystem.userPrincipalLookupService

        val user = lookupService.lookupPrincipalByName("root") as PosixUser
        assertEquals(0, user.id)
        assertEquals("root", user.name.toString())
        val group = lookupService.lookupPrincipalByGroupName("root") as PosixGroup
        assertEquals(0, group.id)
        assertEquals("root", group.name.toString())

        // Bionic has no account database to miss an entry in: it reports a plain errno for an
        // unknown name, which surfaces as a file system exception rather than a missing principal.
        try {
            lookupService.lookupPrincipalByName(MISSING_NAME)
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertTrue(e.message!!.contains("getpwnam"))
        }
        try {
            lookupService.lookupPrincipalByGroupName(MISSING_NAME)
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertTrue(e.message!!.contains("getgrnam"))
        }
    }

    @Test
    fun usersAndGroupsAreAlsoLookedUpById() {
        assertEquals("root", LinuxUserPrincipalLookupService.lookupPrincipalById(0).name.toString())
        assertEquals(
            "root",
            LinuxUserPrincipalLookupService.lookupPrincipalByGroupId(0).name.toString()
        )
        // An id no account has still gets a principal, with the name bionic makes up for it.
        val missingUser = LinuxUserPrincipalLookupService.lookupPrincipalById(MISSING_ID)
        assertEquals(MISSING_ID, missingUser.id)
        assertNotNull(missingUser.name)
        val missingGroup = LinuxUserPrincipalLookupService.lookupPrincipalByGroupId(MISSING_ID)
        assertEquals(MISSING_ID, missingGroup.id)
        assertNotNull(missingGroup.name)
    }

    @Test
    fun theFileStoreDescribesTheMountTheFileLivesOn() {
        val fileStore = file.getFileStore()

        assertTrue(fileStore.name().isNotEmpty())
        assertTrue(fileStore.type().isNotEmpty())
        assertFalse(fileStore.isReadOnly)
        assertTrue(fileStore.totalSpace > 0)
        assertTrue(fileStore.usableSpace > 0)
        assertTrue(fileStore.unallocatedSpace >= fileStore.usableSpace)
        assertTrue(fileStore.supportsFileAttributeView(PosixFileAttributeView::class.java))
        assertTrue(fileStore.supportsFileAttributeView("posix"))
        assertFalse(fileStore.supportsFileAttributeView("acl"))
    }

    companion object {
        private const val MISSING_NAME = "no-such-account-materialfiles"
        private const val MISSING_ID = 999_999
    }
}
