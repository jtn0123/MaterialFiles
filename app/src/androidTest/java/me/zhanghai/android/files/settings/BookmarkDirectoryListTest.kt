/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.app.Activity
import android.app.Instrumentation
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.EditBookmarkDirectoryDialogActivity
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bookmark directory list screen: a row per bookmark, the row opens it for editing and only its
 * handle starts a drag, and a finished drag is written back to the setting.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkDirectoryListTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private lateinit var savedBookmarkDirectories: List<BookmarkDirectory>
    private lateinit var bookmarkDirectories: List<BookmarkDirectory>
    private lateinit var scenario: ActivityScenario<BookmarkDirectoryListActivity>

    @Before
    fun setUp() {
        savedBookmarkDirectories = Settings.BOOKMARK_DIRECTORIES.valueCompat
        bookmarkDirectories = listOf(
            BookmarkDirectory("Alpha", Paths.get("/storage/emulated/0/Alpha")),
            BookmarkDirectory(null, Paths.get("/storage/emulated/0/Beta"))
        )
        Settings.BOOKMARK_DIRECTORIES.putValue(bookmarkDirectories)
        scenario = ActivityScenario.launch(BookmarkDirectoryListActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario.close()
        Settings.BOOKMARK_DIRECTORIES.putValue(savedBookmarkDirectories)
    }

    @Test
    fun everyBookmarkGetsARowShowingItsNameAndPath() {
        onRecyclerView { recyclerView ->
            assertEquals(bookmarkDirectories.size, recyclerView.adapter!!.itemCount)
            bookmarkDirectories.forEachIndexed { index, bookmarkDirectory ->
                val row = recyclerView.getChildAt(index)
                assertEquals(
                    bookmarkDirectory.name,
                    row.findViewById<TextView>(R.id.nameText).text
                )
                assertEquals(
                    bookmarkDirectory.path.toUserFriendlyString(),
                    row.findViewById<TextView>(R.id.pathText).text
                )
            }
            // The second bookmark has no custom name, so it falls back to the directory name.
            assertEquals("Beta", bookmarkDirectories[1].name)
        }
    }

    @Test
    fun tappingABookmarkOpensItForEditing() {
        val monitor = instrumentation.addMonitor(
            EditBookmarkDirectoryDialogActivity::class.java.name,
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true
        )
        try {
            onRecyclerView { it.getChildAt(0).performClick() }
            instrumentation.waitForIdleSync()

            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun onlyTheHandleStartsADragAndFinishingOneReordersTheBookmarks() {
        onRecyclerView { recyclerView ->
            val adapter = checkNotNull(
                WrapperAdapterUtils.findWrappedAdapter(
                    recyclerView.adapter!!,
                    BookmarkDirectoryListAdapter::class.java
                )
            ) { "The list must be backed by a BookmarkDirectoryListAdapter" }
            val holder =
                recyclerView.getChildViewHolder(recyclerView.getChildAt(0))
                    as BookmarkDirectoryListAdapter.ViewHolder
            assertTrue(recyclerView.adapter!!.hasStableIds())
            assertEquals(bookmarkDirectories[0].id, adapter.getItemId(0))

            val handle = holder.binding.dragHandleView
            assertTrue(
                "Touching the handle must start a drag",
                adapter.onCheckCanStartDrag(holder, 0, centerX(handle), centerY(handle))
            )
            val nameText = holder.binding.nameText
            assertFalse(
                "Touching the name must not start a drag",
                adapter.onCheckCanStartDrag(holder, 0, centerX(nameText), centerY(nameText))
            )

            adapter.onItemDragStarted(0)
            adapter.onMoveItem(0, 0)
            assertEquals(bookmarkDirectories, Settings.BOOKMARK_DIRECTORIES.valueCompat)

            adapter.onMoveItem(0, 1)
            adapter.onItemDragFinished(0, 1, true)
        }
        instrumentation.waitForIdleSync()

        assertEquals(
            bookmarkDirectories.reversed(),
            Settings.BOOKMARK_DIRECTORIES.valueCompat
        )
    }

    /** The drag manager passes coordinates within the row, and the handle is a child of it. */
    private fun centerX(view: View): Int = view.left + view.width / 2

    private fun centerY(view: View): Int = view.top + view.height / 2

    private fun onRecyclerView(block: (RecyclerView) -> Unit) {
        scenario.onActivity { activity ->
            block(activity.findViewById(R.id.recyclerView))
        }
    }
}
