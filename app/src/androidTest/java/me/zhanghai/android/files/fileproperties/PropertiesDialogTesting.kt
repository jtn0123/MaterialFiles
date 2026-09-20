/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.viewpager.widget.ViewPager
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.FileListFragment
import org.junit.Assert.assertNotNull

/** Opens the properties dialog of a file in the file list and reads what its tabs show. */
class PropertiesDialogTesting(private val directory: File) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    /** Opens the file list on the test directory and the properties dialog for one file. */
    fun show(file: File): ActivityScenario<FileListActivity> {
        val fileItem: FileItem = Paths.get(file.path).loadFileItem()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<FileListActivity>(intent)
        assertNotNull(
            "The file list never showed ${file.name}",
            device.wait(Until.findObject(By.text(file.name)), TIMEOUT_MILLIS)
        )
        scenario.onActivity { activity ->
            FilePropertiesDialogFragment.show(fileItem, activity.fileListFragment)
        }
        return scenario
    }

    private val FileListActivity.fileListFragment: FileListFragment
        get() = supportFragmentManager.fragments.filterIsInstance<FileListFragment>().single()

    /** Switches to the tab with the given title and returns the values it shows, by their hint. */
    fun openTab(
        scenario: ActivityScenario<FileListActivity>,
        titleRes: Int,
        expectedHintRes: Int
    ): Map<String, String> {
        val title = context.getString(titleRes)
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var items: Map<String, String> = emptyMap()
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val dialog = activity.fileListFragment.childFragmentManager.fragments
                    .filterIsInstance<FilePropertiesDialogFragment>()
                    .singleOrNull()
                    ?.dialog
                if (dialog != null) {
                    val viewPager = dialog.window!!.decorView
                        .findViewById<ViewPager>(R.id.viewPager)
                    val adapter = viewPager.adapter
                    val index = (0 until (adapter?.count ?: 0))
                        .firstOrNull { adapter!!.getPageTitle(it) == title }
                    if (index != null) {
                        if (viewPager.currentItem != index) {
                            viewPager.currentItem = index
                        }
                        items = dialog.window!!.decorView.propertyItems()
                    }
                }
            }
            if (items.containsKey(context.getString(expectedHintRes))) {
                return items
            }
            Thread.sleep(200)
        }
        throw AssertionError("The $title tab never showed its values: ${items.keys}")
    }

    /** Every labelled value in the view tree, as hint to text. */
    private fun View.propertyItems(): Map<String, String> {
        val items = mutableMapOf<String, String>()
        collectPropertyItems(items)
        return items
    }

    private fun View.collectPropertyItems(items: MutableMap<String, String>) {
        if (this is TextInputLayout) {
            val hint = hint?.toString()
            val text = editText?.text?.toString()
            if (hint != null && text != null) {
                items[hint] = text
            }
            return
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).collectPropertyItems(items)
            }
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
