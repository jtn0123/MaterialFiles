/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.Serializable
import java.net.URI
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.compat.DocumentsContractCompat
import me.zhanghai.android.files.storage.createOrLog

private const val EXTRA_PATH_URI = "${BuildConfig.APPLICATION_ID}.extra.PATH_URI"
private const val EXTRA_PATH_URI_LIST = "${BuildConfig.APPLICATION_ID}.extra.PATH_URI_LIST"
private const val EXTRA_PATH_TOKEN = "${BuildConfig.APPLICATION_ID}.extra.PATH_TOKEN"

/**
 * Whether the private path extras on this intent were put there by this app.
 *
 * Only intents this app built for itself carry [TrustedIntentToken]; the private extras on any
 * other intent are ignored, and the caller falls back to the intent data, which never grants more
 * than the caller could reach on its own.
 */
val Intent.hasTrustedPathExtras: Boolean
    get() = getStringExtra(EXTRA_PATH_TOKEN) == TrustedIntentToken.value

private fun Intent.markPathExtrasTrusted() {
    putExtra(EXTRA_PATH_TOKEN, TrustedIntentToken.value)
}

/**
 * Strips the private path extras and the token that vouches for them. Call this on any intent
 * that is handed to another app, so that the token does not leak.
 */
fun Intent.removeTrustedPathExtras() {
    removeExtra(EXTRA_PATH_URI)
    removeExtra(EXTRA_PATH_URI_LIST)
    removeExtra(EXTRA_PATH_TOKEN)
}

var Intent.extraPath: Path?
    get() {
        if (hasTrustedPathExtras) {
            val extraPathUri = getStringExtra(EXTRA_PATH_URI)
            extraPathUri?.let { URI::class.createOrLog(it) }?.let { return Paths.get(it) }
        }
        data?.toPathOrNull()?.let { return it }
        val extraInitialUri = getParcelableExtraSafe<Uri>(DocumentsContractCompat.EXTRA_INITIAL_URI)
        extraInitialUri?.toPathOrNull()?.let { return it }
        val extraAbsolutePath = getStringExtra("org.openintents.extra.ABSOLUTE_PATH")
            ?.takeIfNotEmpty()
        extraAbsolutePath?.let { return Paths.get(it) }
        return null
    }
    set(value) {
        // We cannot put Path into intent here, otherwise we will crash other apps unmarshalling it.
        // We cannot put URI into intent here either, because ShortcutInfo uses PersistableBundle
        // which doesn't support Serializable.
        putExtra(EXTRA_PATH_URI, value?.toUri()?.toString())
        markPathExtrasTrusted()
    }

val Intent.saveAsPath: Path?
    get() {
        val uri =
            when (action) {
                Intent.ACTION_VIEW -> data
                Intent.ACTION_SEND -> getParcelableExtraSafe(Intent.EXTRA_STREAM) as? Uri
                else -> null
            }
        return uri?.toPathOrNull()
    }

private fun Uri.toPathOrNull(): Path? = when (scheme) {
    ContentResolver.SCHEME_FILE, null -> path?.takeIfNotEmpty()?.let { Paths.get(it) }

    ContentResolver.SCHEME_CONTENT -> {
        val uri = URI::class.createOrLog(toString())
            // Some people use Uri.parse() without encoding their path. Let's try saving
            // them by calling the other URI constructor that encodes everything.
            ?: URI::class.createOrLog(scheme, userInfo, host, port, path, query, fragment)
        uri?.let { Paths.get(it) }
    }

    else -> null
}

var Intent.extraPathList: List<Path>
    get() {
        if (hasTrustedPathExtras) {
            getPathListExtra(EXTRA_PATH_URI_LIST)?.let { return it }
        }
        return listOfNotNull(extraPath)
    }
    set(value) {
        putPathListExtra(EXTRA_PATH_URI_LIST, value)
        markPathExtrasTrusted()
    }

/**
 * Returns null when the extra is absent or empty. Callers must check [hasTrustedPathExtras]
 * themselves; this only guards against a wrong type in the extra.
 */
fun Intent.getPathListExtra(name: String): List<Path>? {
    @Suppress("DEPRECATION")
    val pathUris = (getSerializableExtra(name) as? List<*>)
        ?.filterIsInstance<URI>()
        ?.takeIfNotEmpty()
        ?: return null
    return pathUris.map { Paths.get(it) }
}

fun Intent.putPathListExtra(name: String, paths: List<Path>) {
    // We cannot put Path into intent here, otherwise we will crash other apps unmarshalling it.
    val pathUris = paths.map { it.toUri() }
    putExtra(name, pathUris as Serializable)
}
