/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.NoRootAccessRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rows that scroll away while their thumbnail is being read from a server that has stopped
 * answering: their reads are given up, and the four reads the app allows at a time are free again
 * for the rows that are still shown.
 */
@RunWith(AndroidJUnit4::class)
class RemoteThumbnailCancellationTest {
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
        directory = File(context.filesDir, "cancellation-${UUID.randomUUID()}").apply { mkdirs() }
        fileSystem = SlowRemoteFileSystem(directory)
        loading = ThumbnailLoading(directory)
    }

    @After
    fun tearDown() {
        reads.unblockAll()
        directory.deleteRecursively()
    }

    /**
     * The regression test for the deadlock between a read that closes its channel and the abort
     * that closes the same channel: had it come back, the channels would never finish closing and
     * the four permits would stay taken, so the last thumbnail would never load.
     */
    @Test(timeout = 60_000)
    fun videosWhoseReadsAreStuckAreGivenUpWhenScrolledAway() {
        val stuck = (1..MAX_PARALLEL_READS).map { writeVideo("stuck-$it.mp4") }
        stuck.forEach { reads.block(it) }
        writeVideo("shown.mp4")

        val disposables = stuck.map { loading.enqueue(fileSystem.path(it), 96, 96) }
        waitUntil("${stuck.size} reads are stuck") {
            reads.blockedReadCount == stuck.size
        }
        disposables.forEach { it.dispose() }

        // The reads are abandoned by closing their channels, as the server never answers.
        waitUntil("the stuck channels are closed") {
            stuck.all { reads.closedChannels(it) == reads.openedChannels(it) }
        }
        // Well before a read would time out on its own (15 s), the permits are free again.
        val result = loadWithin(10_000, "shown.mp4")
        // A frame, decoded at the 256 pixel step that thumbnails of remote files are kept at.
        assertEquals(256, result.drawable.intrinsicWidth)
        assertEquals(0, reads.blockedReadCount)
    }

    /** The same for photos, whose reads are interrupted rather than closed from elsewhere. */
    @Test(timeout = 60_000)
    fun photosWhoseReadsAreStuckAreGivenUpWhenScrolledAway() {
        val stuck = (1..MAX_PARALLEL_READS).map { writePhoto("stuck-$it.jpg") }
        stuck.forEach { reads.block(it) }
        writePhoto("shown.jpg")

        val disposables = stuck.map { loading.enqueue(fileSystem.path(it), 96, 96) }
        waitUntil("${stuck.size} reads are stuck") {
            reads.blockedReadCount == stuck.size
        }
        disposables.forEach { it.dispose() }

        waitUntil("the stuck channels are closed") {
            stuck.all { reads.closedChannels(it) == reads.openedChannels(it) }
        }
        loadWithin(10_000, "shown.jpg")
        assertEquals(0, reads.blockedReadCount)
    }

    @Test(timeout = 60_000)
    fun rowsScrolledPastBeforeTheirTurnReadNothing() {
        val slow = (1..MAX_PARALLEL_READS).map { writePhoto("slow-$it.jpg") }
        slow.forEach { reads.block(it) }
        val skipped = (1..6).map { writePhoto("skipped-$it.jpg") }
        writePhoto("last.jpg")

        val slowDisposables = slow.map { loading.enqueue(fileSystem.path(it), 96, 96) }
        waitUntil("${slow.size} reads are stuck") { reads.blockedReadCount == slow.size }
        // These wait for a permit, and scroll away before they get one.
        val skippedDisposables = skipped.map { loading.enqueue(fileSystem.path(it), 96, 96) }
        SystemClock.sleep(300)
        skippedDisposables.forEach { it.dispose() }
        reads.unblockAll()

        for ((name, disposable) in slow.zip(slowDisposables)) {
            assertSuccess(name, disposable.awaitResult())
        }
        // Permits are handed out in order, so anything still queued would have been read first.
        loadWithin(10_000, "last.jpg")
        for (name in skipped) {
            assertEquals(name, 0, reads.openedChannels(name))
            assertEquals(name, 0, reads.readCount(name))
            assertEquals(name, 0L, reads.bytesRead(name))
        }
    }

    private fun writeVideo(name: String): String {
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { input ->
            File(directory, name).outputStream().use { input.copyTo(it) }
        }
        return name
    }

    private fun writePhoto(name: String): String {
        TestJpeg.write(File(directory, name), 800, 600)
        return name
    }

    private fun loadWithin(timeoutMillis: Long, name: String): SuccessResult {
        val result = runBlocking {
            withTimeout(timeoutMillis) { loading.execute(fileSystem.path(name), 96, 96) }
        }
        return assertSuccess(name, result)
    }

    private fun assertSuccess(name: String, result: ImageResult): SuccessResult = when (result) {
        is SuccessResult -> result
        is ErrorResult -> throw AssertionError("Failed to load $name", result.throwable)
    }

    private fun Disposable.awaitResult(): ImageResult =
        runBlocking { withTimeout(10_000) { job.await() } }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw AssertionError("Timed out waiting until $description")
            }
            SystemClock.sleep(20)
        }
    }

    companion object {
        /** The number of files [RemoteThumbnails] reads at once. */
        private const val MAX_PARALLEL_READS = 4

        private const val WAIT_TIMEOUT_MILLIS = 5_000L
    }
}
