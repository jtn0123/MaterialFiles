/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.net.Uri
import android.os.Build
import android.os.Parcel
import androidx.annotation.StringRes
import androidx.core.content.edit
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.readBooleanCompat
import me.zhanghai.android.files.compat.writeBooleanCompat
import me.zhanghai.android.files.compat.writeParcelableListCompat
import me.zhanghai.android.files.file.asExternalStorageUriOrNull
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.content.ContentFileSystem
import me.zhanghai.android.files.provider.document.DocumentFileSystem
import me.zhanghai.android.files.provider.document.resolver.ExternalStorageProviderHacks
import me.zhanghai.android.files.provider.linux.LinuxFileSystem
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.provider.sftp.SftpFileSystem
import me.zhanghai.android.files.provider.smb.SmbFileSystem
import me.zhanghai.android.files.util.StableUriParceler
import me.zhanghai.android.files.util.asBase64
import me.zhanghai.android.files.util.readParcelable
import me.zhanghai.android.files.util.readParcelableListCompat
import me.zhanghai.android.files.util.toBase64
import me.zhanghai.android.files.util.toByteArray
import me.zhanghai.android.files.util.use

internal fun upgradeAppTo1_4_0() {
    migratePathSetting1_4_0(R.string.pref_key_file_list_default_directory)
    migrateSftpServersSetting1_4_0()
    migrateBookmarkDirectoriesSetting1_4_0()
    migrateRootStrategySetting1_4_0()
    migratePathSetting1_4_0(R.string.pref_key_ftp_server_home_directory)
}

