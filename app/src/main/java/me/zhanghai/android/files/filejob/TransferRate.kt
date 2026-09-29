/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The speed of a transfer, smoothed so that one slow or fast moment (a small file, a pause while a
 * dialog waits for the user) does not make the time left jump around.
 */
internal class TransferRate(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var lastTimeMillis = -1L
    private var lastTransferredBytes = 0L

    /** Bytes per second, or `null` until there have been two samples to compare. */
    var bytesPerSecond: Double? = null
        private set

    /** Records that [transferredBytes] have been transferred in total by now. */
    fun update(transferredBytes: Long) {
        val timeMillis = clockMillis()
        if (lastTimeMillis < 0) {
            lastTimeMillis = timeMillis
            lastTransferredBytes = transferredBytes
            return
        }
        val elapsedMillis = timeMillis - lastTimeMillis
        if (elapsedMillis <= 0) {
            return
        }
        val sampleBytesPerSecond =
            (transferredBytes - lastTransferredBytes).coerceAtLeast(0) * 1000.0 / elapsedMillis
        val bytesPerSecond = bytesPerSecond
        this.bytesPerSecond = if (bytesPerSecond == null) {
            sampleBytesPerSecond
        } else {
            bytesPerSecond + SMOOTHING * (sampleBytesPerSecond - bytesPerSecond)
        }
        lastTimeMillis = timeMillis
        lastTransferredBytes = transferredBytes
    }

    /**
     * The whole seconds [remainingBytes] will take at the current speed, or `null` when the speed
     * is not known yet or nothing is moving.
     */
    fun remainingSeconds(remainingBytes: Long): Long? {
        val bytesPerSecond = bytesPerSecond ?: return null
        if (bytesPerSecond <= 0 || remainingBytes <= 0) {
            return null
        }
        return ceil(remainingBytes / bytesPerSecond).toLong()
    }

    companion object {
        /** How much each new sample moves the speed; lower is steadier but slower to follow. */
        const val SMOOTHING = 0.3
    }
}

/** A time left, rounded to what is worth showing: seconds in fives, then minutes, then hours. */
internal sealed class RemainingTime {
    data class Seconds(val seconds: Int) : RemainingTime()

    data class Minutes(val minutes: Int) : RemainingTime()

    data class Hours(val hours: Int, val minutes: Int) : RemainingTime()

    companion object {
        fun of(seconds: Long): RemainingTime = when {
            seconds <= 55 -> Seconds((ceil(seconds.coerceAtLeast(1) / 5.0) * 5).toInt())

            seconds < 60 * 60 -> Minutes(ceil(seconds / 60.0).toInt().coerceAtMost(59))

            else -> {
                val totalMinutes = (seconds / 60.0).roundToLong()
                Hours((totalMinutes / 60).toInt(), (totalMinutes % 60).toInt())
            }
        }
    }
}

/** Joins the parts of a notification text that are known, with a middle dot between them. */
internal fun joinTransferDetails(vararg parts: String?): String? =
    parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" · ")
