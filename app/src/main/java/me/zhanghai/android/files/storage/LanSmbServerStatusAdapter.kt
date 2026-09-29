/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.LanSmbServerStatusItemBinding
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.toUserMessage

/**
 * One row that says why the search for servers on the local network shows none, with a button to
 * search again; no row while there is nothing to say.
 */
class LanSmbServerStatusAdapter(private val onRetry: () -> Unit) :
    RecyclerView.Adapter<LanSmbServerStatusAdapter.ViewHolder>() {
    init {
        setHasStableIds(true)
    }

    var status: LanSmbServerDiscoveryStatus? = null
        set(value) {
            if (field == value) {
                return
            }
            val hadRow = field != null
            field = value
            when {
                hadRow && value == null -> notifyItemRemoved(0)
                !hadRow && value != null -> notifyItemInserted(0)
                else -> notifyItemChanged(0)
            }
        }

    override fun getItemCount(): Int = if (status != null) 1 else 0

    override fun getItemId(position: Int): Long = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LanSmbServerStatusItemBinding.inflate(parent.context.layoutInflater, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        binding.statusText.text = status!!.getMessage(binding.statusText.context)
        binding.retryButton.setOnClickListener { onRetry() }
    }

    class ViewHolder(val binding: LanSmbServerStatusItemBinding) :
        RecyclerView.ViewHolder(binding.root)
}

fun LanSmbServerDiscoveryStatus.getMessage(context: Context): String = when (this) {
    LanSmbServerDiscoveryStatus.NoneFound ->
        context.getString(R.string.storage_add_lan_smb_server_none_found)

    LanSmbServerDiscoveryStatus.NotOnLocalNetwork ->
        context.getString(R.string.storage_add_lan_smb_server_not_on_local_network)

    is LanSmbServerDiscoveryStatus.Failed -> context.getString(
        R.string.storage_add_lan_smb_server_failed_format,
        throwable.toUserMessage(context)
    )
}
