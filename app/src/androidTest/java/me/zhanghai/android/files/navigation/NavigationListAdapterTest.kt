/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.CheckableForegroundLinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How [NavigationListAdapter] turns navigation items and separators into rows. */
@RunWith(AndroidJUnit4::class)
class NavigationListAdapterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val listener = RecordingListener()

    @Test
    fun itemsAndSeparatorsGetDifferentViewTypesAndStableIds() {
        onMainThread {
            val adapter = createAdapter()
            adapter.replace(listOf(TestItem(1, "First"), null, TestItem(2, "Second")))

            assertEquals(3, adapter.itemCount)
            assertEquals(adapter.getItemViewType(0), adapter.getItemViewType(2))
            assertNotEquals(adapter.getItemViewType(0), adapter.getItemViewType(1))
            assertEquals(1L, adapter.getItemId(0))
            assertEquals(2L, adapter.getItemId(2))
            // A separator is identified by how many separators come before it.
            assertEquals(0L, adapter.getItemId(1))
            assertEquals(2, adapter.findPositionById(2L))
        }
    }

    @Test
    fun anItemRowShowsItsTitleSubtitleAndIconAndForwardsClicks() {
        onMainThread {
            val context = themedContext()
            val adapter = createAdapter(context)
            val item = TestItem(1, "Title", "Subtitle")
            adapter.replace(listOf<NavigationItem?>(item))
            val parent = FrameLayout(context)

            val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
            adapter.bindViewHolder(holder, 0)

            val titleText = holder.itemView.findViewById<TextView>(R.id.titleText)
            val subtitleText = holder.itemView.findViewById<TextView>(R.id.subtitleText)
            assertEquals("Title", titleText.text.toString())
            assertEquals("Subtitle", subtitleText.text.toString())
            assertNotNull(holder.itemView.findViewById<ImageView>(R.id.iconImage).drawable)

            holder.itemView.findViewById<View>(R.id.itemLayout).performClick()
            assertEquals(listOf(1L), listener.clickedIds)
            holder.itemView.findViewById<View>(R.id.itemLayout).performLongClick()
            assertEquals(listOf(1L), listener.longClickedIds)
        }
    }

    @Test
    fun theCheckedStateFollowsTheCurrentPathAndIsUpdatedInPlace() {
        onMainThread {
            val context = themedContext()
            val adapter = createAdapter(context)
            val checkedPath = Paths.get("/storage/emulated/0")
            val item = TestItem(1, "Checked", checkedPath = checkedPath)
            adapter.replace(listOf<NavigationItem?>(item))
            val parent = FrameLayout(context)
            val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))

            adapter.bindViewHolder(holder, 0)
            val itemLayout =
                holder.itemView.findViewById<CheckableForegroundLinearLayout>(R.id.itemLayout)
            assertFalse(itemLayout.isChecked)

            listener.currentPath = checkedPath
            // What NavigationFragment does after navigating: rebind with the checked payload only.
            adapter.onBindViewHolder(holder, 0, listOf(Any()))
            assertTrue(itemLayout.isChecked)
        }
    }

    @Test
    fun aSeparatorRowHasNoText() {
        onMainThread {
            val context = themedContext()
            val adapter = createAdapter(context)
            adapter.replace(listOf(null))
            val parent = FrameLayout(context)

            val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
            adapter.bindViewHolder(holder, 0)

            assertNull(holder.itemView.findViewById<TextView>(R.id.titleText))
            assertNotNull(holder.itemView)
        }
    }

    private fun createAdapter(context: Context = themedContext()): NavigationListAdapter =
        NavigationListAdapter(listener, context)

    private fun themedContext(): Context =
        ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MaterialFiles)

    private fun onMainThread(block: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
    }

    private class TestItem(
        override val id: Long,
        private val title: String,
        private val subtitle: String? = null,
        private val checkedPath: Path? = null
    ) : NavigationItem() {
        override val iconRes: Int = R.drawable.directory_icon_white_24dp

        override fun getTitle(context: Context): String = title

        override fun getSubtitle(context: Context): String? = subtitle

        override fun isChecked(listener: Listener): Boolean =
            checkedPath != null && listener.currentPath == checkedPath

        override fun onClick(listener: Listener) {
            (listener as RecordingListener).clickedIds.add(id)
        }

        override fun onLongClick(listener: Listener): Boolean {
            (listener as RecordingListener).longClickedIds.add(id)
            return true
        }
    }

    private class RecordingListener : NavigationItem.Listener {
        override var currentPath: Path = Paths.get("/")
        val clickedIds = mutableListOf<Long>()
        val longClickedIds = mutableListOf<Long>()

        override fun navigateTo(path: Path) {}

        override fun navigateToRoot(path: Path) {}

        override fun launchIntent(intent: Intent) {}

        override fun closeNavigationDrawer() {}
    }
}
