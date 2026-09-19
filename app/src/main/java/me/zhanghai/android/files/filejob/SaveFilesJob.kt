/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getQuantityString

/**
 * Saves several shared files into [targetDirectory] under [names], which the sender chose (the
 * content provider's display names), asking about conflicts like a copy would.
 */
class SaveFilesJob(
    private val sources: List<Path>,
    private val targetDirectory: Path,
    private val names: List<String>
) : FileJob() {
    init {
        require(sources.size == names.size) { "Every source needs a name" }
    }

    @Throws(IOException::class)
    override fun run() {
        val scanInfo = scan(sources, R.plurals.file_job_copy_scan_notification_title_format)
        val transferInfo = TransferInfo(scanInfo, targetDirectory)
        val actionAllInfo = ActionAllInfo()
        var savedCount = 0
        for ((source, name) in sources.zip(names)) {
            if (copy(source, targetDirectory.resolve(name), false, transferInfo, actionAllInfo)) {
                ++savedCount
            }
            throwIfInterrupted()
        }
        if (savedCount > 0) {
            showToast(
                service.getQuantityString(
                    R.plurals.save_as_files_success_format,
                    savedCount,
                    savedCount,
                    getFileName(targetDirectory)
                )
            )
        }
    }
}
