/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.setGroupDividerEnabledCompat
import me.zhanghai.android.files.filelist.FileSortOptions.By
import me.zhanghai.android.files.filelist.FileSortOptions.Order
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.FixQueryChangeSearchView
import me.zhanghai.android.files.util.DebouncedRunnable
import me.zhanghai.android.files.util.valueCompat

/** The toolbar menu of [FileListFragment], including its search view. */
internal class FileListMenu(private val fragment: FileListFragment) {
    private lateinit var menuBinding: FileListMenuBinding

    private val viewModel: FileListViewModel
        get() = fragment.viewModel

    private val debouncedSearchRunnable = DebouncedRunnable(Handler(Looper.getMainLooper()), 1000) {
        if (!fragment.isResumed || !viewModel.isSearchViewExpanded) {
            return@DebouncedRunnable
        }
        val query = viewModel.searchViewQuery
        if (query.isEmpty()) {
            return@DebouncedRunnable
        }
        viewModel.search(query)
    }

    fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuBinding = FileListMenuBinding.inflate(menu, menuInflater)
        menuBinding.viewSortItem.subMenu!!.setGroupDividerEnabledCompat(true)
        setUpSearchView()
    }

    private fun setUpSearchView() {
        val searchView = menuBinding.searchItem.actionView as FixQueryChangeSearchView
        // MenuItem.OnActionExpandListener.onMenuItemActionExpand() is called before SearchView
        // resets the query.
        searchView.setOnSearchClickListener {
            viewModel.isSearchViewExpanded = true
            searchView.setQuery(viewModel.searchViewQuery, false)
            debouncedSearchRunnable()
        }
        // SearchView.OnCloseListener.onClose() is not always called.
        menuBinding.searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.isSearchViewExpanded = false
                viewModel.stopSearching()
                return true
            }
        })
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                debouncedSearchRunnable.cancel()
                viewModel.search(query)
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                if (searchView.shouldIgnoreQueryChange) {
                    return false
                }
                viewModel.searchViewQuery = query
                debouncedSearchRunnable()
                return false
            }
        })
        if (viewModel.isSearchViewExpanded) {
            menuBinding.searchItem.expandActionView()
        }
    }

    fun collapseSearchView() {
        if (this::menuBinding.isInitialized && menuBinding.searchItem.isActionViewExpanded) {
            menuBinding.searchItem.collapseActionView()
        }
    }

    fun onPrepareMenu() {
        updateViewSortMenuItems()
        updateSelectAllMenuItem()
        updateShowHiddenFilesMenuItem()
    }

    fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        android.R.id.home -> {
            fragment.binding.drawerLayout?.openDrawer(GravityCompat.START)
            if (fragment.binding.persistentDrawerLayout != null) {
                Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(
                    !Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
                )
            }
            true
        }

        R.id.action_view_list -> {
            viewModel.viewType = FileViewType.LIST
            true
        }

        R.id.action_view_grid -> {
            viewModel.viewType = FileViewType.GRID
            true
        }

        R.id.action_sort_by_name -> {
            viewModel.setSortBy(By.NAME)
            true
        }

        R.id.action_sort_by_type -> {
            viewModel.setSortBy(By.TYPE)
            true
        }

        R.id.action_sort_by_size -> {
            viewModel.setSortBy(By.SIZE)
            true
        }

        R.id.action_sort_by_last_modified -> {
            viewModel.setSortBy(By.LAST_MODIFIED)
            true
        }

        R.id.action_sort_order_ascending -> {
            viewModel.setSortOrder(
                if (!menuBinding.sortOrderAscendingItem.isChecked) {
                    Order.ASCENDING
                } else {
                    Order.DESCENDING
                }
            )
            true
        }

        R.id.action_sort_directories_first -> {
            viewModel.setSortDirectoriesFirst(!menuBinding.sortDirectoriesFirstItem.isChecked)
            true
        }

        R.id.action_view_sort_path_specific -> {
            viewModel.isViewSortPathSpecific = !menuBinding.viewSortPathSpecificItem.isChecked
            true
        }

        R.id.action_new_task -> {
            fragment.navigation.newTask()
            true
        }

        R.id.action_navigate_up -> {
            fragment.navigation.navigateUp()
            true
        }

        R.id.action_navigate_to -> {
            fragment.navigation.showNavigateToPathDialog()
            true
        }

        R.id.action_refresh -> {
            fragment.content.refresh()
            true
        }

        R.id.action_select_all -> {
            fragment.actionModes.selectAllFiles()
            true
        }

        R.id.action_show_hidden_files -> {
            setShowHiddenFiles(!menuBinding.showHiddenFilesItem.isChecked)
            true
        }

        R.id.action_share -> {
            fragment.fileActions.share()
            true
        }

        R.id.action_copy_path -> {
            fragment.fileActions.copyPath()
            true
        }

        R.id.action_open_in_terminal -> {
            fragment.fileActions.openInTerminal()
            true
        }

        R.id.action_add_bookmark -> {
            fragment.fileActions.addBookmark()
            true
        }

        R.id.action_create_shortcut -> {
            fragment.fileActions.createShortcut()
            true
        }

        else -> false
    }

    fun updateViewSortMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val searchViewExpanded = viewModel.isSearchViewExpanded
        menuBinding.viewSortItem.isVisible = !searchViewExpanded
        if (searchViewExpanded) {
            return
        }
        val viewType = viewModel.viewType
        val checkedViewTypeItem = when (viewType) {
            FileViewType.LIST -> menuBinding.viewListItem
            FileViewType.GRID -> menuBinding.viewGridItem
        }
        checkedViewTypeItem.isChecked = true
        val sortOptions = viewModel.sortOptions
        val checkedSortByItem = when (sortOptions.by) {
            By.NAME -> menuBinding.sortByNameItem
            By.TYPE -> menuBinding.sortByTypeItem
            By.SIZE -> menuBinding.sortBySizeItem
            By.LAST_MODIFIED -> menuBinding.sortByLastModifiedItem
        }
        checkedSortByItem.isChecked = true
        menuBinding.sortOrderAscendingItem.isChecked = sortOptions.order == Order.ASCENDING
        menuBinding.sortDirectoriesFirstItem.isChecked = sortOptions.isDirectoriesFirst
        menuBinding.viewSortPathSpecificItem.isChecked = viewModel.isViewSortPathSpecific
    }

    fun updateSelectAllMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val pickOptions = viewModel.pickOptions
        menuBinding.selectAllItem.isVisible = pickOptions == null || pickOptions.allowMultiple
    }

    private fun setShowHiddenFiles(showHiddenFiles: Boolean) {
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(showHiddenFiles)
    }

    fun updateShowHiddenFilesMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        menuBinding.showHiddenFilesItem.isChecked = showHiddenFiles
    }
}
