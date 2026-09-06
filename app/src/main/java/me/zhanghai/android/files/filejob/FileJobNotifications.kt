/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.app.PendingIntent
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.provider.common.readAttributes

internal fun FileJob.postNotification(
    title: CharSequence,
    text: CharSequence?,
    subText: CharSequence?,
    info: CharSequence?,
    max: Int,
    progress: Int,
    indeterminate: Boolean,
    showCancel: Boolean
) {
    val notification = fileJobNotificationTemplate.createBuilder(service).apply {
        setContentTitle(title)
        setContentText(text)
        setSubText(subText)
        setContentInfo(info)
        setProgress(max, progress, indeterminate)
        // TODO
        //setContentIntent()
        if (showCancel) {
            val intent = FileJobReceiver.createIntent(id)
            var pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingIntentFlags = pendingIntentFlags or PendingIntent.FLAG_IMMUTABLE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                service,
                id + 1,
                intent,
                pendingIntentFlags
            )
            addAction(
                R.drawable.close_icon_white_24dp,
                getString(android.R.string.cancel),
                pendingIntent
            )
        }
    }.build()
    service.notificationManager.notify(id, notification)
}

internal const val PROGRESS_INTERVAL_MILLIS = 200L

private const val NOTIFICATION_INTERVAL_MILLIS = 500L

internal fun FileJob.postScanNotification(scanInfo: ScanInfo, @PluralsRes titleRes: Int) {
    if (!scanInfo.shouldPostNotification()) {
        return
    }
    val size = scanInfo.size.asFileSize().formatHumanReadable(service)
    val fileCount: Int = scanInfo.fileCount
    val title: String = getQuantityString(titleRes, fileCount, fileCount, size)
    postNotification(title, null, null, null, 0, 0, true, true)
}

internal class ScanInfo {
    var fileCount = 0
        private set
    var size = 0L
        private set

    private var lastNotificationTimeMillis = 0L

    fun incrementFileCount() {
        ++fileCount
    }

    fun addToSize(size: Long) {
        this.size += size
    }

    fun shouldPostNotification(): Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        return if (fileCount % 100 == 0 ||
            lastNotificationTimeMillis + NOTIFICATION_INTERVAL_MILLIS < currentTimeMillis
        ) {
            lastNotificationTimeMillis = currentTimeMillis
            true
        } else {
            false
        }
    }
}

internal fun FileJob.postTransferSizeNotification(
    transferInfo: TransferInfo,
    currentSource: Path,
    @StringRes titleOneRes: Int,
    @PluralsRes titleMultipleRes: Int
) {
    if (!transferInfo.shouldPostNotification()) {
        return
    }
    val title: String
    val text: String
    val fileCount = transferInfo.fileCount
    val target = transferInfo.target!!
    val size = transferInfo.size
    val transferredSize = transferInfo.transferredSize
    if (fileCount == 1) {
        title = getString(titleOneRes, getFileName(currentSource), getFileName(target))
        val sizeString = size.asFileSize().formatHumanReadable(service)
        val transferredSizeString = transferredSize.asFileSize().formatHumanReadable(service)
        text = getString(
            R.string.file_job_transfer_size_notification_text_one_format,
            transferredSizeString,
            sizeString
        )
    } else {
        title = getQuantityString(titleMultipleRes, fileCount, fileCount, getFileName(target))
        val currentFileIndex = (transferInfo.transferredFileCount + 1)
            .coerceAtMost(fileCount)
        text = getString(
            R.string.file_job_transfer_size_notification_text_multiple_format,
            currentFileIndex,
            fileCount
        )
    }
    val max: Int
    val progress: Int
    if (size <= Int.MAX_VALUE) {
        max = size.toInt()
        progress = transferredSize.toInt()
    } else {
        var maxLong = size
        var progressLong = transferredSize
        while (maxLong > Int.MAX_VALUE) {
            maxLong /= 2
            progressLong /= 2
        }
        max = maxLong.toInt()
        progress = progressLong.toInt()
    }
    postNotification(title, text, null, null, max, progress, false, true)
}

internal fun FileJob.postTransferCountNotification(
    transferInfo: TransferInfo,
    currentPath: Path,
    @StringRes titleOneRes: Int,
    @PluralsRes titleMultipleRes: Int
) {
    if (!transferInfo.shouldPostNotification()) {
        return
    }
    val title: String
    val text: String?
    val max: Int
    val progress: Int
    val indeterminate: Boolean
    val fileCount = transferInfo.fileCount
    if (fileCount == 1) {
        title = getString(titleOneRes, getFileName(currentPath))
        text = null
        max = 0
        progress = 0
        indeterminate = true
    } else {
        title = getQuantityString(titleMultipleRes, fileCount, fileCount)
        val transferredFileCount = transferInfo.transferredFileCount
        val currentFileIndex = (transferredFileCount + 1).coerceAtMost(fileCount)
        text = getString(
            R.string.file_job_transfer_count_notification_text_multiple_format,
            currentFileIndex,
            fileCount
        )
        max = fileCount
        progress = transferredFileCount
        indeterminate = false
    }
    postNotification(title, text, null, null, max, progress, indeterminate, true)
}

internal class TransferInfo(scanInfo: ScanInfo, val target: Path?) {
    var fileCount: Int = scanInfo.fileCount
        private set
    var transferredFileCount = 0
        private set
    var size: Long = scanInfo.size
        private set
    var transferredSize = 0L
        private set

    private var lastNotificationTimeMillis = 0L

    fun incrementTransferredFileCount() {
        ++transferredFileCount
    }

    fun addTransferredFile(size: Long) {
        ++transferredFileCount
        transferredSize += size
    }

    fun skipFile(path: Path) {
        --fileCount
        try {
            size -= path.readAttributes(
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            ).size()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun skipFileIgnoringSize() {
        --fileCount
    }

    fun addToTransferredSize(size: Long) {
        transferredSize += size
    }

    fun shouldPostNotification(): Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        return if (lastNotificationTimeMillis + NOTIFICATION_INTERVAL_MILLIS < currentTimeMillis) {
            lastNotificationTimeMillis = currentTimeMillis
            true
        } else {
            false
        }
    }
}
