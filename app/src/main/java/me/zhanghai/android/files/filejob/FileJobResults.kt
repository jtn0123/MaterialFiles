/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.notificationManager
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.NotificationTemplate
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.showToast

/** The kinds of transfer whose outcome is worth a notification of its own. */
internal enum class TransferKind(
    @StringRes val finishedOneRes: Int,
    @PluralsRes val finishedMultipleRes: Int,
    @StringRes val failedTitleRes: Int
) {
    COPY(
        R.string.file_job_copy_finished_title_one_format,
        R.plurals.file_job_copy_finished_title_multiple_format,
        R.string.file_job_copy_failed_title
    ),
    EXTRACT(
        R.string.file_job_extract_finished_title_one_format,
        R.plurals.file_job_extract_finished_title_multiple_format,
        R.string.file_job_extract_failed_title
    ),
    MOVE(
        R.string.file_job_move_finished_title_one_format,
        R.plurals.file_job_move_finished_title_multiple_format,
        R.string.file_job_move_failed_title
    )
}

/** What a copy, move or extraction reports once its progress notification is gone. */
internal class TransferResult(
    val kind: TransferKind,
    val sources: List<Path>,
    val targetDirectory: Path
)

private val fileJobResultNotificationTemplate =
    NotificationTemplate(
        fileJobNotificationTemplate.channelTemplate,
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        ongoing = false,
        onlyAlertOnce = true,
        autoCancel = true,
        category = NotificationCompat.CATEGORY_STATUS,
        priority = NotificationCompat.PRIORITY_LOW
    )

/** Distinct from the progress notification, which is cancelled when the job ends. */
internal val FileJob.resultNotificationId: Int
    get() = id + 2

/**
 * Opens the folder a transfer goes to, or the app for any other job, from the notification of
 * this job.
 */
internal fun FileJob.createContentPendingIntent(): PendingIntent {
    val targetDirectory = transferResult?.targetDirectory
    val intent = if (targetDirectory != null) {
        FileListActivity.createViewIntent(targetDirectory)
    } else {
        FileListActivity::class.createIntent()
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(
        service,
        id,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal fun FileJob.getFinishedTitle(result: TransferResult): String =
    if (result.sources.size == 1) {
        getString(
            result.kind.finishedOneRes,
            getFileName(result.sources.single()),
            getFileName(result.targetDirectory)
        )
    } else {
        getQuantityString(
            result.kind.finishedMultipleRes,
            result.sources.size,
            result.sources.size,
            getFileName(result.targetDirectory)
        )
    }

internal fun FileJob.getSkippedErrorsText(): String? = if (skippedErrorCount > 0) {
    getQuantityString(
        R.plurals.file_job_finished_with_skipped_errors_format,
        skippedErrorCount,
        skippedErrorCount
    )
} else {
    null
}

/**
 * Tells the user a transfer finished, and where to, in a notification that stays until they
 * dismiss or tap it. Without notifications only skipped files are worth a toast, as before.
 */
internal fun FileJob.postFinishedNotification(result: TransferResult) {
    val skippedErrorsText = getSkippedErrorsText()
    if (!postResultNotification(getFinishedTitle(result), skippedErrorsText, false)) {
        skippedErrorsText?.let { service.showToast(it) }
    }
}

/** Tells the user a transfer failed and why, falling back to a toast without notifications. */
internal fun FileJob.postFailedNotification(result: TransferResult, message: String) {
    if (!postResultNotification(getString(result.kind.failedTitleRes), message, true)) {
        service.showToast(getString(R.string.file_job_failed_format, message))
    }
}

/** Returns whether the notification could be posted. */
private fun FileJob.postResultNotification(
    title: String,
    text: String?,
    isError: Boolean
): Boolean {
    if (ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED ||
        !notificationManager.areNotificationsEnabled()
    ) {
        return false
    }
    val notification = fileJobResultNotificationTemplate.createBuilder(service).apply {
        setContentTitle(title)
        setContentText(text)
        if (text != null) {
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        if (isError) {
            setCategory(NotificationCompat.CATEGORY_ERROR)
        }
        setContentIntent(createContentPendingIntent())
    }.build()
    notificationManager.notify(resultNotificationId, notification)
    return true
}

/** The speed and the time left of a transfer, for its progress notification. */
internal fun FileJob.getTransferRateTexts(transferInfo: TransferInfo): Array<String?> {
    val rate = transferInfo.rate
    rate.update(transferInfo.transferredSize)
    val bytesPerSecond = rate.bytesPerSecond ?: return arrayOf(null, null)
    val rateText = getString(
        R.string.file_job_transfer_rate_format,
        bytesPerSecond.toLong().asFileSize().formatHumanReadable(service)
    )
    val remainingSeconds = rate.remainingSeconds(transferInfo.size - transferInfo.transferredSize)
    val remainingText = remainingSeconds?.let {
        when (val remainingTime = RemainingTime.of(it)) {
            is RemainingTime.Seconds ->
                getString(R.string.file_job_remaining_seconds_format, remainingTime.seconds)

            is RemainingTime.Minutes ->
                getString(R.string.file_job_remaining_minutes_format, remainingTime.minutes)

            is RemainingTime.Hours -> getString(
                R.string.file_job_remaining_hours_format,
                remainingTime.hours,
                remainingTime.minutes
            )
        }
    }
    return arrayOf(rateText, remainingText)
}
