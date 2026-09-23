/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A folder still loading shows its entries in batches; entries that sort before the ones already
 * shown must not end up above the top of the list, out of sight.
 */
@RunWith(AndroidJUnit4::class)
class FileListStaysAtTopTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private var previousRootStrategy: RootStrategy? = null
    private var previousViewType: FileViewType? = null
    private var previousSortOptions: FileSortOptions? = null

    @Before
    fun setUp() {
        executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        // Not the cache directory, which the system purges whenever storage runs low.
        directory = File(context.filesDir, "Stays at top").apply { mkdirs() }
        for (index in 0..<FILE_COUNT) {
            File(directory, fileName(index)).writeText("Stays at top")
        }
        instrumentation.runOnMainSync {
            previousRootStrategy = Settings.ROOT_STRATEGY.value
            previousViewType = Settings.FILE_LIST_VIEW_TYPE.value
            previousSortOptions = Settings.FILE_LIST_SORT_OPTIONS.value
            Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
            Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.LIST)
            Settings.FILE_LIST_SORT_OPTIONS.putValue(
                FileSortOptions(FileSortOptions.By.NAME, FileSortOptions.Order.ASCENDING, true)
            )
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            previousRootStrategy?.let { Settings.ROOT_STRATEGY.putValue(it) }
            previousViewType?.let { Settings.FILE_LIST_VIEW_TYPE.putValue(it) }
            previousSortOptions?.let { Settings.FILE_LIST_SORT_OPTIONS.putValue(it) }
        }
        directory.deleteRecursively()
    }

    private fun executeShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test
    fun entriesSortingFirstStayInSightWhenTheRestOfTheFolderArrives() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<FileListActivity>(intent).use { scenario ->
            assertNotNull(device.wait(Until.findObject(By.text(fileName(0))), 20_000))
            lateinit var files: List<FileItem>
            scenario.onFileList { state ->
                files = checkNotNull(state.value?.value)
                // The first batch happens to hold only the second half of the folder.
                state.value = Loading(files.filter { it.name >= fileName(FILE_COUNT / 2) })
            }
            assertTrue(device.wait(Until.gone(By.text(fileName(0))), 10_000))
            assertNotNull(
                device.wait(Until.findObject(By.text(fileName(FILE_COUNT / 2))), 10_000)
            )

            scenario.onFileList { state -> state.value = Success(files) }

            assertNotNull(device.wait(Until.findObject(By.text(fileName(0))), 10_000))
            device.waitForIdle()
            scenario.onActivity { activity ->
                val fragment = activity.fileListFragment
                assertEquals(0, fragment.layoutManager.findFirstCompletelyVisibleItemPosition())
            }
        }
    }

    private val FileItem.name: String
        get() = path.fileName.toString()

    private val FileListActivity.fileListFragment: FileListFragment
        get() = supportFragmentManager.fragments.filterIsInstance<FileListFragment>().single()

    private fun ActivityScenario<FileListActivity>.onFileList(
        block: (MutableLiveData<Stateful<List<FileItem>>>) -> Unit
    ) {
        onActivity { activity ->
            @Suppress("UNCHECKED_CAST")
            block(
                activity.fileListFragment.viewModel.fileListLiveData
                    as MutableLiveData<Stateful<List<FileItem>>>
            )
        }
    }

    companion object {
        // Enough rows to fill a tall tablet screen twice over.
        private const val FILE_COUNT = 60

        private fun fileName(index: Int): String = "Entry %02d.txt".format(index)
    }
}
