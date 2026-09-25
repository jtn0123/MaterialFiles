/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.text.TextUtils
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.isAuthenticationFailure
import me.zhanghai.android.files.provider.sftp.client.hostKeyChange
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.SftpHostKeyChangedDialogFragment
import me.zhanghai.android.files.storage.findStoredServer
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.getDimensionDp
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.showActionSnackbar
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.toUserMessage
import me.zhanghai.android.files.util.valueCompat

/** Feeds the file list, its loading state and its view type into the views of [FileListFragment]. */
internal class FileListContent(private val fragment: FileListFragment) {
    private val viewModel: FileListViewModel
        get() = fragment.viewModel

    private val binding: FileListBinding
        get() = fragment.binding

    private val adapter: FileListAdapter
        get() = fragment.adapter

    private var adapterFileListUpdateGeneration = 0

    private var lastShownError: Throwable? = null

    private var errorSnackbar: Snackbar? = null

    /** Whether we sent the user to fix the server that turned us away, and should retry after. */
    private var isEditingServer = false

    fun onViewCreated() {
        binding.retryButton.setOnClickListener { refresh() }
    }

    fun onResume() {
        if (isEditingServer) {
            isEditingServer = false
            refresh()
        }
    }

    fun refresh() {
        viewModel.reload()
    }

    fun onFileListChanged(stateful: Stateful<List<FileItem>>) {
        val files = stateful.value
        val isSearching = viewModel.searchState.isSearching
        updateSubtitle(stateful, isSearching)
        val visibility = FileListStateVisibility.of(stateful, isSearching)
        binding.swipeRefreshLayout.isRefreshing = visibility.isRefreshing
        binding.progress.fadeToVisibilityUnsafe(visibility.isProgressVisible)
        binding.errorLayout.fadeToVisibilityUnsafe(visibility.isErrorVisible)
        if (stateful is Failure) {
            showError(stateful.throwable, visibility.hasFiles)
        } else {
            // A reload is under way or done, so the old error no longer applies.
            errorSnackbar?.dismiss()
            errorSnackbar = null
        }
        binding.emptyView.fadeToVisibilityUnsafe(visibility.isEmptyVisible)
        if (files != null) {
            updateAdapterFileList(restorePendingState = stateful is Success)
        } else {
            // This resets animation as well.
            adapter.clear()
            ++adapterFileListUpdateGeneration
        }
    }

    private fun updateSubtitle(stateful: Stateful<List<FileItem>>, isSearching: Boolean) {
        val files = stateful.value
        when {
            stateful is Failure -> binding.toolbar.setSubtitle(R.string.error)

            stateful is Loading && !isSearching -> binding.toolbar.setSubtitle(R.string.loading)

            // A search always carries its (possibly empty) results, and so does a success.
            files != null -> binding.toolbar.subtitle = getSubtitle(files)
        }
    }

    private fun showError(throwable: Throwable, hasFiles: Boolean) {
        // The live data hands the same failure out again whenever the view is recreated or another
        // observer attaches, and the user has already been told about it.
        val isNewError = throwable !== lastShownError
        lastShownError = throwable
        if (isNewError) {
            throwable.logWarning("FileListContent", "list(${viewModel.currentPath})")
        }
        val error = throwable.toUserMessage(fragment.requireContext())
        if (hasFiles) {
            // What was listed stays useful, so the error goes where it doesn't cover it.
            if (isNewError) {
                errorSnackbar = binding.contentLayout.showActionSnackbar(
                    error,
                    R.string.retry,
                    binding.speedDialView
                ) { refresh() }
            }
        } else {
            binding.errorText.text = error
            // Only the server's edit screen can fix a password it turned away.
            val server = if (throwable.isAuthenticationFailure) {
                findStoredServer(viewModel.currentPath)
            } else {
                null
            }
            binding.editServerButton.isVisible = server != null
            binding.editServerButton.setOnClickListener {
                server ?: return@setOnClickListener
                isEditingServer = true
                fragment.startActivitySafe(server.createEditIntent())
            }
        }
        val hostKeyChange = throwable.hostKeyChange
        if (hostKeyChange != null && !SftpHostKeyChangedDialogFragment.isShowing(fragment)) {
            SftpHostKeyChangedDialogFragment.show(hostKeyChange, fragment)
        }
    }

