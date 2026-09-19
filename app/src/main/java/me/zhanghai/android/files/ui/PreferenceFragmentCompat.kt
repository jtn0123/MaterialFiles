/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat as AndroidXPreferenceFragmentCompat

/**
 * The app's preference fragment: Material dialogs for list preferences and for any preference
 * class registered with [registerPreferenceFragment], and a result launcher for preferences that
 * open an activity ([ActivityResultPreference]) that survives the fragment being recreated.
 */
abstract class PreferenceFragmentCompat : AndroidXPreferenceFragmentCompat() {
    private var activityResultPreferenceKey: String? = null

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val key = activityResultPreferenceKey ?: return@registerForActivityResult
            activityResultPreferenceKey = null
            val preference = findPreference<Preference>(key) as? ActivityResultPreference
            preference?.onActivityResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        activityResultPreferenceKey =
            savedInstanceState?.getString(STATE_ACTIVITY_RESULT_PREFERENCE_KEY)
    }

    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        onCreatePreferencesFix(savedInstanceState, rootKey)
    }

    /** [onCreatePreferences] with the preference screen guaranteed to exist afterwards. */
    abstract fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (preferenceScreen == null) {
            val preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            setPreferenceScreen(preferenceScreen)
        }

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(STATE_ACTIVITY_RESULT_PREFERENCE_KEY, activityResultPreferenceKey)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is ActivityResultPreference) {
            activityResultPreferenceKey = preference.key
            activityResultLauncher.launch(preference.createIntent(requireContext()))
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (parentFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) {
            return
        }
        val fragment = when {
            preference is ListPreference -> MaterialListPreferenceDialogFragmentCompat()

            else ->
                dialogFragmentClasses.entries
                    .firstOrNull { it.key.isInstance(preference) }
                    ?.value
                    ?.getDeclaredConstructor()
                    ?.newInstance()
        }
        if (fragment != null) {
            displayPreferenceDialog(fragment, preference.key)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    private fun displayPreferenceDialog(fragment: DialogFragment, key: String) {
        fragment.arguments = bundleOf(MaterialPreferenceDialogFragmentCompat.ARG_KEY to key)
        @Suppress("DEPRECATION")
        fragment.setTargetFragment(this, 0)
        fragment.show(parentFragmentManager, DIALOG_FRAGMENT_TAG)
    }

    companion object {
        // @see androidx.preference.PreferenceFragmentCompat.DIALOG_FRAGMENT_TAG
        private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"

        private const val STATE_ACTIVITY_RESULT_PREFERENCE_KEY = "activityResultPreferenceKey"

        private val dialogFragmentClasses =
            mutableMapOf<Class<out Preference>, Class<out DialogFragment>>()

        /** Shows [dialogFragmentClass] for every preference of [preferenceClass]. */
        fun registerPreferenceFragment(
            preferenceClass: Class<out Preference>,
            dialogFragmentClass: Class<out DialogFragment>
        ) {
            dialogFragmentClasses[preferenceClass] = dialogFragmentClass
        }
    }
}

/**
 * A preference that opens an activity when clicked and takes its result; the fragment does the
 * launching so that the result still arrives after the fragment was recreated meanwhile.
 */
interface ActivityResultPreference {
    fun createIntent(context: android.content.Context): Intent

    fun onActivityResult(resultCode: Int, data: Intent?)
}
