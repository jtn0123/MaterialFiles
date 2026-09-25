/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Failing to connect to a new server leaves the form in sight, says why above it and marks the
 * field that is likely wrong, instead of a toast with the exception in it.
 */
@RunWith(AndroidJUnit4::class)
class ServerConnectErrorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aServerThatRefusesTheConnectionMarksTheHost() {
        val intent = Intent(context, EditSmbServerActivity::class.java)
            .putArgs(EditSmbServerFragment.Args())
        val storagesBefore = Settings.STORAGES.valueCompat
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.hostEdit).setText("127.0.0.1")
                // Nothing listens on port 1, so the connection is refused right away.
                activity.findViewById<EditText>(R.id.portEdit).setText("1")
                activity.findViewById<EditText>(R.id.usernameEdit).setText("tester")
                activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).performClick()
            }
            waitUntil(scenario) {
                it.findViewById<View>(R.id.connectErrorText).visibility == View.VISIBLE
            }
            scenario.onActivity { activity ->
                val errorText = activity.findViewById<TextView>(R.id.connectErrorText).text
                val connectionFailed = context.getString(R.string.error_connection_failed)
                assertTrue(errorText.toString(), errorText.startsWith(connectionFailed))
                assertEquals(
                    connectionFailed,
                    activity.findViewById<TextInputLayout>(R.id.hostLayout).error
                )
                // The form is still there and usable to fix the host.
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.scrollView).visibility)
                assertTrue(activity.findViewById<View>(R.id.hostEdit).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.saveOrConnectAndAddButton).isEnabled)
                assertFalse(activity.isFinishing)
            }
        }
        assertEquals(storagesBefore, Settings.STORAGES.valueCompat)
    }

    private fun waitUntil(scenario: ActivityScenario<Activity>, predicate: (Activity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var isMet = false
            scenario.onActivity { isMet = predicate(it) }
            if (isMet) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError("The connect error never showed")
    }
}
