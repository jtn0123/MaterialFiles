/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.zhanghai.android.files.R
import me.zhanghai.android.files.theme.custom.ThemeColorPreference
import me.zhanghai.android.files.ui.MaterialListPreferenceDialogFragmentCompat
import me.zhanghai.android.files.ui.MaterialPreferenceDialogFragmentCompat
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PreferenceFragmentCompat] shows Material dialogs for list preferences and for the preference
 * classes that registered one, and only ever one dialog at a time.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPreferenceDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private var scenario: ActivityScenario<SettingsActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun aRegisteredPreferenceFragmentIsShownAndFindsItsPreference() {
        val fragment = launchSettings()
        val preference =
            fragment.requirePreference<ThemeColorPreference>(R.string.pref_key_theme_color)

        instrumentation.runOnMainSync { fragment.onDisplayPreferenceDialog(preference) }
        instrumentation.waitForIdleSync()

        val dialog = fragment.dialogFragment()
        assertNotNull("No dialog was shown for the theme color preference", dialog)
        assertSame(preference, dialog!!.preference)

        // A second request while one is shown is ignored.
        instrumentation.runOnMainSync { fragment.onDisplayPreferenceDialog(preference) }
        instrumentation.waitForIdleSync()
        assertEquals(
            1,
            fragment.childFragmentManager.fragments
                .count { it is MaterialPreferenceDialogFragmentCompat }
        )
    }

    @Test
    fun aListPreferenceGetsTheMaterialListDialogShowingItsEntries() {
        val fragment = launchSettings()
        val preference = fragment.requirePreference<ListPreference>(R.string.pref_key_root_strategy)

        instrumentation.runOnMainSync { fragment.onDisplayPreferenceDialog(preference) }
        instrumentation.waitForIdleSync()

        val dialogFragment = fragment.dialogFragment()
        assertTrue(
            "Expected the Material list dialog but got $dialogFragment",
            dialogFragment is MaterialListPreferenceDialogFragmentCompat
        )
        assertSame(preference, dialogFragment!!.preference)
        val listView = (dialogFragment.requireDialog() as AlertDialog).listView
        assertEquals(preference.entries.size, listView.count)
        assertEquals(preference.entries.first(), listView.getItemAtPosition(0))
        // The current value is the checked row.
        assertEquals(preference.findIndexOfValue(preference.value), listView.checkedItemPosition)

        instrumentation.runOnMainSync { dialogFragment.dismiss() }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aShownDialogSurvivesTheScreenBeingRecreated() {
        val fragment = launchSettings()
        val preference =
            fragment.requirePreference<ThemeColorPreference>(R.string.pref_key_theme_color)
        instrumentation.runOnMainSync { fragment.onDisplayPreferenceDialog(preference) }
        instrumentation.waitForIdleSync()
        assertNotNull(fragment.dialogFragment())

        scenario!!.recreate()
        instrumentation.waitForIdleSync()

        val recreatedFragment = scenario!!.preferenceFragment()
        val dialog = recreatedFragment.dialogFragment()
        assertNotNull("The dialog must come back after a recreation", dialog)
        // Still finds its preference through the fragment it is a child of.
        assertEquals(preference.key, dialog!!.preference.key)
        instrumentation.runOnMainSync { dialog.dismiss() }
        instrumentation.waitForIdleSync()
    }

    private fun launchSettings(): PreferenceFragmentCompat =
        ActivityScenario.launch(SettingsActivity::class.java)
            .also { this.scenario = it }
            .preferenceFragment()

    private fun ActivityScenario<SettingsActivity>.preferenceFragment(): PreferenceFragmentCompat {
        lateinit var fragment: PreferenceFragmentCompat
        onActivity { activity ->
            fragment = activity.supportFragmentManager.findPreferenceFragment()
                ?: error("No preference fragment in the settings activity")
        }
        return fragment
    }

    private fun androidx.fragment.app.FragmentManager.findPreferenceFragment():
        PreferenceFragmentCompat? {
        for (fragment in fragments) {
            if (fragment is PreferenceFragmentCompat) {
                return fragment
            }
            fragment.childFragmentManager.findPreferenceFragment()?.let { return it }
        }
        return null
    }

    private inline fun <reified T : Preference> PreferenceFragmentCompat.requirePreference(
        keyRes: Int
    ): T {
        val key = requireContext().getString(keyRes)
        return checkNotNull(findPreference<Preference>(key) as? T) {
            "No ${T::class.java.simpleName} for key $key"
        }
    }

    private fun Fragment.dialogFragment(): MaterialPreferenceDialogFragmentCompat? =
        childFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG)
            as? MaterialPreferenceDialogFragmentCompat

    companion object {
        private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"
    }
}
