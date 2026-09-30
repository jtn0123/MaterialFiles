/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.math.max
import kotlin.math.min
import me.zhanghai.android.files.coil.toUpright
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.logWarning

/** What the viewer needs to know about an image before it decodes it. */
internal class ImageInfo(
    val attributes: BasicFileAttributes,
    val width: Int,
    val height: Int,
    val mimeType: MimeType,
    /** Clockwise, as the EXIF data of the image asks for it to be shown. */
    val rotationDegrees: Int,
    /** The thumbnail a camera embedded, upright, when it was asked for and there is one. */
    val preview: Bitmap?
)

/**
 * How much of the start of a file the size, the orientation and the embedded thumbnail are read
 * from; a camera puts all three in its first few dozen kilobytes.
 */
private const val HEADER_READ_LIMIT = 512 * 1024

/**
 * Reads the size, the orientation and, if [withPreview], the embedded thumbnail of this image, all
 * from one read of the start of the file: on a share each read is a round trip.
 */
@Throws(IOException::class)
internal fun Path.readImageInfo(attributes: BasicFileAttributes, withPreview: Boolean): ImageInfo {
    val detectedMimeType = AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
    val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    var rotationDegrees = 0
    var preview: Bitmap? = null
    BufferedInputStream(newInputStream(), HEADER_READ_LIMIT).use { inputStream ->
        inputStream.mark(HEADER_READ_LIMIT)
        BitmapFactory.decodeStream(inputStream, null, bitmapOptions)
        val mimeType = bitmapOptions.outMimeType?.asMimeTypeOrNull() ?: detectedMimeType
        if (mimeType == MimeType.IMAGE_JPEG) {
            try {
                inputStream.reset()
                val exifInterface = ExifInterface(inputStream)
                rotationDegrees = exifInterface.rotationDegrees
                if (withPreview) {
                    preview = exifInterface.thumbnailBitmap?.toUpright(exifInterface)
                }
            } catch (e: IOException) {
                // The size alone is enough to show the image.
                e.logWarning("ImageViewerInfo", "Read the EXIF data of $this")
            }
        }
    }
    return ImageInfo(
        attributes,
        bitmapOptions.outWidth,
        bitmapOptions.outHeight,
        bitmapOptions.outMimeType?.asMimeTypeOrNull() ?: detectedMimeType,
        rotationDegrees,
        preview
    )
}

/**
 * The box an image is decoded to fit in: twice the screen, turned the way the image is so that it
 * is sharp at twice its size in either orientation of the device. Zooming in further than that is
 * left to the large image view, which the viewer uses for images too large to decode whole.
 */
internal fun getViewerDecodeSize(
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    screenWidth: Int,
    screenHeight: Int
): Pair<Int, Int> {
    val isSideways = rotationDegrees % 180 != 0
    val shownWidth = if (isSideways) imageHeight else imageWidth
    val shownHeight = if (isSideways) imageWidth else imageHeight
    val longSide = 2 * max(screenWidth, screenHeight)
    val shortSide = 2 * min(screenWidth, screenHeight)
    return if (shownWidth >= shownHeight) longSide to shortSide else shortSide to longSide
}
