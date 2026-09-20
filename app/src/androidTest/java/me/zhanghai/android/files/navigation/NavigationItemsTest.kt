/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.about.AboutActivity
import me.zhanghai.android.files.file.ExternalStorageUri
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.settings.StandardDirectoryListActivity
import me.zhanghai.android.files.storage.AddStorageDialogActivity
import me.zhanghai.android.files.storage.ExternalStorageShortcut
import me.zhanghai.android.files.storage.FileSystemRoot
import me.zhanghai.android.files.storage.PrimaryStorageVolume
import me.zhanghai.android.files.storage.Storage
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The navigation drawer content: which items are built from the settings, and what each of them
 * does when clicked.
 */
@RunWith(AndroidJUnit4::class)
class NavigationItemsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var savedStorages: List<Storage>
    private lateinit var savedBookmarkDirectories: List<BookmarkDirectory>

    @Before
    fun setUp() {
        savedStorages = Settings.STORAGES.valueCompat
        savedBookmarkDirectories = Settings.BOOKMARK_DIRECTORIES.valueCompat
    }

    @After
    fun tearDown() {
        putStorages(savedStorages)
        putBookmarkDirectories(savedBookmarkDirectories)
    }

    @Test
    fun aVisibleStorageBecomesARootItemAndAHiddenOneIsSkipped() {
        val visible = FileSystemRoot("Visible root", true)
        val hidden = PrimaryStorageVolume("Hidden volume", false)
        putStorages(listOf(visible, hidden))

        val items = currentItems()

        val storageItems = items.filterNotNull().filter { it.id == visible.id }
        assertEquals(1, storageItems.size)
        val item = storageItems.single()
        assertEquals("Visible root", item.getTitle(context))
        assertNotNull(item.getIcon(context))
        assertTrue(items.filterNotNull().none { it.id == hidden.id })

        val listener = RecordingListener(Paths.get(FileSystemRoot.LINUX_PATH))
        assertTrue(item.isChecked(listener))
        item.onClick(listener)
        assertEquals(listOf(Paths.get(FileSystemRoot.LINUX_PATH)), listener.navigatedToRoots)
        assertTrue(listener.isDrawerClosed)
        assertTrue(item.onLongClick(listener))
        assertEquals(1, listener.intents.size)
    }

    @Test
    fun aStorageWithoutAPathOpensItsIntentInstead() {
        val shortcut = ExternalStorageShortcut(
            null,
            "Shortcut",
            ExternalStorageUri("primary", "Download")
        )
        putStorages(listOf(shortcut))

        val item = currentItems().filterNotNull().first { it.id == shortcut.id }

        assertEquals("Shortcut", item.getTitle(context))
        val listener = RecordingListener(Paths.get(FileSystemRoot.LINUX_PATH))
        assertFalse("A storage opened elsewhere is never the current one", item.isChecked(listener))
        item.onClick(listener)
        assertEquals(1, listener.intents.size)
        assertEquals(shortcut.createIntent().data, listener.intents.single().data)
        assertTrue(listener.isDrawerClosed)
        assertTrue(listener.navigatedTo.isEmpty())
    }

    @Test
    fun theFileSystemRootShowsItsFreeAndTotalSpace() {
        val root = FileSystemRoot(null, true)
        putStorages(listOf(root))

        val item = currentItems().filterNotNull().first { it.id == root.id }

        val subtitle = item.getSubtitle(context)
        assertNotNull("The file system root should show a subtitle", subtitle)
        // navigation_storage_subtitle_format is "%1$s free of %2$s" or a translation thereof.
        assertTrue(subtitle!!.isNotEmpty())
    }

    @Test
    fun addStorageAndTheMenuItemsAreAlwaysThereAndLaunchTheirActivities() {
        putStorages(listOf(FileSystemRoot(null, true)))

        val items = currentItems()

        val addStorage = items.filterNotNull()
            .first { it.getTitle(context) == context.getString(R.string.navigation_add_storage) }
        val listener = RecordingListener(Paths.get("/"))
        addStorage.onClick(listener)
        assertEquals(
            AddStorageDialogActivity::class.java.name,
            listener.intents.single().component!!.className
        )
        // The add storage item is not a directory, so clicking it leaves the drawer open.
        assertFalse(listener.isDrawerClosed)

        val menuItems = items.takeLast(3).map { checkNotNull(it) }
        assertEquals(
            listOf(
                context.getString(R.string.navigation_ftp_server),
                context.getString(R.string.navigation_settings),
                context.getString(R.string.navigation_about)
            ),
            menuItems.map { it.getTitle(context) }
        )
        val menuListener = RecordingListener(Paths.get("/"))
        menuItems.forEach { it.onClick(menuListener) }
        assertEquals(
            listOf(
                FtpServerActivity::class.java.name,
                SettingsActivity::class.java.name,
                AboutActivity::class.java.name
            ),
            menuListener.intents.map { it.component!!.className }
        )
        assertTrue(menuItems.none { it.isChecked(menuListener) })
        assertTrue(menuItems.none { it.onLongClick(menuListener) })
        // A separator comes before the menu items.
        assertNull(items[items.size - 4])
    }

    @Test
    fun aBookmarkDirectoryBecomesAnItemThatNavigatesAndCanBeEdited() {
        putStorages(listOf(FileSystemRoot(null, true)))
        val bookmarkPath = Paths.get("/storage/emulated/0/Download")
        val bookmark = BookmarkDirectory("Bookmarked downloads", bookmarkPath)
        putBookmarkDirectories(listOf(bookmark))

        val item = currentItems().filterNotNull().first { it.id == bookmark.id }

        assertEquals("Bookmarked downloads", item.getTitle(context))
        val listener = RecordingListener(bookmarkPath)
        assertTrue(item.isChecked(listener))
        item.onClick(listener)
        // A bookmark directory is not a navigation root, so it only navigates to its path.
        assertEquals(listOf(bookmarkPath), listener.navigatedTo)
        assertTrue(listener.navigatedToRoots.isEmpty())
        assertTrue(listener.isDrawerClosed)
        assertTrue(item.onLongClick(listener))
        assertEquals(
            EditBookmarkDirectoryDialogActivity::class.java.name,
            listener.intents.single().component!!.className
        )
    }

    @Test
    fun noBookmarkDirectoriesMeansNoBookmarkSection() {
        putStorages(listOf(FileSystemRoot(null, true)))
        putBookmarkDirectories(emptyList())

        val items = currentItems()

        // Storages, add storage, standard directories and the menu, each section separated once.
        assertEquals(2, items.count { it == null })
    }

    @Test
    fun standardDirectoriesFollowTheirSettingsAndNeverIncludeTheBlockedAppDirectories() {
        putStorages(listOf(FileSystemRoot(null, true)))

        val titles = currentItems().filterNotNull().map { it.getTitle(context) }

        assertTrue(titles.contains(context.getString(R.string.navigation_standard_directory_dcim)))
        assertTrue(
            titles.contains(context.getString(R.string.navigation_standard_directory_downloads))
        )
        // Disabled by default.
        assertFalse(
            titles.contains(context.getString(R.string.navigation_standard_directory_alarms))
        )
        // Android/data has been unreadable since Android 11.
        assertFalse(titles.contains(context.getString(R.string.navigation_standard_directory_qq)))
        assertFalse(titles.contains(context.getString(R.string.navigation_standard_directory_tim)))
        assertFalse(
            titles.contains(context.getString(R.string.navigation_standard_directory_wechat))
        )
    }

    @Test
    fun aStandardDirectoryItemOpensTheStandardDirectoryListOnLongClick() {
        putStorages(listOf(FileSystemRoot(null, true)))
        val dcimTitle = context.getString(R.string.navigation_standard_directory_dcim)

        val item = currentItems().filterNotNull().first { it.getTitle(context) == dcimTitle }

        val listener = RecordingListener(Paths.get("/"))
        item.onClick(listener)
        assertEquals(1, listener.navigatedTo.size)
        assertTrue(listener.navigatedTo.single().toString().endsWith("/DCIM"))
        assertTrue(item.onLongClick(listener))
        assertEquals(
            StandardDirectoryListActivity::class.java.name,
            listener.intents.single().component!!.className
        )
    }

    private fun currentItems(): List<NavigationItem?> {
        lateinit var items: List<NavigationItem?>
        instrumentation.runOnMainSync { items = navigationItems }
        return items
    }

    private fun putStorages(storages: List<Storage>) {
        Settings.STORAGES.putValue(storages)
        instrumentation.runOnMainSync {}
    }

    private fun putBookmarkDirectories(bookmarkDirectories: List<BookmarkDirectory>) {
        Settings.BOOKMARK_DIRECTORIES.putValue(bookmarkDirectories)
        instrumentation.runOnMainSync {}
    }

    private class RecordingListener(override val currentPath: Path) : NavigationItem.Listener {
        val navigatedTo = mutableListOf<Path>()
        val navigatedToRoots = mutableListOf<Path>()
        val intents = mutableListOf<Intent>()
        var isDrawerClosed = false

        override fun navigateTo(path: Path) {
            navigatedTo.add(path)
        }

        override fun navigateToRoot(path: Path) {
            navigatedToRoots.add(path)
        }

        override fun launchIntent(intent: Intent) {
            intents.add(intent)
        }

        override fun closeNavigationDrawer() {
            isDrawerClosed = true
        }
    }
}
