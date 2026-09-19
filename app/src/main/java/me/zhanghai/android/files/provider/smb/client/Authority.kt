/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import android.os.Parcel
import android.os.Parcelable
import com.hierynomus.smbj.SMBClient
import me.zhanghai.android.files.provider.common.UriAuthority
import me.zhanghai.android.files.util.takeIfNotEmpty

data class Authority(
    val host: String,
    val port: Int,
    val username: String,
    val domain: String?,
    /** Ask for SMB3 encryption; needed for shares that require it, harmless otherwise. */
    val encrypt: Boolean = DEFAULT_ENCRYPT
) : Parcelable {
    fun toUriAuthority(): UriAuthority {
        val userInfo = if (domain != null) "$domain\\$username" else username.takeIfNotEmpty()
        val uriPort = port.takeIf { it != DEFAULT_PORT }
        return UriAuthority(userInfo, host, uriPort)
    }

    override fun toString(): String = toUriAuthority().toString()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(PARCEL_VERSION_MARKER)
        dest.writeString(host)
        dest.writeInt(port)
        dest.writeString(username)
        dest.writeString(domain)
        dest.writeInt(if (encrypt) 1 else 0)
    }

    companion object {
        const val DEFAULT_PORT = SMBClient.DEFAULT_PORT
        const val DEFAULT_ENCRYPT = false

        /**
         * Saved servers used to be parcelled by `@Parcelize` starting with the host name, whose
         * length is written as a non-negative int (or -1 for null). A negative marker that is
         * not -1 tells the two layouts apart.
         */
        private const val PARCEL_VERSION_MARKER = -0x536D6202

        @JvmField
        val CREATOR = object : Parcelable.Creator<Authority> {
            override fun createFromParcel(source: Parcel): Authority {
                val startPosition = source.dataPosition()
                val hasMarker = source.readInt() == PARCEL_VERSION_MARKER
                if (!hasMarker) {
                    source.setDataPosition(startPosition)
                }
                val host = source.readString()!!
                val port = source.readInt()
                val username = source.readString()!!
                val domain = source.readString()
                val encrypt = if (hasMarker) source.readInt() != 0 else DEFAULT_ENCRYPT
                return Authority(host, port, username, domain, encrypt)
            }

            override fun newArray(size: Int): Array<Authority?> = arrayOfNulls(size)
        }
    }
}
