/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.image

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Returns the camera a photo was taken with, naming its [make] only when the [model] doesn't
 * already start with it.
 */
internal fun getEquipment(
    make: String?,
    model: String?,
    formatMakeAndModel: (String, String) -> String
): String? = when {
    make != null && model != null ->
        if (model.startsWith(make, true)) model else formatMakeAndModel(make, model)

    else -> make ?: model
}

/**
 * Returns an APEX shutter speed [value] as seconds for a long exposure, or as a fraction of a
 * second through [formatDenominator] otherwise.
 *
 * @see com.android.documentsui.inspector.MediaView.formatShutterSpeed
 */
internal fun getShutterSpeedText(value: Double, formatDenominator: (Int) -> String): String =
    if (value <= 0) {
        val shutterSpeed = 2.0.pow(-1 * value)
        ((shutterSpeed * 10.0).roundToInt() / 10.0).toString()
    } else {
        val approximateDenominator = 2.0.pow(value).toInt() + 1
        formatDenominator(approximateDenominator)
    }
