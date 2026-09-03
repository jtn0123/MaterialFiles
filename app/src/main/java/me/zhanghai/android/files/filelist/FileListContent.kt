/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.text.TextUtils
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.getDimensionDp
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.showToast
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

    fun refresh() {
        viewModel.reload()
    }

    fun onFileListChanged(stateful: Stateful<List<FileItem>>) {
        val files = stateful.value
        val isSearching = viewModel.searchState.isSearching
        when {
            stateful is Failure -> binding.toolbar.setSubtitle(R.string.error)
            stateful is Loading && !isSearching -> binding.toolbar.setSubtitle(R.string.loading)
            else -> binding.toolbar.subtitle = getSubtitle(files!!)
        }
        val hasFiles = !files.isNullOrEmpty()
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading && (hasFiles || isSearching)
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && !(hasFiles || isSearching))
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && !hasFiles)
        val throwable = (stateful as? Failure)?.throwable
        if (throwable != null) {
            throwable.printStackTrace()
            val error = throwable.toUserMessage(fragment.requireContext())
            if (hasFiles) {
                fragment.showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        binding.emptyView.fadeToVisibilityUnsafe(stateful is Success && !hasFiles)
        if (files != null) {
            updateAdapterFileList(restorePendingState = stateful is Success)
        } else {
            // This resets animation as well.
            adapter.clear()
            ++adapterFileListUpdateGeneration
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
            adapter.replaceListAndIsSearching(visibleFiles, isSearching)
            if (restorePendingState) {
                viewModel.pendingState?.let { fragment.layoutManager.onRestoreInstanceState(it) }
            }
        }
    }

    fun onFileNameEllipsizeChanged(fileNameEllipsize: TextUtils.TruncateAt) {
        adapter.nameEllipsize = fileNameEllipsize
    }
}
