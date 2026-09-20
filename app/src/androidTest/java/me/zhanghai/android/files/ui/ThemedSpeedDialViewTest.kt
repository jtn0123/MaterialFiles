/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leinardi.android.speeddial.SpeedDialView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The file list's floating action button. It replaces the library's rotation with its own animator
 * on the main button's drawable, and it hands the library's callbacks to whoever set a listener on
 * it instead of to the listener it installed itself.
 */
@RunWith(AndroidJUnit4::class)
class ThemedSpeedDialViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private var scenario: ActivityScenario<FtpServerActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun theMainActionIsReportedWhenThereIsNothingToOpen() {
        val view = addSpeedDialView()
        val listener = RecordingListener()
        onMain { view.setOnChangeListener(listener) }

        onMain { view.open() }

        assertEquals(1, listener.mainActionSelectedCount)
        assertTrue("A speed dial without items must stay closed", listener.toggles.isEmpty())
        assertFalse(view.isOpen)
    }

    @Test
    fun openingTurnsTheMainFabDrawableAndClosingTurnsItBack() {
        val view = addSpeedDialView()
        val listener = RecordingListener()
        onMain {
            view.inflate(R.menu.file_list_speed_dial)
            view.setOnChangeListener(listener)
        }
        assertNotEquals(view.mainFabClosedBackgroundColor, view.mainFabOpenedBackgroundColor)
        assertEquals(0, onMain { view.mainFab.drawable.level })

        onMain { view.open() }

        assertTrue(view.isOpen)
        assertEquals(listOf(true), listener.toggles)
        assertEquals(0, listener.mainActionSelectedCount)
        waitForLevel(view, 10000)
        assertEquals(
            view.mainFabOpenedBackgroundColor,
            onMain { view.mainFab.backgroundTintList!!.defaultColor }
        )

        onMain { view.close() }

        assertFalse(view.isOpen)
        assertEquals(listOf(true, false), listener.toggles)
        waitForLevel(view, 0)
    }

    private fun addSpeedDialView(): ThemedSpeedDialView {
        val scenario = ActivityScenario.launch(FtpServerActivity::class.java)
            .also { this.scenario = it }
        var view: ThemedSpeedDialView? = null
        scenario.onActivity { activity ->
            val container = FrameLayout(activity)
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                container,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            // The real layout gives the main button its icon and its rotation angle.
            activity.layoutInflater.inflate(
                R.layout.file_list_fragment_speed_dial_include,
                container,
                true
            )
            view = container.findViewById(R.id.speedDialView)
        }
        instrumentation.waitForIdleSync()
        return checkNotNull(view) { "The layout has no speed dial view" }
    }

    /** The drawable level is animated, so it only reaches its end value a frame or two later. */
    private fun waitForLevel(view: ThemedSpeedDialView, level: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (onMain { view.mainFab.drawable.level } == level) {
                return
            }
            Thread.sleep(50)
        }
        assertEquals(level, onMain { view.mainFab.drawable.level })
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private class RecordingListener : SpeedDialView.OnChangeListener {
        val toggles = mutableListOf<Boolean>()

        var mainActionSelectedCount = 0

        override fun onMainActionSelected(): Boolean {
            mainActionSelectedCount++
            return false
        }

        override fun onToggleChanged(isOpen: Boolean) {
            toggles += isOpen
        }
    }
}
