/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/**
 * Asks before a saved server is removed, since its password goes with it and there is no undo.
 * The fragment that shows it is told when the user confirms, even after a rotation.
 */
class ConfirmRemoveServerDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(getString(R.string.storage_edit_server_remove_title_format, args.name))
            .setMessage(R.string.storage_edit_server_remove_message)
            .setPositiveButton(R.string.remove) { _, _ -> listener.removeServer() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    companion object {
        fun show(name: String, fragment: Fragment) {
            ConfirmRemoveServerDialogFragment().putArgs(Args(name)).show(fragment)
        }
    }

    @Parcelize
    class Args(val name: String) : ParcelableArgs

    interface Listener {
        fun removeServer()
    }
}