    private fun getSubtitle(files: List<FileItem>): String {
        val directoryCount = files.count { it.attributes.isDirectory }
        val fileCount = files.size - directoryCount
        val directoryCountText = if (directoryCount > 0) {
            fragment.getQuantityString(
                R.plurals.file_list_subtitle_directory_count_format,
                directoryCount,
                directoryCount
            )
        } else {
            null
        }
        val fileCountText = if (fileCount > 0) {
            fragment.getQuantityString(
                R.plurals.file_list_subtitle_file_count_format,
                fileCount,
                fileCount
            )
        } else {
            null
        }
        return when {
            !directoryCountText.isNullOrEmpty() && !fileCountText.isNullOrEmpty() ->
                (
                    directoryCountText +
                        fragment.getString(R.string.file_list_subtitle_separator) +
                        fileCountText
                    )

            !directoryCountText.isNullOrEmpty() -> directoryCountText

            !fileCountText.isNullOrEmpty() -> fileCountText

            else -> fragment.getString(R.string.empty)
        }
    }

    fun onViewTypeChanged(viewType: FileViewType) {
        updateSpanCount()
        adapter.viewType = viewType
        updateAdapterFileList()
        fragment.menus.updateViewSortMenuItems()
    }

    fun updateSpanCount() {
        fragment.layoutManager.spanCount = when (viewModel.viewType) {
            FileViewType.LIST -> 1

            FileViewType.GRID -> {
                var widthDp = fragment.resources.configuration.screenWidthDp
                val persistentDrawerLayout = binding.persistentDrawerLayout
                if (persistentDrawerLayout != null &&
                    persistentDrawerLayout.isDrawerOpen(GravityCompat.START)
                ) {
                    widthDp -= fragment.getDimensionDp(R.dimen.navigation_max_width).roundToInt()
                }
                (widthDp / 180).coerceAtLeast(2)
            }
        }
    }

    fun onSortOptionsChanged(sortOptions: FileSortOptions) {
        adapter.sortOptions = sortOptions
        updateAdapterFileList()
        fragment.menus.updateViewSortMenuItems()
    }

    fun onShowHiddenFilesChanged() {
        updateAdapterFileList()
        fragment.menus.updateShowHiddenFilesMenuItem()
    }

    private fun updateAdapterFileList(restorePendingState: Boolean = false) {
        // The sort options arrive before the first file list does.
        val files = viewModel.fileListLiveData.value?.value ?: return
        val isSearching = viewModel.searchState.isSearching
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        val sortOptions = viewModel.sortOptions
        val generation = ++adapterFileListUpdateGeneration
        // Filtering and sorting a large directory takes long enough to drop frames, so do it off
        // the main thread and only apply the newest result.
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val visibleFiles = withContext(Dispatchers.Default) {
                val visibleFiles = if (showHiddenFiles) files else files.filterNot { it.isHidden }
                if (isSearching) {
                    visibleFiles
                } else {
                    visibleFiles.sortedWith(sortOptions.createComparator())
                }
            }
            if (generation != adapterFileListUpdateGeneration) {
                return@launch
            }
            val wasAtTop = !binding.recyclerView.canScrollVertically(-1)
            adapter.replaceListAndIsSearching(visibleFiles, isSearching) {
                if (generation == adapterFileListUpdateGeneration) {
                    restoreScrollPosition(restorePendingState, wasAtTop)
                }
            }
        }
    }

    private fun restoreScrollPosition(restorePendingState: Boolean, wasAtTop: Boolean) {
        val pendingState = if (restorePendingState) viewModel.pendingState else null
        if (pendingState != null) {
            fragment.layoutManager.onRestoreInstanceState(pendingState)
        } else if (wasAtTop) {
            // Entries of a folder still loading arrive in batches, and the ones sorting first are
            // then inserted above the top row, where the list would otherwise keep them out of
            // sight.
            fragment.layoutManager.scrollToPosition(0)
        }
    }

    fun onFileNameEllipsizeChanged(fileNameEllipsize: TextUtils.TruncateAt) {
        adapter.nameEllipsize = fileNameEllipsize
    }
}