private fun migratePathSetting1_4_0(@StringRes keyRes: Int) {
    val key = application.getString(keyRes)
    val oldBytes = defaultSharedPreferences.getString(key, null)?.asBase64()?.toByteArray()
        ?: return
    val newBytes = try {
        Parcel.obtain().use { newParcel ->
            Parcel.obtain().use { oldParcel ->
                oldParcel.unmarshall(oldBytes, 0, oldBytes.size)
                oldParcel.setDataPosition(0)
                newParcel.writeInt(oldParcel.readInt())
                migratePath1_4_0(oldParcel, newParcel)
            }
            newParcel.marshall()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
    defaultSharedPreferences.edit { putString(key, newBytes?.toBase64()?.value) }
}

private fun migrateBookmarkDirectoriesSetting1_4_0() {
    val key = application.getString(R.string.pref_key_bookmark_directories)
    val oldBytes = defaultSharedPreferences.getString(key, null)?.asBase64()?.toByteArray()
        ?: return
    val newBytes = try {
        Parcel.obtain().use { newParcel ->
            Parcel.obtain().use { oldParcel ->
                oldParcel.unmarshall(oldBytes, 0, oldBytes.size)
                oldParcel.setDataPosition(0)
                newParcel.writeInt(oldParcel.readInt())
                val size = oldParcel.readInt()
                newParcel.writeInt(size)
                repeat(size) {
                    newParcel.writeInt(oldParcel.readInt())
                    newParcel.writeString(oldParcel.readString())
                    newParcel.writeLong(oldParcel.readLong())
                    newParcel.writeString(oldParcel.readString())
                    migratePath1_4_0(oldParcel, newParcel)
                }
            }
            newParcel.marshall()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
    defaultSharedPreferences.edit { putString(key, newBytes?.toBase64()?.value) }
}

private fun migratePath1_4_0(oldParcel: Parcel, newParcel: Parcel) {
    val className = oldParcel.readString()
    newParcel.writeString(className)
    newParcel.writeByte(oldParcel.readByte())
    newParcel.writeBooleanCompat(oldParcel.readBooleanCompat())
    newParcel.writeParcelableListCompat(oldParcel.readParcelableListCompat<ByteString>(), 0)
    when (className) {
        "me.zhanghai.android.files.provider.archive.ArchivePath" -> {
            newParcel.writeString(oldParcel.readString())
            migratePath1_4_0(oldParcel, newParcel)
        }

        "me.zhanghai.android.files.provider.content.ContentPath" -> {
            newParcel.writeParcelable(oldParcel.readParcelable<ContentFileSystem>(), 0)
            newParcel.writeParcelable(oldParcel.readParcelable<Uri>(), 0)
        }

        "me.zhanghai.android.files.provider.document.DocumentPath" ->
            newParcel.writeParcelable(oldParcel.readParcelable<DocumentFileSystem>(), 0)

        "me.zhanghai.android.files.provider.linux.LinuxPath" -> {
            newParcel.writeParcelable(oldParcel.readParcelable<LinuxFileSystem>(), 0)
            oldParcel.readBooleanCompat()
        }

        "me.zhanghai.android.files.provider.sftp.SftpPath" ->
            newParcel.writeParcelable(oldParcel.readParcelable<SftpFileSystem>(), 0)

        "me.zhanghai.android.files.provider.smb.SmbPath" ->
            newParcel.writeParcelable(oldParcel.readParcelable<SmbFileSystem>(), 0)

        else -> throw IllegalStateException(className)
    }
}

private fun migrateSftpServersSetting1_4_0() {
    val key = application.getString(R.string.pref_key_storages)
    val oldBytes = defaultSharedPreferences.getString(key, null)?.asBase64()?.toByteArray()
        ?: return
    val newBytes = try {
        Parcel.obtain().use { newParcel ->
            Parcel.obtain().use { oldParcel ->
                oldParcel.unmarshall(oldBytes, 0, oldBytes.size)
                oldParcel.setDataPosition(0)
                newParcel.writeInt(oldParcel.readInt())
                val size = oldParcel.readInt()
                newParcel.writeInt(size)
                repeat(size) {
                    val oldPosition = oldParcel.dataPosition()
                    oldParcel.readInt()
                    when (oldParcel.readString()) {
                        "me.zhanghai.android.files.storage.SftpServer" -> {
                            newParcel.writeInt(PARCEL_VAL_PARCELABLE)
                            newParcel.writeString("me.zhanghai.android.files.storage.SftpServer")
                            val id = oldParcel.readLong()
                            newParcel.writeLong(id)
                            val customName = oldParcel.readString()
                            newParcel.writeString(customName)
                            val authorityHost = oldParcel.readString()
                            newParcel.writeString(authorityHost)
                            val authorityPort = oldParcel.readInt()
                            newParcel.writeInt(authorityPort)
                            val authenticationClassName = oldParcel.readString()
                            val authorityUsername = oldParcel.readString()
                            newParcel.writeString(authorityUsername)
                            newParcel.writeString(authenticationClassName)
                            val authenticationPasswordOrPrivateKey = oldParcel.readString()
                            newParcel.writeString(authenticationPasswordOrPrivateKey)
                            val relativePath = oldParcel.readString()
                            newParcel.writeString(relativePath)
                        }

                        else -> {
                            oldParcel.setDataPosition(oldPosition)
                            val storage = oldParcel.readValue(appClassLoader)
                            newParcel.writeValue(storage)
                        }
                    }
                }
            }
            newParcel.marshall()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
    defaultSharedPreferences.edit { putString(key, newBytes?.toBase64()?.value) }
}

private fun migrateRootStrategySetting1_4_0() {
    val key = application.getString(R.string.pref_key_root_strategy)
    val oldValue = defaultSharedPreferences.getString(key, null)?.toInt() ?: return
    val newValue = when (oldValue) {
        0 -> RootStrategy.NEVER
        3 -> RootStrategy.ALWAYS
        else -> RootStrategy.AUTOMATIC
    }.ordinal.toString()
    defaultSharedPreferences.edit { putString(key, newValue) }
}

internal fun upgradeAppTo1_5_0() {
    migrateSftpServersSetting1_5_0()
}

private fun migrateSftpServersSetting1_5_0() {
    val key = application.getString(R.string.pref_key_storages)
    val oldBytes = defaultSharedPreferences.getString(key, null)?.asBase64()?.toByteArray()
        ?: return
    val newBytes = try {
        Parcel.obtain().use { newParcel ->
            Parcel.obtain().use { oldParcel ->
                oldParcel.unmarshall(oldBytes, 0, oldBytes.size)
                oldParcel.setDataPosition(0)
                newParcel.writeInt(oldParcel.readInt())
                val size = oldParcel.readInt()
                newParcel.writeInt(size)
                repeat(size) {
                    val oldPosition = oldParcel.dataPosition()
                    oldParcel.readInt()
                    when (oldParcel.readString()) {
                        "me.zhanghai.android.files.storage.SftpServer" -> {
                            newParcel.writeInt(PARCEL_VAL_PARCELABLE)
                            newParcel.writeString("me.zhanghai.android.files.storage.SftpServer")
                            val id = oldParcel.readLong()
                            newParcel.writeLong(id)
                            val customName = oldParcel.readString()
                            newParcel.writeString(customName)
                            val authorityHost = oldParcel.readString()
                            newParcel.writeString(authorityHost)
                            val authorityPort = oldParcel.readInt()
                            newParcel.writeInt(authorityPort)
                            val authorityUsername = oldParcel.readString()
                            newParcel.writeString(authorityUsername)
                            val authenticationClassName = oldParcel.readString()
                            newParcel.writeString(authenticationClassName)
                            val authenticationPasswordOrPrivateKey = oldParcel.readString()
                            newParcel.writeString(authenticationPasswordOrPrivateKey)
                            if (authenticationClassName ==
                                "me.zhanghai.android.files.provider.sftp" +
                                ".client.PublicKeyAuthentication"
                            ) {
                                newParcel.writeString(null)
                            }
                            val relativePath = oldParcel.readString()
                            newParcel.writeString(relativePath)
                        }

                        else -> {
                            oldParcel.setDataPosition(oldPosition)
                            val storage = oldParcel.readValue(appClassLoader)
                            newParcel.writeValue(storage)
                        }
                    }
                }
            }
            newParcel.marshall()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
    defaultSharedPreferences.edit { putString(key, newBytes?.toBase64()?.value) }
}

internal fun upgradeAppTo1_6_0() {
    addViewTypePathSetting1_6_0()
}

private fun addViewTypePathSetting1_6_0() {
    val keys = pathSharedPreferences.all.keys.toSet()
    val sortOptionsKey = application.getString(R.string.pref_key_file_list_sort_options)
    val viewTypeKey = application.getString(R.string.pref_key_file_list_view_type)
    val defaultViewType = application.getString(R.string.pref_default_value_file_list_view_type)
    for (key in keys) {
        if (!key.startsWith(sortOptionsKey)) {
            continue
        }
        val newKey = key.replaceFirst(sortOptionsKey, viewTypeKey)
        if (newKey in keys) {
            continue
        }
        pathSharedPreferences.edit { putString(newKey, defaultViewType) }
    }
}

internal fun upgradeAppTo1_7_2() {
    migrateDocumentManagerShortcutSetting1_7_2()
}

private fun migrateDocumentManagerShortcutSetting1_7_2() {
    val key = application.getString(R.string.pref_key_storages)
    val oldBytes =
        defaultSharedPreferences.getString(key, null)?.asBase64()?.toByteArray() ?: return
    val newBytes =
        try {
            Parcel.obtain().use { newParcel ->
                Parcel.obtain().use { oldParcel ->
                    oldParcel.unmarshall(oldBytes, 0, oldBytes.size)
                    oldParcel.setDataPosition(0)
                    newParcel.writeInt(oldParcel.readInt())
                    readWriteLengthPrefixedValue(oldParcel, newParcel) {
                        val size = oldParcel.readInt()
                        newParcel.writeInt(size)
                        repeat(size) {
                            val oldPosition = oldParcel.dataPosition()
                            oldParcel.readInt()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                // Skip prefix length.
                                oldParcel.readInt()
                            }
                            val className = oldParcel.readString()
                            oldParcel.setDataPosition(oldPosition)
                            when (className) {
                                "me.zhanghai.android.files.storage.DocumentManagerShortcut" -> {
                                    newParcel.writeInt(oldParcel.readInt())
                                    readWriteLengthPrefixedValue(oldParcel, newParcel) {
                                        oldParcel.readString()
                                        newParcel.writeString(
                                            "me.zhanghai.android.files.storage" +
                                                ".ExternalStorageShortcut"
                                        )
                                        val id = oldParcel.readLong()
                                        newParcel.writeLong(id)
                                        val customName = oldParcel.readString()
                                        newParcel.writeString(customName)
                                        var uri = StableUriParceler.create(oldParcel)!!
                                        if (uri.asExternalStorageUriOrNull() == null) {
                                            // Reset to a valid external storage URI.
                                            uri =
                                                ExternalStorageProviderHacks
                                                    .DOCUMENT_URI_ANDROID_DATA
                                        }
                                        with(StableUriParceler) { uri.write(newParcel, 0) }
                                    }
                                }

                                else -> {
                                    val storage = oldParcel.readValue(appClassLoader)
                                    newParcel.writeValue(storage)
                                }
                            }
                        }
                    }
                }
                newParcel.marshall()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    defaultSharedPreferences.edit { putString(key, newBytes?.toBase64()?.value) }
}

private fun readWriteLengthPrefixedValue(oldParcel: Parcel, newParcel: Parcel, block: () -> Unit) {
    var lengthPosition = 0
    var startPosition = 0
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        oldParcel.readInt()
        lengthPosition = newParcel.dataPosition()
        newParcel.writeInt(-1)
        startPosition = newParcel.dataPosition()
    }
    block()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val endPosition = newParcel.dataPosition()
        newParcel.setDataPosition(lengthPosition)
        newParcel.writeInt(endPosition - startPosition)
        newParcel.setDataPosition(endPosition)
    }
}
