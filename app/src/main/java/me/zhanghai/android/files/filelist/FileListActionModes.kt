/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MenuItem
import androidx.core.view.isVisible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.navigation.NavigationRootMapLiveData
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.ui.AppBarLayoutExpandHackListener
import me.zhanghai.android.files.ui.OverlayToolbarActionMode
import me.zhanghai.android.files.ui.PersistentBarLayoutToolbarActionMode
import me.zhanghai.android.files.ui.ToolbarActionMode
import me.zhanghai.android.files.util.asFileName
import me.zhanghai.android.files.util.asFileNameOrNull
import me.zhanghai.android.files.util.setOnEditorConfirmActionListener
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat

/**
 * The selection action mode on the overlay toolbar and the paste or pick action mode on the
 * bottom toolbar of [FileListFragment].
 */
internal class FileListActionModes(private val fragment: FileListFragment) {
    lateinit var overlayActionMode: ToolbarActionMode
        private set

    private lateinit var bottomActionMode: ToolbarActionMode

    private val viewModel: FileListViewModel
        get() = fragment.viewModel

    private val binding: FileListBinding
        get() = fragment.binding

    fun onViewCreated(binding: FileListBinding) {
        overlayActionMode = OverlayToolbarActionMode(binding.overlayToolbar)
        bottomActionMode = PersistentBarLayoutToolbarActionMode(
            binding.persistentBarLayout,
            binding.bottomBarLayout,
            binding.bottomToolbar
        )
    }

    fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (bottomActionMode.isActive) {
            val menu = bottomActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        if (overlayActionMode.isActive) {
            val menu = overlayActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        return false
    }

    fun onCurrentPathChanged() {
        updateOverlayToolbar()
        updateBottomToolbar()
    }

    fun onSelectedFilesChanged(files: FileItemSet) {
        updateOverlayToolbar()
        fragment.adapter.replaceSelectedFiles(files)
    }

    fun updateOverlayToolbar() {
        val files = viewModel.selectedFiles
        if (files.isEmpty()) {
            if (overlayActionMode.isActive) {
                overlayActionMode.finish()
            }
            return
        }
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            overlayActionMode.title =
                fragment.getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_pick)
            val menu = overlayActionMode.menu
            val isOpen = when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE, PickOptions.Mode.OPEN_DIRECTORY -> true
                PickOptions.Mode.CREATE_FILE -> false
            }
            menu.findItem(R.id.action_open).isVisible = isOpen
            menu.findItem(R.id.action_create).isVisible = !isOpen
            menu.findItem(R.id.action_select_all).isVisible = pickOptions.allowMultiple
        } else {
            overlayActionMode.title =
                fragment.getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_select)
            val menu = overlayActionMode.menu
            val isAnyFileReadOnly = files.any { it.path.fileSystem.isReadOnly }
            menu.findItem(R.id.action_cut).isVisible = !isAnyFileReadOnly
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            menu.findItem(R.id.action_copy)
                .setIcon(
                    if (areAllFilesArchivePaths) {
                        R.drawable.extract_icon_control_normal_24dp
                    } else {
                        R.drawable.copy_icon_control_normal_24dp
                    }
                )
                .setTitle(
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_select_action_extract
                    } else {
                        R.string.copy
                    }
                )
            menu.findItem(R.id.action_delete).isVisible = !isAnyFileReadOnly
            val areAllFilesArchiveFiles = files.all { it.isArchiveFile }
            menu.findItem(R.id.action_extract).isVisible = areAllFilesArchiveFiles
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            menu.findItem(R.id.action_archive).isVisible = !isCurrentPathReadOnly
        }
        if (!overlayActionMode.isActive) {
            binding.appBarLayout.setExpanded(true)
            binding.appBarLayout.addOnOffsetChangedListener(
                AppBarLayoutExpandHackListener(binding.recyclerView)
            )
            overlayActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onOverlayActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onOverlayActionModeFinished()
                }
            })
        }
    }

    private fun onOverlayActionModeMenuItemClicked(item: MenuItem): Boolean {
        val fileActions = fragment.fileActions
        return when (item.itemId) {
            R.id.action_open -> {
                fragment.pick.pickFiles(viewModel.selectedFiles)
                true
            }

            R.id.action_create -> {
                fragment.pick.confirmReplaceFile(viewModel.selectedFiles.single())
                true
            }

            R.id.action_cut -> {
                fileActions.cutFiles(viewModel.selectedFiles)
                true
            }

            R.id.action_copy -> {
                fileActions.copyFiles(viewModel.selectedFiles)
                true
            }

            R.id.action_delete -> {
                fileActions.confirmDeleteFiles(viewModel.selectedFiles)
                true
            }

            R.id.action_extract -> {
                fileActions.extractFiles(viewModel.selectedFiles)
                true
            }

            R.id.action_archive -> {
                fileActions.showCreateArchiveDialog(viewModel.selectedFiles)
                true
            }

            R.id.action_share -> {
                fileActions.shareFiles(viewModel.selectedFiles)
                true
            }

            R.id.action_select_all -> {
                selectAllFiles()
                true
            }

            else -> false
        }
    }

    private fun onOverlayActionModeFinished() {
        viewModel.clearSelectedFiles()
    }

    fun selectAllFiles() {
        fragment.adapter.selectAllFiles()
    }

    fun onPasteStateChanged() {
        updateBottomToolbar()
    }

    fun updateBottomToolbar() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            bottomActionMode.setMenuResource(R.menu.file_list_pick_bottom)
            val menu = bottomActionMode.menu
            when (pickOptions.mode) {
                PickOptions.Mode.CREATE_FILE -> {
                    bottomActionMode.title = null
                    binding.bottomCreateFileNameEdit.isVisible = true
                    val createMenuItem = menu.findItem(R.id.action_create)
                    binding.bottomCreateFileNameEdit.setOnEditorConfirmActionListener {
                        onBottomActionModeMenuItemClicked(createMenuItem)
                    }
                    if (!viewModel.isCreateFileNameEditInitialized) {
                        val fileName = pickOptions.fileName!!
                        binding.bottomCreateFileNameEdit.setText(fileName)
                        binding.bottomCreateFileNameEdit.setSelection(
                            0,
                            fileName.asFileName().baseName.length
                        )
                        binding.bottomCreateFileNameEdit.requestFocus()
                        viewModel.isCreateFileNameEditInitialized = true
                    }
                    menu.findItem(R.id.action_open).isVisible = false
                    createMenuItem.isVisible = true
                }

                PickOptions.Mode.OPEN_DIRECTORY -> {
                    val path = viewModel.currentPath
                    val navigationRoot = NavigationRootMapLiveData.valueCompat[path]
                    val name = navigationRoot?.getName(fragment.requireContext()) ?: path.name
                    bottomActionMode.title =
                        fragment.getString(R.string.file_list_open_current_directory_format, name)
                    binding.bottomCreateFileNameEdit.isVisible = false
                    menu.findItem(R.id.action_open).isVisible = true
                    menu.findItem(R.id.action_create).isVisible = false
                }

                else -> {
                    if (bottomActionMode.isActive) {
                        bottomActionMode.finish()
                    }
                    return
                }
            }
        } else {
            val pasteState = viewModel.pasteState
            val files = pasteState.files
            if (files.isEmpty()) {
                if (bottomActionMode.isActive) {
                    bottomActionMode.finish()
                }
                return
            }
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            bottomActionMode.title = fragment.getString(
                if (pasteState.copy) {
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_paste_extract_title_format
                    } else {
                        R.string.file_list_paste_copy_title_format
                    }
                } else {
                    R.string.file_list_paste_move_title_format
                },
                files.size
            )
            binding.bottomCreateFileNameEdit.isVisible = false
            bottomActionMode.setMenuResource(R.menu.file_list_paste)
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            bottomActionMode.menu.findItem(R.id.action_paste)
                .setTitle(
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_paste_action_extract_here
                    } else {
                        R.string.paste
                    }
                )
                .isEnabled = !isCurrentPathReadOnly
        }
        if (!bottomActionMode.isActive) {
            bottomActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarNavigationIconClicked(toolbarActionMode: ToolbarActionMode) {
                    onBottomToolbarNavigationIconClicked()
                }

                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onBottomActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onBottomActionModeFinished()
                }
            })
        }
    }

    private fun onBottomToolbarNavigationIconClicked() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            fragment.requireActivity().finish()
        } else {
            bottomActionMode.finish()
        }
    }

    private fun onBottomActionModeMenuItemClicked(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> {
            fragment.pick.pickPaths(linkedSetOf(viewModel.currentPath))
            true
        }

        R.id.action_create -> {
            val fileName = binding.bottomCreateFileNameEdit.text.toString()
            if (fileName.isEmpty()) {
                fragment.showToast(R.string.file_list_create_file_name_error_empty)
            } else if (fileName.asFileNameOrNull() == null) {
                fragment.showToast(R.string.file_list_create_file_name_error_invalid)
            } else {
                val file = fragment.fileActions.getFileWithName(fileName)
                if (file != null) {
                    fragment.pick.confirmReplaceFile(file, false)
                } else {
                    val path = viewModel.currentPath.resolve(fileName)
                    fragment.pick.pickPaths(linkedSetOf(path))
                }
            }
            true
        }

        R.id.action_paste -> {
            fragment.fileActions.pasteFiles(viewModel.currentPath)
            true
        }

        else -> false
    }

    private fun onBottomActionModeFinished() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions == null) {
            viewModel.clearPasteState()
        }
    }
}
