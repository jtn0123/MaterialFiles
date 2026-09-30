/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import me.zhanghai.android.files.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When the search for servers on the local network shows none, a row says why and offers to
 * search again, and a screen reader hears it without having to look for it.
 */
@RunWith(AndroidJUnit4::class)
class LanSmbServerStatusRowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<StorageListActivity>
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LanSmbServerStatusAdapter
    private var retryCount = 0

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(StorageListActivity::class.java)
        scenario.onActivity { activity ->
            adapter = LanSmbServerStatusAdapter { ++retryCount }
            recyclerView = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                // A removed row would otherwise linger while it animates away.
                itemAnimator = null
                adapter = this@LanSmbServerStatusRowTest.adapter
                setBackgroundColor(android.graphics.Color.WHITE)
            }
            activity.addContentView(
                recyclerView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun showStatus(status: LanSmbServerDiscoveryStatus?) {
        instrumentation.runOnMainSync { adapter.status = status }
        instrumentation.waitForIdleSync()
    }

    private fun row(): View {
        var row: View? = null
        instrumentation.runOnMainSync { row = recyclerView.getChildAt(0) }
        return checkNotNull(row) { "There is no status row" }
    }

    @Test
    fun aSearchThatFoundNothingSuggestsWhyAndHowToGoOn() {
        showStatus(LanSmbServerDiscoveryStatus.NoneFound)

        val statusText = row().findViewById<TextView>(R.id.statusText)
        assertEquals(
            context.getString(R.string.storage_add_lan_smb_server_none_found),
            statusText.text.toString()
        )
        assertTrue(statusText.text.contains("Wi-Fi"))
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, statusText.accessibilityLiveRegion)
    }

    @Test
    fun beingOffALocalNetworkIsSaid() {
        showStatus(LanSmbServerDiscoveryStatus.NotOnLocalNetwork)

        assertEquals(
            context.getString(R.string.storage_add_lan_smb_server_not_on_local_network),
            row().findViewById<TextView>(R.id.statusText).text.toString()
        )
    }

    @Test
    fun aFailedSearchSaysWhatWentWrongAndCanBeRetried() {
        showStatus(LanSmbServerDiscoveryStatus.Failed(IOException("Network is unreachable")))

        val row = row()
        val statusText = row.findViewById<TextView>(R.id.statusText).text.toString()
        assertTrue(statusText, statusText.contains("Network is unreachable"))
        val retryButton = row.findViewById<Button>(R.id.retryButton)
        assertEquals(
            context.getString(R.string.storage_add_lan_smb_server_search_again),
            retryButton.text.toString()
        )
        instrumentation.runOnMainSync { retryButton.performClick() }
        assertEquals(1, retryCount)
    }

    @Test
    fun theRowGoesAwayOnceThereIsNothingToSay() {
        showStatus(LanSmbServerDiscoveryStatus.NoneFound)
        assertEquals(1, adapter.itemCount)

        showStatus(null)

        assertEquals(0, adapter.itemCount)
        var childCount = -1
        instrumentation.runOnMainSync { childCount = recyclerView.childCount }
        assertEquals(0, childCount)
    }
}
