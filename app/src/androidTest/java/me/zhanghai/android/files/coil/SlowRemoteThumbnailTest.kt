/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.SuccessResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.filelist.isRemotePath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What loading thumbnails costs a slow server: how much of each file is read, and how many files
 * are read at once.
 */
@RunWith(AndroidJUnit4::class)
class SlowRemoteThumbnailTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private lateinit var directory: File
    private lateinit var fileSystem: SlowRemoteFileSystem
    private lateinit var loading: ThumbnailLoading

    private val reads: RemoteReads
        get() = fileSystem.reads

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "slow-remote-${UUID.randomUUID()}").apply { mkdirs() }
        fileSystem = SlowRemoteFileSystem(directory)
        loading = ThumbnailLoading(directory)
    }

    @After
    fun tearDown() {
        reads.unblockAll()
        directory.deleteRecursively()
    }

    @Test
    fun theFakeServerIsRemote() {
        TestJpeg.write(File(directory, "any.jpg"), 64, 64)

        assertTrue(fileSystem.path("any.jpg").isRemotePath)
    }

    @Test
    fun aListIconOfACameraPhotoReadsOnlyTheStartOfTheFile() {
        val file = File(directory, "camera.jpg")
        TestJpeg.write(file, 2400, 1800, 160, 120, isNoisy = true)
        val fileSize = file.length()
        assertTrue("The photo is only $fileSize bytes", fileSize > 3 * 1024 * 1024)
        val path = fileSystem.path("camera.jpg")

        val result = loading.loadSuccessfully(path, 96, 96)

        // The thumbnail the camera embedded is enough for a list icon.
        assertEquals(DataSource.NETWORK, result.dataSource)
        assertEquals(160, result.drawable.intrinsicWidth)
        assertEquals(120, result.drawable.intrinsicHeight)
        // One small first read, and nothing read ahead.
        val bytesRead = reads.bytesRead("camera.jpg")
        assertEquals("Reads of a $fileSize byte file", 1, reads.readCount("camera.jpg"))
        assertTrue("Read $bytesRead of $fileSize bytes", bytesRead <= FIRST_READ_SIZE)
        assertEquals(0, reads.openChannelCount)
    }

    @Test
    fun aListIconOfAPhotoWithoutAnEmbeddedThumbnailReadsTheWholeFile() {
        // What the test above saves: without an embedded thumbnail, the whole photo is read.
        val file = File(directory, "plain.jpg")
        TestJpeg.write(file, 2400, 1800, isNoisy = true)
        val path = fileSystem.path("plain.jpg")

        loading.loadSuccessfully(path, 96, 96)

        assertTrue(
            "Read ${reads.bytesRead("plain.jpg")} of ${file.length()} bytes",
            reads.bytesRead("plain.jpg") >= file.length()
        )
        assertEquals(0, reads.openChannelCount)
    }

    @Test
    fun scrollingABigFolderReadsAtMostFourFilesAtOnce() {
        val names = (1..20).map { "photo-$it.jpg" }
        for (name in names) {
            TestJpeg.write(File(directory, name), 800, 600)
        }
        val paths = names.map { fileSystem.path(it) }
        reads.latencyMillis = 150

        val results = runBlocking {
            withTimeout(60_000) {
                paths.map { async { loading.execute(it, 96, 96) } }.awaitAll()
            }
        }

        for ((name, result) in names.zip(results)) {
            if (result is ErrorResult) {
                throw AssertionError("Failed to load $name", result.throwable)
            }
            assertTrue(name, result is SuccessResult)
            assertTrue(name, reads.readCount(name) > 0)
        }
        assertTrue("Peak reads in flight ${reads.peakReadsInFlight}", reads.peakReadsInFlight <= 4)
        assertTrue("Peak open files ${reads.peakOpenChannels}", reads.peakOpenChannels <= 4)
        // And the four are used, rather than the files being read one by one.
        assertEquals(4, reads.peakOpenChannels)
        assertEquals(0, reads.openChannelCount)
    }

    @Test
    fun thumbnailsOnDiskAreNotReadAgain() {
        TestJpeg.write(File(directory, "again.jpg"), 800, 600)
        val path = fileSystem.path("again.jpg")

        loading.loadSuccessfully(path, 96, 96)
        val readCount = reads.readCount("again.jpg")
        val openedChannels = reads.openedChannels("again.jpg")
        val second = loading.loadSuccessfully(path, 96, 96)

        assertEquals(DataSource.DISK, second.dataSource)
        assertEquals(readCount, reads.readCount("again.jpg"))
        assertEquals(openedChannels, reads.openedChannels("again.jpg"))
    }

    companion object {
        /** The first read of [me.zhanghai.android.files.provider.common.AbstractFileByteChannel]. */
        private const val FIRST_READ_SIZE = 128 * 1024L
    }
}
