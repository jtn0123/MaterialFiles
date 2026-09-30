/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import java.io.IOException
import java8.nio.file.Path
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.util.recordWarnings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ViewerFileDeletionTest {
    private val defaultDeleter = ViewerFileDeletion.deleter
    private val path = TestPath("/share/Photo.jpg")

    @After
    fun tearDown() {
        ViewerFileDeletion.deleter = defaultDeleter
    }

    @Test
    fun deletesOffTheCallingThread() {
        var deletedPath: Path? = null
        var deletingThread: Thread? = null
        ViewerFileDeletion.deleter = {
            deletedPath = it
            deletingThread = Thread.currentThread()
        }
        val callingThread = Thread.currentThread()

        val exception = runBlocking { ViewerFileDeletion.delete(path, "Test") }

        assertNull(exception)
        assertSame(path, deletedPath)
        assertNotSame(callingThread, deletingThread)
    }

    @Test
    fun aFailureIsReturnedAndLogged() {
        val failure = IOException("The server went away")
        ViewerFileDeletion.deleter = { throw failure }
        var exception: IOException? = null

        val warnings = recordWarnings {
            exception = runBlocking { ViewerFileDeletion.delete(path, "Test") }
        }

        assertSame(failure, exception)
        assertEquals(1, warnings.size)
        assertEquals("Test", warnings[0].tag)
        assertEquals("Delete $path", warnings[0].operation)
        assertSame(failure, warnings[0].throwable)
    }
}
