/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.content.Context
import android.util.AttributeSet
import android.view.ContextMenu
import android.view.Menu
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.lifecycle.Observer
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.valueCompat

class FtpServerUrlPreference : Preference {
    private val observer = Observer<Any> { updateUrl() }
    private val watcher = FtpServerUrl.createChangeWatcher(context) { updateUrl() }

    private var url: String? = null

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
        isPersistent = false
        updateUrl()
    }

    override fun onAttached() {
        super.onAttached()

        Settings.FTP_SERVER_ANONYMOUS_LOGIN.observeForever(observer)
        Settings.FTP_SERVER_USERNAME.observeForever(observer)
        Settings.FTP_SERVER_PORT.observeForever(observer)
        watcher.register()
    }

    override fun onDetached() {
        super.onDetached()

        Settings.FTP_SERVER_ANONYMOUS_LOGIN.removeObserver(observer)
        Settings.FTP_SERVER_USERNAME.removeObserver(observer)
        Settings.FTP_SERVER_PORT.removeObserver(observer)
        watcher.unregister()
    }

    private fun updateUrl() {
        url = FtpServerUrl.getUrl()
        summary = url ?: context.getString(R.string.ftp_server_url_summary_no_local_inet_address)
    }

    /** What the long press menu offers: a title and the text copied when it is chosen. */
    internal fun createContextMenuItems(): List<Pair<Int, String>> {
        val url = url ?: return emptyList()
        val items = mutableListOf(R.string.ftp_server_url_menu_copy_url to url)
        if (!Settings.FTP_SERVER_ANONYMOUS_LOGIN.valueCompat) {
            val password = Settings.FTP_SERVER_PASSWORD.valueCompat
            if (password.isNotEmpty()) {
                items += R.string.ftp_server_url_menu_copy_password to password
            }
        }
        return items
    }

    private fun onCreateContextMenu(menu: ContextMenu) {
        val url = url ?: return
        menu.setHeaderTitle(url)
        for ((titleRes, text) in createContextMenuItems()) {
            menu.add(Menu.NONE, Menu.NONE, Menu.NONE, titleRes)
                .setOnMenuItemClickListener {
                    clipboardManager.copyText(text, context)
                    true
                }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.setOnCreateContextMenuListener { menu, _, _ -> onCreateContextMenu(menu) }
    }
}
