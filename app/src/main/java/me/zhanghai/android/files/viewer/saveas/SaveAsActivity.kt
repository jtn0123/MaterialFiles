/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.saveas

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.saveAsUris
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.toPathOrNull

/**
 * Saves what another app shares: one file goes through the create-file picker so its name can
 * be edited, several go into a directory the user picks, under the names their provider gives.
 */
class SaveAsActivity : AppActivity() {
    private val createFileLauncher =
        registerForActivityResult(FileListActivity.CreateFileContract(), ::onCreateFileResult)

    private val openDirectoryLauncher =
        registerForActivityResult(FileListActivity.OpenDirectoryContract(), ::onOpenDirectoryResult)

    private val sources: List<Pair<Uri, Path>>
        get() = intent.saveAsUris.mapNotNull { uri -> uri.toPathOrNull()?.let { uri to it } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            // A launcher is already waiting for its result.
            return
        }
        val sources = sources
        if (sources.isEmpty()) {
            showToast(R.string.save_as_error)
            finish()
            return
        }
        val initialPath =
            Paths.get(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            )
        if (sources.size == 1) {
            val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
            val title = sources.single().displayName
            createFileLauncher.launch(Triple(mimeType, title, initialPath))
        } else {
            openDirectoryLauncher.launch(initialPath)
        }
    }

    private fun onCreateFileResult(result: Path?) {
        if (result == null) {
            finish()
            return
        }
        FileJobService.save(sources.single().second, result, this)
        finish()
    }

    private fun onOpenDirectoryResult(result: Path?) {
        if (result == null) {
            finish()
            return
        }
        val sources = sources
        FileJobService.saveAll(
            sources.map { it.second },
            result,
            sources.map { it.displayName },
            this
        )
        finish()
    }

    /** The provider's display name when it has one, otherwise the path's own file name. */
    private val Pair<Uri, Path>.displayName: String
        get() = (first.queryDisplayName() ?: second.fileName.toString()).replace('/', '_')

    private fun Uri.queryDisplayName(): String? {
        if (scheme != ContentResolver.SCHEME_CONTENT) {
            return null
        }
        return try {
            contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0)?.takeIfNotEmpty() else null }
        } catch (e: Exception) {
            e.logWarning("SaveAsActivity", "queryDisplayName($this)")
            null
        }
    }
}
