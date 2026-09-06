/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import androidx.core.view.GravityCompat
import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

/** Moving [FileListFragment] between directories, and its navigation drawer. */
internal class FileListNavigation(private val fragment: FileListFragment) {
    private val viewModel: FileListViewModel
        get() = fragment.viewModel

    fun navigateTo(path: Path) {
        fragment.menus.collapseSearchView()
        val state = fragment.layoutManager.onSaveInstanceState()
        viewModel.navigateTo(state!!, path)
    }

    fun navigateUp() {
        fragment.menus.collapseSearchView()
        viewModel.navigateUp()
    }

    fun showNavigateToPathDialog() {
        NavigateToPathDialogFragment.show(viewModel.currentPath, fragment)
    }

    fun newTask() {
        openInNewTask(viewModel.currentPath)
    }

    fun openInNewTask(path: Path) {
        val intent = FileListActivity.createViewIntent(path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        fragment.startActivitySafe(intent)
    }

    fun navigateToRoot(path: Path) {
        fragment.menus.collapseSearchView()
        viewModel.resetTo(path)
    }

    fun navigateToDefaultRoot() {
        navigateToRoot(Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat)
    }

    fun closeNavigationDrawer() {
        fragment.binding.drawerLayout?.closeDrawer(GravityCompat.START)
    }

    fun onPersistentDrawerOpenChanged(open: Boolean) {
        fragment.binding.persistentDrawerLayout?.let {
            if (open) {
                it.openDrawer(GravityCompat.START)
            } else {
                it.closeDrawer(GravityCompat.START)
            }
        }
        fragment.content.updateSpanCount()
    }
}
