/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What the FTP server sees of a file: its name, whether it may be written and its contents. */
@RunWith(AndroidJUnit4::class)
class ProviderFtpFileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var root: Path
    private val writingUser = BaseUser().apply {
        name = "tester"
        authorities = listOf(WritePermission())
    }
    private val readingUser = BaseUser().apply { name = "reader" }

    private lateinit var rootStrategy: RootStrategy

    @Before
    fun setUp() {
        // The app's own files are not reachable as root on the emulator.
        instrumentation.runOnMainSync {
            rootStrategy = Settings.ROOT_STRATEGY.valueCompat
            Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
        }
        root = Paths.get(context.cacheDir.path).resolve("ftp-file-test")
        deleteRecursively(root)
        java.io.File(root.toString()).mkdirs()
    }

    @After
    fun tearDown() {
        deleteRecursively(root)
        instrumentation.runOnMainSync { Settings.ROOT_STRATEGY.putValue(rootStrategy) }
    }

    @Test
    fun theRootIsNamedAfterItsPathAndCannotBeRemoved() {
        val file = file("")

        assertEquals("/", file.name)
        assertEquals("/", file.absolutePath)
        assertTrue(file.isDirectory)
        assertTrue(file.doesExist())
        assertFalse("The root of the share must not be removable", file.isRemovable)
        assertEquals(3, file.linkCount)
    }

    @Test
    fun aFileIsOnlyWritableForAUserThatMayWrite() {
        write("file.txt", "Hello")

        assertTrue(file("file.txt").isWritable)
        assertFalse(file("file.txt", readingUser).isWritable)
        assertFalse(file("file.txt", readingUser).isRemovable)
    }

    @Test
    fun aDirectoryListsItsChildrenSorted() {
        write("b.txt", "b")
        write("a.txt", "a")
        java.io.File(root.toString(), "dir").mkdir()

        val names = file("").listFiles()!!.map { it.name }

        assertEquals(listOf("a.txt", "b.txt", "dir"), names)
    }

    @Test
    fun aFileIsReadAndWrittenFromAnOffset() {
        write("file.txt", "Hello")
        val file = file("file.txt")

        val rest = file.createInputStream(3).use { it.readBytes() }
        assertArrayEquals("lo".toByteArray(), rest)
        assertEquals(5L, file.size)

        file.createOutputStream(5).use { it.write("!".toByteArray()) }
        assertArrayEquals(
            "Hello!".toByteArray(),
            java.io.File(root.toString(), "file.txt").readBytes()
        )
    }

    @Test
    fun aFileIsMovedAndDeletedOnlyWhenTheUserMayWrite() {
        write("file.txt", "Hello")

        assertFalse(
            "A read-only user must not be able to delete",
            file("file.txt", readingUser).delete()
        )
        assertTrue(file("file.txt").move(file("moved.txt")))
        assertFalse(java.io.File(root.toString(), "file.txt").exists())
        assertTrue(file("moved.txt").delete())
        assertFalse(java.io.File(root.toString(), "moved.txt").exists())
    }

    @Test
    fun aDirectoryIsOnlyCreatedForAUserThatMayWrite() {
        assertFalse(file("new", readingUser).mkdir())
        assertTrue(file("new").mkdir())
        assertTrue(java.io.File(root.toString(), "new").isDirectory)
    }

    private fun file(relative: String, user: BaseUser = writingUser): ProviderFtpFile =
        ProviderFtpFile(root.resolve(relative), Paths.get(relative), user)

    private fun write(name: String, contents: String) {
        java.io.File(root.toString(), name).writeText(contents)
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) {
            return
        }
        if (java.io.File(path.toString()).isDirectory) {
            path.newDirectoryStream().use { stream ->
                for (name in stream) {
                    deleteRecursively(path.resolve(name))
                }
            }
        }
        path.delete()
    }
}
