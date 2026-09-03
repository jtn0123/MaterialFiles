/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.DrawerLayoutOnBackPressedCallback
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.ui.SpeedDialViewOnBackPressedCallback
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.autoCleared
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.hasSw600Dp
import me.zhanghai.android.files.util.isOrientationLandscape
import me.zhanghai.android.files.util.viewModels

class FileListFragment :
    Fragment(),
    MenuProvider,
    BreadcrumbLayout.Listener,
    FileListAdapter.Listener,
    ConfirmReplaceFileDialogFragment.Listener,
    OpenApkDialogFragment.Listener,
    ConfirmDeleteFilesDialogFragment.Listener,
    CreateArchiveDialogFragment.Listener,
    RenameFileDialogFragment.Listener,
    CreateFileDialogFragment.Listener,
    CreateDirectoryDialogFragment.Listener,
    NavigateToPathDialogFragment.Listener,
    NavigationFragment.Listener,
    ShowRequestAllFilesAccessRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionInSettingsRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionInSettingsRationaleDialogFragment.Listener {
    // Registers its activity result launchers, so it must be created with the fragment.
    private val permissions = FileListPermissions(this)

    internal val pick = FileListPick(this)

    internal val menus = FileListMenu(this)

    internal val content = FileListContent(this)

    internal val navigation = FileListNavigation(this)

    internal val actionModes = FileListActionModes(this)

    internal val fileActions = FileListFileActions(this)

    private val args by args<Args>()
    private val argsPath by lazy { args.intent.extraPath }

    internal val viewModel by viewModels { { FileListViewModel() } }

    internal var binding by autoCleared<FileListBinding>()
        private set

    private lateinit var navigationFragment: NavigationFragment

    internal lateinit var layoutManager: GridLayoutManager
        private set

    internal lateinit var adapter: FileListAdapter
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FileListBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().addMenuProvider(this, viewLifecycleOwner)
        if (savedInstanceState == null) {
            navigationFragment = NavigationFragment()
            childFragmentManager.commit { add(R.id.navigationFragment, navigationFragment) }
        } else {
            navigationFragment = childFragmentManager.findFragmentById(R.id.navigationFragment)
                as NavigationFragment
        }
        navigationFragment.listener = this
        val activity = requireActivity() as AppCompatActivity
        activity.setTitle(R.string.file_list_title)
        activity.setSupportActionBar(binding.toolbar)
        actionModes.onViewCreated(binding)
        val contentLayoutInitialPaddingBottom = binding.contentLayout.paddingBottom
        binding.appBarLayout.addOnOffsetChangedListener { _, verticalOffset ->
            binding.contentLayout.updatePaddingRelative(
                bottom = contentLayoutInitialPaddingBottom +
                    binding.appBarLayout.totalScrollRange + verticalOffset
            )
        }
        binding.appBarLayout.syncBackgroundColorTo(binding.overlayToolbar)
        binding.breadcrumbLayout.setListener(this)
        if (!(activity.hasSw600Dp && activity.isOrientationLandscape)) {
            binding.swipeRefreshLayout.setProgressViewEndTarget(
                true,
                binding.swipeRefreshLayout.progressViewEndOffset
            )
        }
        binding.swipeRefreshLayout.setOnRefreshListener { content.refresh() }
        layoutManager = GridLayoutManager(activity, 1)
        binding.recyclerView.layoutManager = layoutManager
        adapter = FileListAdapter(this)
        binding.recyclerView.adapter = adapter
        val fastScroller = ThemedFastScroller.create(binding.recyclerView)
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.recyclerView,
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView, fastScroller)
        )
        binding.speedDialView.inflate(R.menu.file_list_speed_dial)
        binding.speedDialView.setOnActionSelectedListener {
            when (it.id) {
                R.id.action_create_file -> fileActions.showCreateFileDialog()
                R.id.action_create_directory -> fileActions.showCreateDirectoryDialog()
            }
            // Returning false causes the speed dial to close without animation.
            //return false
            binding.speedDialView.close()
            true
        }

        val viewLifecycleOwner = viewLifecycleOwner
        addOnBackPressedCallback(
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    viewModel.navigateUp()
                }
            }
                .also { callback ->
                    viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) {
                        callback.isEnabled = viewModel.canNavigateUpBreadcrumb
                    }
                }
        )
        addOnBackPressedCallback(actionModes.overlayActionMode.onBackPressedCallback)
        addOnBackPressedCallback(SpeedDialViewOnBackPressedCallback(binding.speedDialView))
        binding.drawerLayout?.let {
            addOnBackPressedCallback(DrawerLayoutOnBackPressedCallback(it))
        }

        if (!viewModel.hasTrail) {
            pick.resetTrailFromIntent(args.intent, argsPath)
        }
        viewModel.currentPathLiveData.observe(viewLifecycleOwner) {
            actionModes.onCurrentPathChanged()
        }
        viewModel.searchViewExpandedLiveData.observe(viewLifecycleOwner) {
            menus.updateViewSortMenuItems()
        }
        viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) {
            binding.breadcrumbLayout.setData(it)
        }
        viewModel.viewTypeLiveData.observe(viewLifecycleOwner) { content.onViewTypeChanged(it) }
        // Live data only calls observeForever() on its sources when it is active, so we have to
        // make view type live data active first (so that it can load its initial value) before we
        // register another observer that needs to get the view type.
        if (binding.persistentDrawerLayout != null) {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.observe(viewLifecycleOwner) {
                navigation.onPersistentDrawerOpenChanged(it)
            }
        }
        viewModel.sortOptionsLiveData.observe(viewLifecycleOwner) {
            content.onSortOptionsChanged(it)
        }
        viewModel.viewSortPathSpecificLiveData.observe(viewLifecycleOwner) {
            menus.updateViewSortMenuItems()
        }
        viewModel.pickOptionsLiveData.observe(viewLifecycleOwner) { pick.onPickOptionsChanged(it) }
        viewModel.selectedFilesLiveData.observe(viewLifecycleOwner) {
            actionModes.onSelectedFilesChanged(it)
        }
        viewModel.pasteStateLiveData.observe(viewLifecycleOwner) {
            actionModes.onPasteStateChanged()
        }
        Settings.FILE_NAME_ELLIPSIZE.observe(viewLifecycleOwner) {
            content.onFileNameEllipsizeChanged(it)
        }
        viewModel.fileListLiveData.observe(viewLifecycleOwner) { content.onFileListChanged(it) }
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.observe(viewLifecycleOwner) {
            content.onShowHiddenFilesChanged()
        }
    }

    override fun onResume() {
        super.onResume()

        permissions.onResume()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menus.onCreateMenu(menu, menuInflater)
    }

    override fun onPrepareMenu(menu: Menu) {
        menus.onPrepareMenu()
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
        menus.onMenuItemSelected(menuItem)

    fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean =
        actionModes.onKeyShortcut(keyCode, event)

    override fun navigateTo(path: Path) {
        navigation.navigateTo(path)
    }

    override fun copyPath(path: Path) {
        fileActions.copyPath(path)
    }

    override fun openInNewTask(path: Path) {
        navigation.openInNewTask(path)
    }

    override fun replaceFile(file: FileItem) {
        pick.replaceFile(file)
    }

    override fun deleteFiles(files: FileItemSet) {
        fileActions.deleteFiles(files)
    }

    override fun archive(
        files: FileItemSet,
        name: String,
        format: Int,
        filter: Int,
        password: String?
    ) {
        fileActions.archive(files, name, format, filter, password)
    }

    override fun clearSelectedFiles() {
        viewModel.clearSelectedFiles()
    }

    override fun selectFile(file: FileItem, selected: Boolean) {
        viewModel.selectFile(file, selected)
    }

    override fun selectFiles(files: FileItemSet, selected: Boolean) {
        viewModel.selectFiles(files, selected)
    }

    override fun openFile(file: FileItem) {
        fileActions.openFile(file)
    }

    override fun installApk(file: FileItem) {
        fileActions.installApk(file)
    }

    override fun viewApk(file: FileItem) {
        fileActions.viewApk(file)
    }

    override fun openFileWith(file: FileItem) {
        fileActions.openFileWith(file)
    }

    override fun cutFile(file: FileItem) {
        fileActions.cutFiles(fileItemSetOf(file))
    }

    override fun copyFile(file: FileItem) {
        fileActions.copyFiles(fileItemSetOf(file))
    }

    override fun confirmDeleteFile(file: FileItem) {
        fileActions.confirmDeleteFiles(fileItemSetOf(file))
    }

    override fun showRenameFileDialog(file: FileItem) {
        fileActions.showRenameFileDialog(file)
    }

    override fun hasFileWithName(name: String): Boolean = fileActions.hasFileWithName(name)

    override fun renameFile(file: FileItem, newName: String) {
        fileActions.renameFile(file, newName)
    }

    override fun extractFile(file: FileItem) {
        copyFile(file.createDummyArchiveRoot())
    }

    override fun showCreateArchiveDialog(file: FileItem) {
        fileActions.showCreateArchiveDialog(fileItemSetOf(file))
    }

    override fun shareFile(file: FileItem) {
        fileActions.shareFile(file)
    }

    override fun copyPath(file: FileItem) {
        fileActions.copyPath(file.path)
    }

    override fun addBookmark(file: FileItem) {
        fileActions.addBookmark(file.path)
    }

    override fun createShortcut(file: FileItem) {
        fileActions.createShortcut(file)
    }

    override fun showPropertiesDialog(file: FileItem) {
        fileActions.showPropertiesDialog(file)
    }

    override fun createFile(name: String) {
        fileActions.createFile(name)
    }

    override fun createDirectory(name: String) {
        fileActions.createDirectory(name)
    }

    override val currentPath: Path
        get() = viewModel.currentPath

    override fun navigateToRoot(path: Path) {
        navigation.navigateToRoot(path)
    }

    override fun navigateToDefaultRoot() {
        navigation.navigateToDefaultRoot()
    }

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {
        viewModel.currentPathLiveData.observe(owner, observer)
    }

    override fun closeNavigationDrawer() {
        navigation.closeNavigationDrawer()
    }

    override fun onShowRequestAllFilesAccessRationaleResult(shouldRequest: Boolean) {
        permissions.onShowRequestAllFilesAccessRationaleResult(shouldRequest)
    }

    override fun onShowRequestStoragePermissionRationaleResult(shouldRequest: Boolean) {
        permissions.onShowRequestStoragePermissionRationaleResult(shouldRequest)
    }

    override fun onShowRequestStoragePermissionInSettingsRationaleResult(shouldRequest: Boolean) {
        permissions.onShowRequestStoragePermissionInSettingsRationaleResult(shouldRequest)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionRationaleResult(shouldRequest: Boolean) {
        permissions.onShowRequestNotificationPermissionRationaleResult(shouldRequest)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionInSettingsRationaleResult(
        shouldRequest: Boolean
    ) {
        permissions.onShowRequestNotificationPermissionInSettingsRationaleResult(shouldRequest)
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs
}
