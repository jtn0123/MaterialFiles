/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Intent
import android.os.Environment
import androidx.annotation.StringRes
import java.io.File
import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.BackgroundActivityStarter
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.OpenFileAsDialogActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogFragment
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.createDirectories
import me.zhanghai.android.files.provider.common.resolveForeign
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.withChooser

class OpenFileJob(
    private val file: Path,
    private val mimeType: MimeType,
    private val withChooser: Boolean
) : FileJob() {
    override fun run() {
        open(
            file,
            R.string.file_open_from_background_title_format,
            R.string.file_open_from_background_text
        ) { file ->
            file.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply { extraPath = file }
                .let {
                    if (withChooser) {
                        it.withChooser(
                            OpenFileAsDialogActivity::class.createIntent()
                                .putArgs(OpenFileAsDialogFragment.Args(file))
                        )
                    } else {
                        it
                    }
                }
        }
    }
}

private val FileJob.cacheDirectory: File
    get() =
        service.externalCacheDir?.takeIf {
            Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
        } ?: service.cacheDir

@Throws(IOException::class)
internal fun FileJob.open(
    file: Path,
    @StringRes notificationTitleFormatRes: Int,
    @StringRes notificationTextRes: Int,
    intentCreator: (Path) -> Intent
) {
    val isExtract = file.isArchivePath
    val scanInfo = scan(
        file,
        if (isExtract) {
            R.plurals.file_job_extract_scan_notification_title_format
        } else {
            R.plurals.file_job_copy_scan_notification_title_format
        }
    )
    val cacheDirectory = Paths.get(cacheDirectory.path, "open_cache")
    cacheDirectory.createDirectories()
    val targetFileName = getTargetFileName(file)
    val targetFile = cacheDirectory.resolveForeign(targetFileName)
    val transferInfo = TransferInfo(scanInfo, cacheDirectory)
    val actionAllInfo = ActionAllInfo(replace = true)
    val copied = copy(file, targetFile, isExtract, transferInfo, actionAllInfo)
    if (!copied) {
        return
    }
    BackgroundActivityStarter.startActivity(
        intentCreator(targetFile),
        getString(notificationTitleFormatRes, targetFileName),
        getString(notificationTextRes),
        service
    )
}
