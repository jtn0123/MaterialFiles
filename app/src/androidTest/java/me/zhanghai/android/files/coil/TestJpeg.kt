/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Writes the kind of JPEG a camera produces: a photo with a small thumbnail of it embedded in an
 * Exif segment, so that a reader can show something before it has read the photo itself.
 */
object TestJpeg {
    /** A red-and-blue image, recognizable after scaling and rotation. */
    fun createImage(width: Int, height: Int): Bitmap = createBitmap(width, height).applyPattern()

    /**
     * An image of random pixels, which JPEG cannot compress much: a few megapixels of it make a
     * file as large as a camera's.
     */
    fun createNoisyImage(width: Int, height: Int): Bitmap {
        val random = Random(width * 31 + height)
        val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
        return createBitmap(width, height).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun write(
        file: File,
        width: Int,
        height: Int,
        thumbnailWidth: Int = 0,
        thumbnailHeight: Int = 0,
        orientation: Int = ORIENTATION_NORMAL,
        isNoisy: Boolean = false
    ) {
        val image = (if (isNoisy) createNoisyImage(width, height) else createImage(width, height))
            .toJpegBytes()
        val thumbnail = if (thumbnailWidth > 0 && thumbnailHeight > 0) {
            createImage(thumbnailWidth, thumbnailHeight).toJpegBytes()
        } else {
            null
        }
        file.outputStream().use { outputStream ->
            if (thumbnail != null || orientation != ORIENTATION_NORMAL) {
                // The Exif segment goes right after the start-of-image marker.
                outputStream.write(image, 0, 2)
                outputStream.write(createExifSegment(thumbnail, orientation))
                outputStream.write(image, 2, image.size - 2)
            } else {
                outputStream.write(image)
            }
        }
    }

    /** The metadata a camera writes onto a photo: what took it, how, and where. */
    fun writeExif(file: File) {
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Canon")
            setAttribute(ExifInterface.TAG_MODEL, "Canon EOS 5D")
            setAttribute(ExifInterface.TAG_F_NUMBER, "28/10")
            // APEX 7, which is 1/128 s.
            setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE, "7/1")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "50/1")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:03 12:34:56")
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "A test photo")
            setAttribute(ExifInterface.TAG_ARTIST, "Ansel")
            setAttribute(ExifInterface.TAG_COPYRIGHT, "Public domain")
            setAttribute(ExifInterface.TAG_SOFTWARE, "Material Files tests")
            setLatLong(37.8, -122.4)
            setAltitude(12.3)
            saveAttributes()
        }
    }

    private fun Bitmap.applyPattern(): Bitmap = apply {
        val paint = Paint().apply { color = Color.BLUE }
        Canvas(this).apply {
            drawColor(Color.RED)
            drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        }
    }

    private fun Bitmap.toJpegBytes(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    /**
     * An `APP1` segment holding a little-endian TIFF header, an IFD0 with the orientation and an
     * IFD1 pointing at the embedded thumbnail.
     */
    private fun createExifSegment(thumbnail: ByteArray?, orientation: Int): ByteArray {
        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))
        tiff.writeInt(TIFF_HEADER_SIZE)
        // IFD0: the orientation, and a link to IFD1 when there is a thumbnail.
        tiff.writeShort(1)
        tiff.writeEntry(TAG_ORIENTATION, TYPE_SHORT, 1) {
            writeShort(orientation)
            writeShort(0)
        }
        tiff.writeInt(if (thumbnail != null) IFD1_OFFSET else 0)
        if (thumbnail != null) {
            tiff.writeShort(3)
            tiff.writeEntry(TAG_COMPRESSION, TYPE_SHORT, 1) {
                writeShort(COMPRESSION_JPEG)
                writeShort(0)
            }
            tiff.writeEntry(TAG_THUMBNAIL_OFFSET, TYPE_LONG, 1) { writeInt(THUMBNAIL_OFFSET) }
            tiff.writeEntry(TAG_THUMBNAIL_LENGTH, TYPE_LONG, 1) { writeInt(thumbnail.size) }
            tiff.writeInt(0)
            tiff.write(thumbnail)
        }
        val payload = EXIF_IDENTIFIER + tiff.toByteArray()
        val segment = ByteArrayOutputStream()
        segment.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
        val length = payload.size + 2
        segment.write(byteArrayOf((length shr 8).toByte(), length.toByte()))
        segment.write(payload)
        return segment.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        writeShort(value and 0xFFFF)
        writeShort((value shr 16) and 0xFFFF)
    }

    private inline fun ByteArrayOutputStream.writeEntry(
        tag: Int,
        type: Int,
        count: Int,
        writeValue: ByteArrayOutputStream.() -> Unit
    ) {
        writeShort(tag)
        writeShort(type)
        writeInt(count)
        writeValue()
    }

    const val ORIENTATION_NORMAL = 1

    /** Flipped horizontally. */
    const val ORIENTATION_FLIP_HORIZONTAL = 2

    /** Rotated 90 degrees clockwise, as a camera held sideways records it. */
    const val ORIENTATION_ROTATE_90 = 6

    private val EXIF_IDENTIFIER = "Exif".toByteArray() + byteArrayOf(0, 0)
    private const val TIFF_HEADER_SIZE = 8
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_COMPRESSION = 0x0103
    private const val TAG_THUMBNAIL_OFFSET = 0x0201
    private const val TAG_THUMBNAIL_LENGTH = 0x0202
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val COMPRESSION_JPEG = 6

    /** After the header, the entry count, one entry and the link to the next directory. */
    private const val IFD1_OFFSET = TIFF_HEADER_SIZE + 2 + 12 + 4

    /** After IFD1's entry count, its three entries and its (empty) link to the next directory. */
    private const val THUMBNAIL_OFFSET = IFD1_OFFSET + 2 + 3 * 12 + 4
}
