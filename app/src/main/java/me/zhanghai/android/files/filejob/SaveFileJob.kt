/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.Path
import me.zhanghai.android.files.R

class SaveFileJob(private val source: Path, private val target: Path) : FileJob() {
    override fun run() {
        save(source, target)
    }
}

@Throws(IOException::class)
private fun FileJob.save(source: Path, target: Path) {
    val scanInfo = scan(source, R.plurals.file_job_copy_scan_notification_title_format)
    val targetParent = target.parent
    val transferInfo = TransferInfo(scanInfo, targetParent)
    val actionAllInfo = ActionAllInfo(replace = true)
    val copied = copy(source, target, false, transferInfo, actionAllInfo)
    if (!copied) {
        return
    }
    showToast(
        getString(R.string.save_as_success_format, getFileName(target), getFileName(targetParent))
    )
}
