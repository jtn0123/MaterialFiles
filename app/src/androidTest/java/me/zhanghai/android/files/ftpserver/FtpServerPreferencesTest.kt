/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.atomic.AtomicInteger
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.EditTextPreference
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.primaryText
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The FTP server screen's preferences, as they are inflated and used by the real screen. */
@RunWith(AndroidJUnit4::class)
class FtpServerPreferencesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var scenario: ActivityScenario<FtpServerActivity>? = null
    private lateinit var fragment: PreferenceFragmentCompat
    private var savedPassivePorts: String? = null

    @Before
    fun setUp() {
        val scenario = ActivityScenario.launch(FtpServerActivity::class.java)
            .also { this.scenario = it }
        scenario.onActivity { activity ->
            fragment = activity.supportFragmentManager.findPreferenceFragment()
                ?: error("No preference fragment in the FTP server activity")
        }
        savedPassivePorts = passivePortsPreference().text
    }

    @After
    fun tearDown() {
        if (this::fragment.isInitialized) {
            setText(passivePortsPreference(), savedPassivePorts.orEmpty())
        }
        scenario?.close()
    }

    @Test
    fun passivePortsAreNormalizedAndInvalidOnesAreRejected() {
        val preference = passivePortsPreference()

        setText(preference, "2400-2401, 2300-2399, 2350")
        assertEquals("2300-2401", preference.text)
        assertEquals("2300-2401", preference.summary)

        setText(preference, "0")
        assertEquals("An invalid value must be rejected", "2300-2401", preference.text)

        setText(preference, "")
        assertEquals("", preference.text)
        assertEquals(
            context.getString(R.string.ftp_server_passive_ports_summary_any),
            preference.summary
        )
    }

    @Test
    fun theInputTypeFromXmlReachesTheDialogEditTextAndAnyOwnListenerStillRuns() {
        val preference = passivePortsPreference()
        var listenerRan = false
        preference.setOnBindEditTextListener { listenerRan = true }

        instrumentation.runOnMainSync { fragment.onDisplayPreferenceDialog(preference) }
        instrumentation.waitForIdleSync()

        val dialogFragment = checkNotNull(
            fragment.parentFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) as DialogFragment?
        ) { "No dialog was shown for the passive ports preference" }
        try {
            val editText = checkNotNull(
                dialogFragment.requireDialog().findViewById<EditText>(android.R.id.edit)
            ) { "The preference dialog has no edit text" }
            // android:inputType="text" in ftp_server.xml.
            assertEquals(InputType.TYPE_CLASS_TEXT, editText.inputType)
            assertTrue("The listener set on the preference must still be called", listenerRan)
        } finally {
            instrumentation.runOnMainSync { dialogFragment.dismiss() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun theServerIsNotRunningAndTheUrlPreferenceAgreesWithTheUrl() {
        val statePreference = requirePreference<Preference>(R.string.pref_key_ftp_server_state)
        assertEquals(
            context.getString(R.string.ftp_server_state_summary_stopped),
            statePreference.summary
        )

        val urlPreference = fragment.preferenceScreen
            .findPreferenceOfType(FtpServerUrlPreference::class.java)
        assertNotNull("The FTP server screen has a URL preference", urlPreference)
        val expected = FtpServerUrl.getUrl()
            ?: context.getString(R.string.ftp_server_url_summary_no_local_inet_address)
        assertEquals(expected, urlPreference!!.summary)
    }

    @Test
    fun theUrlMenuOffersThePasswordOnlyWhenThereIsOneToCopy() {
        val urlPreference = checkNotNull(
            fragment.preferenceScreen.findPreferenceOfType(FtpServerUrlPreference::class.java)
        ) { "The FTP server screen has no URL preference" }
        val savedAnonymous = Settings.FTP_SERVER_ANONYMOUS_LOGIN.valueCompat
        val savedPassword = Settings.FTP_SERVER_PASSWORD.valueCompat
        try {
            instrumentation.runOnMainSync {
                Settings.FTP_SERVER_ANONYMOUS_LOGIN.putValue(true)
            }
            assertEquals(
                listOf(R.string.ftp_server_url_menu_copy_url),
                urlPreference.createContextMenuItems().map { it.first }
            )

            instrumentation.runOnMainSync {
                Settings.FTP_SERVER_ANONYMOUS_LOGIN.putValue(false)
                Settings.FTP_SERVER_PASSWORD.putValue("")
            }
            assertEquals(
                "Without a password there is nothing to copy",
                listOf(R.string.ftp_server_url_menu_copy_url),
                urlPreference.createContextMenuItems().map { it.first }
            )

            instrumentation.runOnMainSync { Settings.FTP_SERVER_PASSWORD.putValue("secret") }
            val items = urlPreference.createContextMenuItems()
            assertEquals(
                listOf(
                    R.string.ftp_server_url_menu_copy_url,
                    R.string.ftp_server_url_menu_copy_password
                ),
                items.map { it.first }
            )
            assertEquals("secret", items[1].second)
            assertEquals(FtpServerUrl.getUrl(), items[0].second)
        } finally {
            instrumentation.runOnMainSync {
                Settings.FTP_SERVER_ANONYMOUS_LOGIN.putValue(savedAnonymous)
                Settings.FTP_SERVER_PASSWORD.putValue(savedPassword)
            }
        }
    }

    @Test
    fun longPressingTheUrlCopiesItToTheClipboard() {
        val urlPreference = checkNotNull(
            fragment.preferenceScreen.findPreferenceOfType(FtpServerUrlPreference::class.java)
        ) { "The FTP server screen has no URL preference" }
        val url = FtpServerUrl.getUrl()
        assertNotNull("The emulator must have a local address for this test", url)
        val row = checkNotNull(rowFor(urlPreference)) { "The URL preference has no row" }

        instrumentation.runOnMainSync { row.showContextMenu() }
        val device = UiDevice.getInstance(instrumentation)
        val copyUrl = device.wait(
            Until.findObject(By.text(context.getString(R.string.ftp_server_url_menu_copy_url))),
            5000
        )
        assertNotNull("Long pressing the URL must offer to copy it", copyUrl)
        copyUrl.click()
        device.waitForIdle()

        var copied: CharSequence? = null
        scenario!!.onActivity {
            copied = it.getSystemService(ClipboardManager::class.java).primaryText
        }
        assertEquals(url, copied)
    }

    private fun rowFor(preference: Preference): View? {
        var row: View? = null
        instrumentation.runOnMainSync {
            val listView = fragment.listView
            for (index in 0 until listView.childCount) {
                val child = listView.getChildAt(index)
                val title = child.findViewById<TextView>(android.R.id.title)
                if (title != null && title.text == preference.title) {
                    row = child
                    break
                }
            }
        }
        return row
    }

    @Test
    fun aNetworkChangeRefreshesWhateverWatchesTheUrl() {
        val changes = AtomicInteger()
        val watcher = FtpServerUrl.createChangeWatcher(context) { changes.incrementAndGet() }

        watcher.register()
        try {
            // Registering reports the network that is already there.
            assertTrue(
                "Registering must report the current default network",
                waitForChange(changes, 0)
            )

            val afterRegister = changes.get()
            instrumentation.runOnMainSync { watcher.networkCallback.onLost(activeNetwork()) }
            assertTrue("Losing the network must refresh the URL", changes.get() > afterRegister)

            val afterLost = changes.get()
            instrumentation.runOnMainSync { watcher.networkCallback.onAvailable(activeNetwork()) }
            assertTrue("Getting a network must refresh the URL", changes.get() > afterLost)

            val afterAvailable = changes.get()
            instrumentation.runOnMainSync {
                watcher.networkCallback.onLinkPropertiesChanged(activeNetwork(), LinkProperties())
            }
            assertTrue(
                "A new address on the same network must refresh the URL",
                changes.get() > afterAvailable
            )
        } finally {
            watcher.unregister()
        }

        // Nothing arrives once we are done watching.
        val afterUnregister = changes.get()
        assertFalse(waitForChange(changes, afterUnregister, timeoutMillis = 500))
    }

    private fun waitForChange(
        changes: AtomicInteger,
        from: Int,
        timeoutMillis: Long = 5000
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (changes.get() > from) {
                return true
            }
            Thread.sleep(50)
        }
        return false
    }

    private fun activeNetwork(): Network = checkNotNull(
        context.getSystemService(ConnectivityManager::class.java).activeNetwork
    ) { "The emulator has no active network" }

    private fun passivePortsPreference(): FtpServerPassivePortsPreference =
        requirePreference(R.string.pref_key_ftp_server_passive_ports)

    private fun setText(preference: EditTextPreference, text: String) {
        instrumentation.runOnMainSync { preference.text = text }
        instrumentation.waitForIdleSync()
    }

    private fun <T : Preference> requirePreference(keyRes: Int): T {
        val key = context.getString(keyRes)
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(fragment.findPreference<Preference>(key) as T?) {
            "No preference for key $key"
        }
    }

    private fun <T : Preference> PreferenceGroup.findPreferenceOfType(type: Class<T>): T? {
        for (index in 0 until preferenceCount) {
            val preference = getPreference(index)
            if (type.isInstance(preference)) {
                return type.cast(preference)
            }
            if (preference is PreferenceGroup) {
                preference.findPreferenceOfType(type)?.let { return it }
            }
        }
        return null
    }

    private fun FragmentManager.findPreferenceFragment(): PreferenceFragmentCompat? {
        for (fragment in fragments) {
            if (fragment is PreferenceFragmentCompat) {
                return fragment
            }
            fragment.childFragmentManager.findPreferenceFragment()?.let { return it }
        }
        return null
    }

    companion object {
        // @see androidx.preference.PreferenceFragmentCompat.DIALOG_FRAGMENT_TAG
        private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"
    }
}
