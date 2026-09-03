/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Environment
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.extension
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.asFileName
import me.zhanghai.android.files.util.asFileNameOrNull
import me.zhanghai.android.files.util.create
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.valueCompat

/** The file picking (`ACTION_GET_CONTENT` and friends) handling of [FileListFragment]. */
internal class FileListPick(private val fragment: FileListFragment) {
    private val viewModel: FileListViewModel
        get() = fragment.viewModel

    /** Resets the trail to the path and pick options requested by the intent that started us. */
    fun resetTrailFromIntent(intent: Intent, argsPath: Path?) {
        var path = argsPath
        var pickOptions: PickOptions? = null
        when (val action = intent.action) {
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_CREATE_DOCUMENT -> {
                val mode = if (action == Intent.ACTION_CREATE_DOCUMENT) {
                    PickOptions.Mode.CREATE_FILE
                } else {
                    PickOptions.Mode.OPEN_FILE
                }
                val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
                val fileName = if (mode == PickOptions.Mode.CREATE_FILE) {
                    intent.getStringExtra(Intent.EXTRA_TITLE)?.asFileNameOrNull()?.value
                        ?: mimeType.extension?.let { "file.$it" } ?: "file"
                } else {
                    null
                }
                val readOnly = action == Intent.ACTION_GET_CONTENT
                val extraMimeTypes = if (mode == PickOptions.Mode.OPEN_FILE) {
                    intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                        ?.mapNotNull { it.asMimeTypeOrNull() }?.takeIfNotEmpty()
                } else {
                    null
                }
                val mimeTypes = extraMimeTypes ?: listOf(mimeType)
                val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                val allowMultiple = mode != PickOptions.Mode.CREATE_FILE &&
                    intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                pickOptions =
                    PickOptions(mode, fileName, readOnly, mimeTypes, localOnly, allowMultiple)
            }

            Intent.ACTION_OPEN_DOCUMENT_TREE -> {
                val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                pickOptions = PickOptions(
                    PickOptions.Mode.OPEN_DIRECTORY,
                    null,
                    false,
                    emptyList(),
                    localOnly,
                    false
                )
            }

            ACTION_VIEW_DOWNLOADS ->
                path = Paths.get(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ).path
                )

            else ->
                if (path != null) {
                    val mimeType = intent.type?.asMimeTypeOrNull()
                    if (mimeType != null && path.isArchiveFile(mimeType)) {
                        path = path.createArchiveRootPath()
                    }
                }
        }
        if (path == null) {
            path = Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
        }
        viewModel.resetTo(path)
        if (pickOptions != null) {
            viewModel.pickOptions = pickOptions
        }
    }

    fun onPickOptionsChanged(pickOptions: PickOptions?) {
        val title = if (pickOptions == null) {
            fragment.getString(R.string.file_list_title)
        } else {
            val count = if (pickOptions.allowMultiple) Int.MAX_VALUE else 1
            when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE ->
                    fragment.getQuantityString(R.plurals.file_list_title_open_file, count)

                PickOptions.Mode.CREATE_FILE ->
                    fragment.getString(R.string.file_list_title_create_file)

                PickOptions.Mode.OPEN_DIRECTORY ->
                    fragment.getQuantityString(R.plurals.file_list_title_open_directory, count)
            }
        }
        fragment.requireActivity().title = title
        fragment.menus.updateSelectAllMenuItem()
        fragment.actionModes.updateOverlayToolbar()
        fragment.actionModes.updateBottomToolbar()
        fragment.adapter.pickOptions = pickOptions
    }

    fun pickFiles(files: FileItemSet) {
        pickPaths(files.mapTo(linkedSetOf()) { it.path })
    }

    fun pickPaths(paths: LinkedHashSet<Path>) {
        val intent = Intent().apply {
            val pickOptions = viewModel.pickOptions!!
            if (paths.size == 1) {
                val path = paths.single()
                data = path.fileProviderUri
                extraPath = path
            } else {
                val mimeTypes = pickOptions.mimeTypes.map { it.value }
                val items = paths.map { ClipData.Item(it.fileProviderUri) }
                clipData = ClipData::class.create(null, mimeTypes, items)
                extraPathList = paths.toList()
            }
            var flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (!pickOptions.readOnly) {
                flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            if (pickOptions.mode == PickOptions.Mode.OPEN_DIRECTORY) {
                flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            }
            addFlags(flags)
        }
        fragment.requireActivity().run {
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    fun confirmReplaceFile(file: FileItem, setFileName: Boolean = true) {
        if (setFileName) {
            val fileName = file.name
            val bottomCreateFileNameEdit = fragment.binding.bottomCreateFileNameEdit
            bottomCreateFileNameEdit.setText(fileName)
            bottomCreateFileNameEdit.setSelection(0, fileName.asFileName().baseName.length)
        }
        ConfirmReplaceFileDialogFragment.show(file, fragment)
    }

    fun replaceFile(file: FileItem) {
        pickFiles(fileItemSetOf(file))
    }

    companion object {
        private const val ACTION_VIEW_DOWNLOADS =
            "me.zhanghai.android.files.intent.action.VIEW_DOWNLOADS"
    }
}
