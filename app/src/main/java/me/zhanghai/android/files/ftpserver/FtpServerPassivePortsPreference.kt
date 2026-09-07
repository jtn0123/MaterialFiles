/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.preference.Preference
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.EditTextPreference
import me.zhanghai.android.files.util.showToast

/** An [EditTextPreference] that only stores a valid, normalized [FtpPassivePorts] list. */
class FtpServerPassivePortsPreference : EditTextPreference {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        summaryProvider = Preference.SummaryProvider<FtpServerPassivePortsPreference> {
            it.text?.takeIf { text -> text.isNotEmpty() }
                ?: it.context.getString(R.string.ftp_server_passive_ports_summary_any)
        }
    }

    override fun setText(text: String?) {
        val normalized = FtpPassivePorts.normalize(text.orEmpty())
        if (normalized == null) {
            context.showToast(R.string.ftp_server_passive_ports_error_invalid)
            return
        }
        super.setText(normalized)
    }
}
