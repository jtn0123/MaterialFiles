/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import java.io.IOException
import java.util.concurrent.CancellationException
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What decides how large a thumbnail is fetched, and under which key it is kept. */
class ThumbnailSizeTest {
    private val attributes = TestAttributes(lastModifiedMillis = 1_600_000_000_000, size = 4096)

    @Test
    fun aListIconAndAGridCellAreBothThumbnails() {
        assertEquals(96 to 96, RemoteThumbnails.getThumbnailSize(Size(96, 96)))
        assertEquals(1024 to 768, RemoteThumbnails.getThumbnailSize(Size(1024, 768)))
    }

    @Test
    fun anImageForAViewerIsNotAThumbnail() {
        assertNull(RemoteThumbnails.getThumbnailSize(Size(1025, 768)))
        assertNull(RemoteThumbnails.getThumbnailSize(Size(768, 1025)))
        assertNull(RemoteThumbnails.getThumbnailSize(Size.ORIGINAL))
        assertNull(
            RemoteThumbnails.getThumbnailSize(Size(Dimension.Undefined, Dimension.Pixels(96)))
        )
    }

    @Test
    fun sizesAFewPixelsApartShareOneMemoryKey() {
        val path = TestPath("/photos/beach.jpg")
        val key = RemoteThumbnails.createMemoryKey(path, attributes, Size(140, 139))
        assertEquals(
            key,
            RemoteThumbnails.createMemoryKey(path, attributes, Size(150, 150))
        )
        assertTrue(key, key.startsWith("/photos/beach.jpg:1600000000000:4096:"))
        assertTrue(key, key.endsWith(":256x256"))
    }

    @Test
    fun aGridCellDoesNotGetTheThumbnailDecodedForAListIcon() {
        val path = TestPath("/photos/beach.jpg")
        assertNotEquals(
            RemoteThumbnails.createMemoryKey(path, attributes, Size(96, 96)),
            RemoteThumbnails.createMemoryKey(path, attributes, Size(512, 512))
        )
    }

    @Test
    fun anImageForAViewerIsKeptUnderItsOwnKey() {
        val path = TestPath("/photos/beach.jpg")
        assertTrue(
            RemoteThumbnails.createMemoryKey(path, attributes, Size.ORIGINAL)
                .endsWith(":original")
        )
    }

    @Test
    fun aChangedFileIsNotShownWithTheOldThumbnail() {
        val path = TestPath("/photos/beach.jpg")
        val key = RemoteThumbnails.createMemoryKey(path, attributes, Size(96, 96))
        val changed = TestAttributes(lastModifiedMillis = 1_600_000_060_000, size = 4096)
        assertNotEquals(key, RemoteThumbnails.createMemoryKey(path, changed, Size(96, 96)))
        val resized = TestAttributes(lastModifiedMillis = 1_600_000_000_000, size = 8192)
        assertNotEquals(key, RemoteThumbnails.createMemoryKey(path, resized, Size(96, 96)))
    }

    @Test
    fun aServerThatFailedIsWorthAskingAgainButAFileThatCannotBeShownIsNot() {
        assertTrue(RemoteThumbnails.isWorthReadingAgain(IOException("Connection reset")))
        assertTrue(RemoteThumbnails.isWorthReadingAgain(CancellationException()))
        assertFalse(RemoteThumbnails.isWorthReadingAgain(IllegalStateException("Cannot decode")))
        assertFalse(RemoteThumbnails.isWorthReadingAgain(OutOfMemoryError()))
    }

    @Test
    fun aFrameIsScaledDownToTheViewButNeverUpForAnInexactRequest() {
        assertEquals(
            0.5,
            computeFrameScale(1920, 1080, 960, 540, Scale.FIT, allowInexactSize = true),
            0.001
        )
        assertEquals(
            1.0,
            computeFrameScale(320, 180, 1920, 1080, Scale.FIT, allowInexactSize = true),
            0.001
        )
        assertEquals(
            6.0,
            computeFrameScale(320, 180, 1920, 1080, Scale.FIT, allowInexactSize = false),
            0.001
        )
    }

    @Test
    fun aFrameIsScaledToCoverTheViewWhenItIsFilled() {
        assertEquals(
            1.0,
            computeFrameScale(1920, 1080, 1920, 1920, Scale.FIT, allowInexactSize = false),
            0.001
        )
        assertEquals(
            1.7777,
            computeFrameScale(1920, 1080, 1920, 1920, Scale.FILL, allowInexactSize = false),
            0.001
        )
    }

    @Test
    fun aVideoRecordedSidewaysIsShownUpright() {
        assertEquals(1080 to 1920, videoDisplaySize(90, 1920, 1080))
        assertEquals(1080 to 1920, videoDisplaySize(270, 1920, 1080))
        assertEquals(1920 to 1080, videoDisplaySize(0, 1920, 1080))
        assertEquals(1920 to 1080, videoDisplaySize(180, 1920, 1080))
    }

    @Test
    fun theFrameTakenIsAThirdIntoTheVideo() {
        assertEquals(10_000_000, frameMicrosOf(30_000, 1.0 / 3.0))
        assertEquals(0, frameMicrosOf(0, 1.0 / 3.0))
        assertEquals(30_000_000, frameMicrosOf(30_000, 1.0))
    }

    private class TestAttributes(private val lastModifiedMillis: Long, private val size: Long) :
        BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(lastModifiedMillis)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(lastModifiedMillis)

        override fun creationTime(): FileTime = FileTime.fromMillis(lastModifiedMillis)

        override fun isRegularFile(): Boolean = true

        override fun isDirectory(): Boolean = false

        override fun isSymbolicLink(): Boolean = false

        override fun isOther(): Boolean = false

        override fun size(): Long = size

        override fun fileKey(): Any = throw UnsupportedOperationException()
    }
}
