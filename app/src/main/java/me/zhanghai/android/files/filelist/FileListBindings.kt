/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.leinardi.android.speeddial.SpeedDialView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.FileListFragmentAppBarIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentBinding
import me.zhanghai.android.files.databinding.FileListFragmentBottomBarIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentContentIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentSpeedDialIncludeBinding
import me.zhanghai.android.files.ui.CoordinatorAppBarLayout
import me.zhanghai.android.files.ui.PersistentBarLayout
import me.zhanghai.android.files.ui.PersistentDrawerLayout

/** The views of [FileListFragment], gathered from its included layouts. */
internal class FileListBinding private constructor(
    val root: View,
    val drawerLayout: DrawerLayout? = null,
    val persistentDrawerLayout: PersistentDrawerLayout? = null,
    val persistentBarLayout: PersistentBarLayout,
    val appBarLayout: CoordinatorAppBarLayout,
    val toolbar: Toolbar,
    val overlayToolbar: Toolbar,
    val breadcrumbLayout: BreadcrumbLayout,
    val contentLayout: ViewGroup,
    val progress: ProgressBar,
    val errorText: TextView,
    val emptyView: View,
    val swipeRefreshLayout: SwipeRefreshLayout,
    val recyclerView: RecyclerView,
    val bottomBarLayout: ViewGroup,
    val bottomToolbar: Toolbar,
    val bottomCreateFileNameEdit: EditText,
    val speedDialView: SpeedDialView
) {
    companion object {
        fun inflate(
            inflater: LayoutInflater,
            root: ViewGroup?,
            attachToRoot: Boolean
        ): FileListBinding {
            val binding = FileListFragmentBinding.inflate(inflater, root, attachToRoot)
            val bindingRoot = binding.root
            val includeBinding = FileListFragmentIncludeBinding.bind(bindingRoot)
            val appBarBinding = FileListFragmentAppBarIncludeBinding.bind(bindingRoot)
            val contentBinding = FileListFragmentContentIncludeBinding.bind(bindingRoot)
            val bottomBarBinding = FileListFragmentBottomBarIncludeBinding.bind(bindingRoot)
            val speedDialBinding = FileListFragmentSpeedDialIncludeBinding.bind(bindingRoot)
            return FileListBinding(
                bindingRoot, includeBinding.drawerLayout, includeBinding.persistentDrawerLayout,
                includeBinding.persistentBarLayout, appBarBinding.appBarLayout,
                appBarBinding.toolbar, appBarBinding.overlayToolbar,
                appBarBinding.breadcrumbLayout, contentBinding.contentLayout,
                contentBinding.progress, contentBinding.errorText, contentBinding.emptyView,
                contentBinding.swipeRefreshLayout, contentBinding.recyclerView,
                bottomBarBinding.bottomBarLayout, bottomBarBinding.bottomToolbar,
                bottomBarBinding.bottomCreateFileNameEdit, speedDialBinding.speedDialView
            )
        }
    }
}

/** The items of the [FileListFragment] toolbar menu. */
internal class FileListMenuBinding private constructor(
    val menu: Menu,
    val searchItem: MenuItem,
    val viewSortItem: MenuItem,
    val viewListItem: MenuItem,
    val viewGridItem: MenuItem,
    val sortByNameItem: MenuItem,
    val sortByTypeItem: MenuItem,
    val sortBySizeItem: MenuItem,
    val sortByLastModifiedItem: MenuItem,
    val sortOrderAscendingItem: MenuItem,
    val sortDirectoriesFirstItem: MenuItem,
    val viewSortPathSpecificItem: MenuItem,
    val selectAllItem: MenuItem,
    val showHiddenFilesItem: MenuItem
) {
    companion object {
        fun inflate(menu: Menu, inflater: MenuInflater): FileListMenuBinding {
            inflater.inflate(R.menu.file_list, menu)
            return FileListMenuBinding(
                menu, menu.findItem(R.id.action_search), menu.findItem(R.id.action_view_sort),
                menu.findItem(R.id.action_view_list), menu.findItem(R.id.action_view_grid),
                menu.findItem(R.id.action_sort_by_name),
                menu.findItem(R.id.action_sort_by_type),
                menu.findItem(R.id.action_sort_by_size),
                menu.findItem(R.id.action_sort_by_last_modified),
                menu.findItem(R.id.action_sort_order_ascending),
                menu.findItem(R.id.action_sort_directories_first),
                menu.findItem(R.id.action_view_sort_path_specific),
                menu.findItem(R.id.action_select_all),
                menu.findItem(R.id.action_show_hidden_files)
            )
        }
    }
}
