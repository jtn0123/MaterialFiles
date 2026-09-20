/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.about

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.LicensesDialogFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The about screen's open source licenses dialog, which renders the notices into a web view. */
@RunWith(AndroidJUnit4::class)
class AboutLicensesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<AboutActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun theLicensesDialogRendersTheNoticesAndKeepsThemAcrossARecreation() {
        scenario.onActivity { it.findViewById<View>(R.id.licensesLayout).performClick() }
        instrumentation.waitForIdleSync()

        val dialogFragment = licensesDialogFragment()
        assertNotNull("Tapping licenses must show the licenses dialog", dialogFragment)
        val title = dialogFragment!!.requireDialog()
            .findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
        assertEquals(context.getString(R.string.about_licenses_title), title.text)
        val webView = checkNotNull(
            dialogFragment.requireDialog().window!!.decorView.findWebView()
        ) { "The licenses dialog has no web view" }
        assertTrue("The notices must be rendered", waitForContent(webView))

        // The notices are parcelled into the saved state instead of being parsed again.
        scenario.recreate()
        instrumentation.waitForIdleSync()

        val recreatedDialogFragment = licensesDialogFragment()
        assertNotNull("The dialog must come back after a recreation", recreatedDialogFragment)
        val recreatedWebView = checkNotNull(
            recreatedDialogFragment!!.requireDialog().window!!.decorView.findWebView()
        ) { "The recreated licenses dialog has no web view" }
        assertTrue("The notices must be rendered again", waitForContent(recreatedWebView))
    }

    private fun waitForContent(webView: WebView): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var contentHeight = 0
            instrumentation.runOnMainSync { contentHeight = webView.contentHeight }
            if (contentHeight > 0) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) {
            return this
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findWebView()?.let { return it }
            }
        }
        return null
    }

    private fun licensesDialogFragment(): LicensesDialogFragment? {
        var dialogFragment: LicensesDialogFragment? = null
        scenario.onActivity { activity ->
            dialogFragment = activity.supportFragmentManager.findLicensesDialogFragment()
        }
        return dialogFragment
    }

    private fun FragmentManager.findLicensesDialogFragment(): LicensesDialogFragment? {
        for (fragment in fragments) {
            if (fragment is LicensesDialogFragment) {
                return fragment
            }
            fragment.childFragmentManager.findLicensesDialogFragment()?.let { return it }
        }
        return null
    }
}
