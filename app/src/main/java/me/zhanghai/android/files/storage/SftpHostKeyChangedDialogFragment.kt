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
import me.zhanghai.android.files.provider.sftp.client.HostKeyChange
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs

class SftpHostKeyChangedDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val change = args.change
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.storage_edit_sftp_server_host_key_changed_title)
            .setMessage(
                getString(
                    R.string.storage_edit_sftp_server_host_key_changed_message_format,
                    change.host,
                    change.keyType,
                    change.oldFingerprint,
                    change.newFingerprint
                )
            )
            .setPositiveButton(R.string.storage_edit_sftp_server_host_key_changed_trust) { _, _ ->
                listener.trustHostKey(change)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        private val TAG = SftpHostKeyChangedDialogFragment::class.java.name

        fun show(change: HostKeyChange, fragment: Fragment) {
            SftpHostKeyChangedDialogFragment().putArgs(Args(change))
                .show(fragment.childFragmentManager, TAG)
        }

        fun isShowing(fragment: Fragment): Boolean =
            fragment.childFragmentManager.findFragmentByTag(TAG) != null
    }

    @Parcelize
    class Args(val change: HostKeyChange) : ParcelableArgs

    interface Listener {
        fun trustHostKey(change: HostKeyChange)
    }
}
