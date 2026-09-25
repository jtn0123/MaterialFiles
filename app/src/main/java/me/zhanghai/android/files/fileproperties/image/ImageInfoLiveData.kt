/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.image

import android.graphics.BitmapFactory
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import java.time.Instant
import java8.nio.file.Path
import kotlin.math.roundToInt
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.fileproperties.PathObserverLiveData
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.backgroundExecutor
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.valueCompat
import okio.buffer
import okio.source

class ImageInfoLiveData(path: Path, private val mimeType: MimeType) :
    PathObserverLiveData<Stateful<ImageInfo>>(path) {
    init {
        loadValue()
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        backgroundExecutor.execute {
            val value = try {
                val imageInfo = when (mimeType) {
                    MimeType.IMAGE_SVG_XML -> loadSvgImageInfo()
                    else -> loadBitmapImageInfo()
                }
                Success(imageInfo)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private fun loadSvgImageInfo(): ImageInfo {
        val svg = path.newInputStream()
            // It seems we need Okio for SVG parser to work for files with entities.
            // Something weird is going on with buffering and mark/reset.
            //.buffer()
            //.use { SVG.getFromInputStream(it) }
            .source()
            .buffer()
            .use { SVG.getFromInputStream(it.inputStream()) }
        val width = svg.documentWidth
        val height = svg.documentHeight
        val dimensions = if (width != -1f && height != -1f) {
            Size(width.roundToInt(), height.roundToInt())
        } else {
            svg.documentViewBox?.let { Size(it.width().roundToInt(), it.height().roundToInt()) }
        }
        return ImageInfo(dimensions, null)
    }

    private fun loadBitmapImageInfo(): ImageInfo {
        val bitmapOptions = BitmapFactory.Options()
            .apply { inJustDecodeBounds = true }
        path.newInputStream()
            .buffered()
            .use { BitmapFactory.decodeStream(it, null, bitmapOptions) }
        val width = bitmapOptions.outWidth
        val height = bitmapOptions.outHeight
        val dimensions = if (width != -1 && height != -1) {
            Size(width, height)
        } else {
            null
        }
        val exifInfo = try {
            loadExifInfo()
        } catch (e: Exception) {
            e.logWarning("ImageInfoLiveData", "Load the EXIF info of $path")
            null
        }
        return ImageInfo(dimensions, exifInfo)
    }

    private fun loadExifInfo(): ExifInfo {
        val lastModifiedTime = path.getLastModifiedTime().toInstant()
        return path.newInputStream().buffered().use {
            ExifInterface(it).toExifInfo(lastModifiedTime)
        }
    }

    private fun ExifInterface.toExifInfo(lastModifiedTime: Instant): ExifInfo = ExifInfo(
        dateTimeOriginal = inferDateTimeOriginal(lastModifiedTime),
        gpsCoordinates = latLong?.let { it[0] to it[1] },
        gpsAltitude = gpsAltitude,
        make = getAttributeNotBlank(ExifInterface.TAG_MAKE),
        model = getAttributeNotBlank(ExifInterface.TAG_MODEL),
        fNumber = getAttributeDoubleOrNull(ExifInterface.TAG_F_NUMBER),
        shutterSpeedValue = getAttributeDoubleOrNull(ExifInterface.TAG_SHUTTER_SPEED_VALUE),
        focalLength = getAttributeDoubleOrNull(ExifInterface.TAG_FOCAL_LENGTH),
        photographicSensitivity =
            getAttributeIntOrNull(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
        software = getAttributeNotBlank(ExifInterface.TAG_SOFTWARE),
        description = getAttributeNotBlank(ExifInterface.TAG_IMAGE_DESCRIPTION)
            ?: getAttributeNotBlank(ExifInterface.TAG_USER_COMMENT),
        artist = getAttributeNotBlank(ExifInterface.TAG_ARTIST),
        copyright = getAttributeNotBlank(ExifInterface.TAG_COPYRIGHT)
    )
}
