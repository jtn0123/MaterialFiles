/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.test.core.app.ActivityScenario
import com.google.android.material.textfield.TextInputLayout
import me.zhanghai.android.files.R
import org.junit.Assert.assertTrue

/**
 * Drives the dialogs of the file list through their views.
 *
 * A name dialog pans itself out of the way of the soft keyboard, so where its buttons end up on
 * screen is anyone's guess, and tapping there sometimes types a letter instead.
 */
object FileListDialogTesting {
    /** Waits for a dialog that asks for a name, and returns nothing once it is there. */
    fun awaitNameDialog(scenario: ActivityScenario<FileListActivity>) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                found = activity.showingDialogs().any { it.nameEdit() != null }
            }
            if (!found) {
                Thread.sleep(100)
            }
        }
        assertTrue("No dialog asked for a name", found)
    }

    /** Puts a name into the dialog that asks for one. */
    fun typeName(scenario: ActivityScenario<FileListActivity>, name: String) {
        awaitNameDialog(scenario)
        onNameDialog(scenario) { it.nameEdit()!!.setText(name) }
    }

    /** Presses the dialog's OK button, from the thread that owns it. */
    fun confirm(scenario: ActivityScenario<FileListActivity>) {
        onNameDialog(scenario) {
            (it as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
    }

    /** Presses the dialog's cancel button, which is the only way out that closes it for sure. */
    fun cancel(scenario: ActivityScenario<FileListActivity>) {
        onNameDialog(scenario) {
            (it as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
    }

    /** What the dialog says is wrong with the name, if anything. */
    fun nameError(scenario: ActivityScenario<FileListActivity>): String? {
        var error: String? = null
        scenario.onActivity { activity ->
            error = activity.showingDialogs()
                .firstOrNull { it.nameEdit() != null }
                ?.findViewById<TextInputLayout>(R.id.nameLayout)
                ?.error
                ?.toString()
        }
        return error
    }

    /** Waits for the dialog to say what is wrong with the name. */
    fun awaitNameError(scenario: ActivityScenario<FileListActivity>, expected: String): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var error: String? = null
        while (error != expected && System.currentTimeMillis() < deadline) {
            error = nameError(scenario)
            if (error != expected) {
                Thread.sleep(100)
            }
        }
        return error
    }

    private fun onNameDialog(
        scenario: ActivityScenario<FileListActivity>,
        block: (Dialog) -> Unit
    ) {
        var found = false
        scenario.onActivity { activity ->
            val dialog = activity.showingDialogs().firstOrNull { it.nameEdit() != null }
            if (dialog != null) {
                found = true
                block(dialog)
            }
        }
        assertTrue("No dialog asked for a name", found)
    }

    private fun Dialog.nameEdit(): EditText? = findViewById(R.id.nameEdit)

    private fun FileListActivity.showingDialogs(): List<Dialog> =
        supportFragmentManager.dialogFragments().mapNotNull { fragment ->
            fragment.dialog?.takeIf { it.isShowing }
        }

    private fun FragmentManager.dialogFragments(): List<DialogFragment> =
        fragments.flatMap { fragment ->
            val children = if (fragment.isAdded) {
                fragment.childFragmentManager.dialogFragments()
            } else {
                emptyList()
            }
            if (fragment is DialogFragment) children + fragment else children
        }

    const val TIMEOUT_MILLIS = 20_000L
}
