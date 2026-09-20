/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.app.Instrumentation
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The storage list screen shows every storage and opens the one that is tapped for editing. */
@RunWith(AndroidJUnit4::class)
class StorageListTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<StorageListActivity>
    private lateinit var storages: List<Storage>

    @Before
    fun setUp() {
        storages = Settings.STORAGES.valueCompat
        scenario = ActivityScenario.launch(StorageListActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun everyStorageGetsARowShowingItsNameAndDescription() {
        assertTrue("The emulator has at least the internal storage", storages.isNotEmpty())

        scenario.onActivity { activity ->
            val recyclerView = activity.findViewById<RecyclerView>(R.id.recyclerView)
            assertEquals(storages.size, recyclerView.adapter!!.itemCount)
            val row = recyclerView.getChildAt(0)
            assertNotNull("The first storage must have a row", row)
            val storage = storages.first()
            assertEquals(
                storage.getName(context),
                row.findViewById<TextView>(R.id.nameText).text
            )
            assertEquals(
                storage.isVisible,
                row.findViewById<TextView>(R.id.nameText).isActivated
            )
            assertEquals(
                storage.description,
                row.findViewById<TextView>(R.id.descriptionText).text
            )
        }
    }

    @Test
    fun tappingAStorageOpensItsEditScreen() {
        val editActivityName = storages.first().createEditIntent().component!!.className
        val monitor = instrumentation.addMonitor(
            editActivityName,
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true
        )
        try {
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.recyclerView).getChildAt(0).performClick()
            }
            instrumentation.waitForIdleSync()

            assertEquals(
                "Tapping a storage must open $editActivityName",
                1,
                monitor.hits
            )
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
