/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.terminal.Terminal
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.createInstallPackageIntent
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.video.VideoSubtitles
import me.zhanghai.android.files.viewer.video.VideoViewerActivity

/** The operations [FileListFragment] performs on files and on its current directory. */
internal class FileListFileActions(private val fragment: FileListFragment) {
    private val viewModel: FileListViewModel
        get() = fragment.viewModel

    private val adapter: FileListAdapter
        get() = fragment.adapter

    private val currentPath: Path
        get() = viewModel.currentPath

    fun openFile(file: FileItem) {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            if (file.attributes.isDirectory) {
                fragment.navigation.navigateTo(file.path)
            } else {
                when (pickOptions.mode) {
                    PickOptions.Mode.OPEN_FILE -> fragment.pick.pickFiles(fileItemSetOf(file))
                    PickOptions.Mode.CREATE_FILE -> fragment.pick.confirmReplaceFile(file)
                    PickOptions.Mode.OPEN_DIRECTORY -> {}
                }
            }
            return
        }
        if (file.mimeType.isApk) {
            openApk(file)
            return
        }
        if (file.isListable) {
            fragment.navigation.navigateTo(file.listablePath)
            return
        }
        openFileWithIntent(file, false)
    }

    private fun openApk(file: FileItem) {
        if (!file.isListable) {
            installApk(file)
            return
        }
        when (Settings.OPEN_APK_DEFAULT_ACTION.valueCompat) {
            OpenApkDefaultAction.INSTALL -> installApk(file)
            OpenApkDefaultAction.VIEW -> viewApk(file)
            OpenApkDefaultAction.ASK -> OpenApkDialogFragment.show(file, fragment)
        }
    }

    fun installApk(file: FileItem) {
        val path = file.path
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!path.isArchivePath) path.fileProviderUri else null
        } else {
            // PackageInstaller only supports file URI before N.
            if (path.isLinuxPath) Uri.fromFile(path.toFile()) else null
        }
        if (uri != null) {
            fragment.startActivitySafe(uri.createInstallPackageIntent())
        } else {
            FileJobService.installApk(path, fragment.requireContext())
        }
    }

    fun viewApk(file: FileItem) {
        fragment.navigation.navigateTo(file.listablePath)
    }

    fun openFileWith(file: FileItem) {
        openFileWithIntent(file, true)
    }

    private fun openFileWithIntent(file: FileItem, withChooser: Boolean) {
        val path = file.path
        val mimeType = file.mimeType
        if (path.isArchivePath) {
            FileJobService.open(path, mimeType, withChooser, fragment.requireContext())
        } else {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply {
                    // Open images and videos in our own viewers directly. Sending them
                    // through the resolver lists both our viewer and SaveAsActivity, and
                    // some resolvers (One UI) collapse the two into a single app entry
                    // whose "Always" choice then lands on "Save as" for every tap.
                    //
                    // The private path extras (the real path, its siblings, subtitle
                    // candidates) go only on that explicit launch of our own viewer. Anything
                    // that reaches the resolver or a chooser may land in another app, and
                    // must not carry the token that makes those extras trusted.
                    if (!withChooser && maybeSetBuiltInViewer(this, mimeType)) {
                        extraPath = path
                        maybeAddImageViewerActivityExtras(this, path, mimeType)
                        maybeAddVideoViewerActivityExtras(this, path, mimeType)
                    }
                }
                .let {
                    if (withChooser) {
                        it.withChooser(
                            EditFileActivity::class.createIntent()
                                .putArgs(EditFileActivity.Args(path, mimeType)),
                            OpenFileAsDialogActivity::class.createIntent()
                                .putArgs(OpenFileAsDialogFragment.Args(path))
                        )
                    } else {
                        it
                    }
                }
            fragment.startActivitySafe(intent)
        }
    }

    /** Returns whether [intent] now targets one of our own viewers. */
    private fun maybeSetBuiltInViewer(intent: Intent, mimeType: MimeType): Boolean {
        val viewerClass = when {
            mimeType.isImage -> ImageViewerActivity::class.java
            mimeType.isVideo -> VideoViewerActivity::class.java
            else -> return false
        }
        intent.setClass(fragment.requireContext(), viewerClass)
        return true
    }

    private fun maybeAddImageViewerActivityExtras(intent: Intent, path: Path, mimeType: MimeType) {
        if (!mimeType.isImage) {
            return
        }
        val (paths, position) = collectSiblingPaths(path) { it.isImage } ?: return
        ImageViewerActivity.putExtras(intent, paths, position)
    }

    private fun maybeAddVideoViewerActivityExtras(intent: Intent, path: Path, mimeType: MimeType) {
        if (!mimeType.isVideo) {
            return
        }
        val (paths, position) = collectSiblingPaths(path) { it.isVideo } ?: return
        val subtitlePaths = (0..<adapter.itemCount)
            .map { adapter.getItem(it).path }
            .filter { VideoSubtitles.isSidecarCandidate(it) }
        VideoViewerActivity.putExtras(intent, paths, position, subtitlePaths)
    }

    /**
     * Collects the paths of the files in this directory that [predicate] accepts, so that a viewer
     * can walk through them, along with the position of [path] among them.
     */
    private fun collectSiblingPaths(
        path: Path,
        predicate: (MimeType) -> Boolean
    ): Pair<List<Path>, Int>? {
        val paths = mutableListOf<Path>()
        // We need the ordered list from our adapter instead of the list from FileListLiveData.
        for (index in 0..<adapter.itemCount) {
            val file = adapter.getItem(index)
            val filePath = file.path
            if (predicate(file.mimeType) || filePath == path) {
                paths.add(filePath)
            }
        }
        val position = paths.indexOf(path)
        if (position == -1) {
            return null
        }
        // The list travels in an intent, so bound it by size rather than by count.
        return windowWithinBudget(paths, position, VIEWER_ACTIVITY_PATH_URI_LENGTH_MAX) {
            it.toUri().toString().length
        }
    }

    fun cutFiles(files: FileItemSet) {
        viewModel.addToPasteState(false, files)
        viewModel.selectFiles(files, false)
    }

    fun copyFiles(files: FileItemSet) {
        viewModel.addToPasteState(true, files)
        viewModel.selectFiles(files, false)
    }

    fun confirmDeleteFiles(files: FileItemSet) {
        ConfirmDeleteFilesDialogFragment.show(files, fragment)
    }

    fun deleteFiles(files: FileItemSet) {
        FileJobService.delete(makePathListForJob(files), fragment.requireContext())
        viewModel.selectFiles(files, false)
    }

    fun extractFiles(files: FileItemSet) {
        copyFiles(files.mapTo(fileItemSetOf()) { it.createDummyArchiveRoot() })
        viewModel.selectFiles(files, false)
    }

    fun showCreateArchiveDialog(files: FileItemSet) {
        CreateArchiveDialogFragment.show(files, fragment)
    }

    fun archive(files: FileItemSet, name: String, format: Int, filter: Int, password: String?) {
        val archiveFile = currentPath.resolve(name)
        FileJobService.archive(
            makePathListForJob(files),
            archiveFile,
            format,
            filter,
            password,
            fragment.requireContext()
        )
        viewModel.selectFiles(files, false)
    }

    fun shareFiles(files: FileItemSet) {
        shareFiles(files.map { it.path }, files.map { it.mimeType })
        viewModel.selectFiles(files, false)
    }

    fun pasteFiles(targetDirectory: Path) {
        val pasteState = viewModel.pasteState
        if (viewModel.pasteState.copy) {
            FileJobService.copy(
                makePathListForJob(pasteState.files),
                targetDirectory,
                fragment.requireContext()
            )
        } else {
            FileJobService.move(
                makePathListForJob(pasteState.files),
                targetDirectory,
                fragment.requireContext()
            )
        }
        viewModel.clearPasteState()
    }

    private fun makePathListForJob(files: FileItemSet): List<Path> =
        files.map { it.path }.sortedBy { it.toUri() }

    fun showRenameFileDialog(file: FileItem) {
        RenameFileDialogFragment.show(file, fragment)
    }

    fun hasFileWithName(name: String): Boolean = getFileWithName(name) != null

    fun getFileWithName(name: String): FileItem? {
        val fileListData = viewModel.fileListStateful
        if (fileListData !is Success) {
            return null
        }
        return fileListData.value.find { it.name == name }
    }

    fun renameFile(file: FileItem, newName: String) {
        FileJobService.rename(file.path, newName, fragment.requireContext())
        viewModel.selectFile(file, false)
    }

    fun share() {
        shareFile(currentPath, MimeType.DIRECTORY)
    }

    fun shareFile(file: FileItem) {
        shareFile(file.path, file.mimeType)
    }

    private fun shareFile(path: Path, mimeType: MimeType) {
        shareFiles(listOf(path), listOf(mimeType))
    }

    private fun shareFiles(paths: List<Path>, mimeTypes: List<MimeType>) {
        val uris = paths.map { it.fileProviderUri }
        val intent = uris.createSendStreamIntent(mimeTypes)
            .withChooser()
        fragment.startActivitySafe(intent)
    }

    fun copyPath() {
        copyPath(currentPath)
    }

    fun copyPath(path: Path) {
        clipboardManager.copyText(path.toUserFriendlyString(), fragment.requireContext())
    }

    fun openInTerminal() {
        val path = currentPath
        if (path.isLinuxPath) {
            Terminal.open(path.toFile().path, fragment.requireContext())
        } else {
            // TODO
        }
    }

    fun addBookmark() {
        addBookmark(currentPath)
    }

    fun addBookmark(path: Path) {
        BookmarkDirectories.add(BookmarkDirectory(null, path))
        fragment.showToast(R.string.file_add_bookmark_success)
    }

    fun createShortcut() {
        createShortcut(currentPath, MimeType.DIRECTORY)
    }

    fun createShortcut(file: FileItem) {
        createShortcut(file.path, file.mimeType)
    }

    private fun createShortcut(path: Path, mimeType: MimeType) {
        val context = fragment.requireContext()
        val isDirectory = mimeType == MimeType.DIRECTORY
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path.toString())
            .setShortLabel(path.name)
            .setIntent(
                if (isDirectory) {
                    FileListActivity.createViewIntent(path)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                } else {
                    OpenFileActivity.createIntent(path, mimeType)
                }
            )
            .setIcon(
                IconCompat.createWithResource(
                    context,
                    if (isDirectory) {
                        R.mipmap.directory_shortcut_icon
                    } else {
                        R.mipmap.file_shortcut_icon
                    }
                )
            )
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            fragment.showToast(R.string.shortcut_created)
        }
    }

    fun showPropertiesDialog(file: FileItem) {
        FilePropertiesDialogFragment.show(file, fragment)
    }

    fun showCreateFileDialog() {
        CreateFileDialogFragment.show(fragment)
    }

    fun createFile(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, false, fragment.requireContext())
    }

    fun showCreateDirectoryDialog() {
        CreateDirectoryDialogFragment.show(fragment)
    }

    fun createDirectory(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, true, fragment.requireContext())
    }

    companion object {
        // Well under the 1 MB binder limit even after the URIs are serialized into the intent
        // and the intent is copied on its way to the viewer.
        private const val VIEWER_ACTIVITY_PATH_URI_LENGTH_MAX = 64 * 1024
    }
}
