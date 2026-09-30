/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.linux.isLinuxPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A video that is not a plain file on the device, such as one on a server, is read by the
 * retriever through a channel of the app's own; and a read that fails, such as one of a channel
 * that was closed to abandon the retriever, is reported for what it was.
 */
@RunWith(AndroidJUnit4::class)
class MediaMetadataRetrieverPathTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File
    private lateinit var path: Path

    @Before
    fun setUp() {
        directory = File(instrumentation.targetContext.filesDir, "retriever-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val file = File(directory, "clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        // A content URI is neither a local file nor a document, like a file on a server.
        path = Paths.get(URI.create(Paths.get(file.path).fileProviderUri.toString()))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aVideoThatIsNotAFileOnTheDeviceIsReadThroughItsChannel() {
        assertFalse(path.isLinuxPath)
        val channels = mutableListOf<Closeable>()
        val failures = mutableListOf<IOException>()

        val duration = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(path, { channels += it }) { failures += it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        }

        assertEquals(1, channels.size)
        assertEquals(emptyList<IOException>(), failures)
        // The clip is 30 s long.
        assertNotNull(duration)
        assertTrue(duration, duration!!.toLong() in 29_000..31_000)
    }

    @Test
    fun aReadOfAnAbandonedChannelIsReportedAndFailsTheRetriever() {
        val failures = mutableListOf<IOException>()

        MediaMetadataRetriever().use { retriever ->
            assertThrows(RuntimeException::class.java) {
                // Closing the channel is how a retriever reading from a server is abandoned.
                retriever.setDataSource(path, { it.close() }) {
                    synchronized(failures) { failures += it }
                }
            }
        }

        val reported = synchronized(failures) { failures.toList() }
        assertTrue("No failed read was reported", reported.isNotEmpty())
    }
}
