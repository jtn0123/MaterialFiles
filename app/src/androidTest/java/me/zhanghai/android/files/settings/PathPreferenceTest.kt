/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.extraPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A preference that picks a directory opens the file list and takes the directory it comes back
 * with, going through the fragment's activity result launcher.
 */
@RunWith(AndroidJUnit4::class)
class PathPreferenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private var scenario: ActivityScenario<SettingsActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun theIntentOpensTheFileListAtTheCurrentDirectory() {
        val preference = defaultDirectoryPreference().second

        val intent = preference.createIntent(instrumentation.targetContext)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(
            FileListActivity::class.java.name,
            intent.component!!.className
        )
        assertEquals(preference.path, intent.extraPath)
    }

    @Test
    fun thePickedDirectoryBecomesTheNewValue() {
        val (fragment, preference) = defaultDirectoryPreference()
        val oldPath = preference.path
        val pickedPath = Paths.get(instrumentation.targetContext.cacheDir.path)
        val result = Instrumentation.ActivityResult(
            Activity.RESULT_OK,
            Intent().apply { extraPath = pickedPath }
        )
        val monitor = instrumentation.addMonitor(FileListActivity::class.java.name, result, true)
        try {
            instrumentation.runOnMainSync { fragment.onPreferenceTreeClick(preference) }
            instrumentation.waitForIdleSync()

            assertEquals(pickedPath, preference.path)
            assertTrue(
                "The summary must show the picked directory but was ${preference.summary}",
                preference.summary!!.contains(pickedPath.fileName.toString())
            )
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { preference.path = oldPath }
        }
    }

    private fun defaultDirectoryPreference(): Pair<PreferenceFragmentCompat, PathPreference> {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
            .also { this.scenario = it }
        lateinit var fragment: PreferenceFragmentCompat
        scenario.onActivity { activity ->
            fragment = activity.supportFragmentManager.findPreferenceFragment()
                ?: error("No preference fragment in the settings activity")
        }
        val key = instrumentation.targetContext
            .getString(R.string.pref_key_file_list_default_directory)
        val preference = checkNotNull(fragment.findPreference<Preference>(key) as? PathPreference) {
            "No path preference for key $key"
        }
        return fragment to preference
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
}
