/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil.decode.DataSource
import coil.decode.DecodeUtils
import coil.fetch.DrawableResult
import coil.request.Options
import coil.request.videoFrameOption
import coil.request.videoFramePercent
import coil.size.Scale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import me.zhanghai.android.files.compat.getFrameAtTimeCompat
import me.zhanghai.android.files.compat.getScaledFrameAtTimeCompat

/** 1/3 is the first percentage tried by totem-video-thumbnailer. */
// @see https://gitlab.gnome.org/GNOME/totem/-/blob/master/src/totem-video-thumbnailer.c#L543
private const val DEFAULT_FRAME_PERCENT = 1.0 / 3.0

/**
 * Decodes the frame that represents a video, from a retriever whose data source is already set.
 * Taking the retriever lets the caller ask the same one for an embedded picture first, which
 * matters when every open costs a round trip to a server.
 *
 * @see coil.decode.VideoFrameDecoder
 */
fun MediaMetadataRetriever.decodeVideoFrame(options: Options): DrawableResult {
    var (srcWidth, srcHeight) = getVideoSize()
    val frameMicros = getFrameMicros(options)
    val frameOption = options.parameters.videoFrameOption()
        ?: MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    val bitmapParams =
        MediaMetadataRetriever.BitmapParams().apply { preferredConfig = options.config }
    // Without the size of the video there is nothing to scale to, so the frame is taken as it is.
    val outBitmap = if (srcWidth > 0 && srcHeight > 0) {
        val scale = options.computeFrameScale(srcWidth, srcHeight)
        getScaledFrameAtTimeCompat(
            frameMicros,
            frameOption,
            (scale * srcWidth).roundToInt(),
            (scale * srcHeight).roundToInt(),
            bitmapParams
        )
    } else {
        getFrameAtTimeCompat(frameMicros, frameOption, bitmapParams)?.also {
            srcWidth = it.width
            srcHeight = it.height
        }
    }
    checkNotNull(outBitmap) { "Failed to decode frame at $frameMicros microseconds" }
    val scale = options.computeFrameScale(srcWidth, srcHeight)
    val width = (scale * srcWidth).roundToInt()
    val height = (scale * srcHeight).roundToInt()
    return DrawableResult(
        drawable = outBitmap.toRequestedFrame(options, scale, width, height)
            .toDrawable(options.context.resources),
        isSampled = scale < 1.0,
        dataSource = DataSource.DISK
    )
}

/** The size of the video as it is shown, which a rotated one does not report directly. */
private fun MediaMetadataRetriever.getVideoSize(): Pair<Int, Int> {
    val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        ?.toIntOrNull() ?: 0
    val width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        ?.toIntOrNull() ?: 0
    val height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        ?.toIntOrNull() ?: 0
    return videoDisplaySize(rotation, width, height)
}

/** A video recorded sideways reports the size of its frames, which is the shown size rotated. */
internal fun videoDisplaySize(rotationDegrees: Int, width: Int, height: Int): Pair<Int, Int> =
    when (rotationDegrees) {
        90, 270 -> height to width
        else -> width to height
    }

private fun MediaMetadataRetriever.getFrameMicros(options: Options): Long {
    val durationMillis = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull() ?: 0L
    val framePercent = options.parameters.videoFramePercent() ?: DEFAULT_FRAME_PERCENT
    return frameMicrosOf(durationMillis, framePercent)
}

/** The time of the frame at [framePercent] of a video that is [durationMillis] long. */
internal fun frameMicrosOf(durationMillis: Long, framePercent: Double): Long =
    TimeUnit.MICROSECONDS.convert(
        (framePercent * durationMillis).roundToLong(),
        TimeUnit.MILLISECONDS
    )

/**
 * How much a frame is scaled to fill the requested size. A request that takes any size near it is
 * never scaled up, because a blurry larger frame is no better than the sharp smaller one.
 */
internal fun computeFrameScale(
    srcWidth: Int,
    srcHeight: Int,
    dstWidth: Int,
    dstHeight: Int,
    scale: Scale,
    allowInexactSize: Boolean
): Double {
    val rawScale = DecodeUtils.computeSizeMultiplier(
        srcWidth = srcWidth,
        srcHeight = srcHeight,
        dstWidth = dstWidth,
        dstHeight = dstHeight,
        scale = scale
    )
    return if (allowInexactSize) rawScale.coerceAtMost(1.0) else rawScale
}

private fun Options.computeFrameScale(srcWidth: Int, srcHeight: Int): Double = computeFrameScale(
    srcWidth,
    srcHeight,
    size.widthPx(scale) { srcWidth },
    size.heightPx(scale) { srcHeight },
    scale,
    allowInexactSize
)

/**
 * Redraws the frame at the requested size, or in a config the request can use, when the retriever
 * did not give us one.
 */
private fun Bitmap.toRequestedFrame(
    options: Options,
    scale: Double,
    width: Int,
    height: Int
): Bitmap {
    val isValidSize = if (options.allowInexactSize) {
        this.width <= width && this.height <= height
    } else {
        this.width == width && this.height == height
    }
    val isValidConfig = config?.isHardware != true || options.config.isHardware
    if (isValidSize && isValidConfig) {
        return this
    }
    return createBitmap(width, height, options.config.toSoftware()).applyCanvas {
        scale(scale.toFloat(), scale.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        drawBitmap(this@toRequestedFrame, 0f, 0f, paint)
        this@toRequestedFrame.recycle()
    }
}
