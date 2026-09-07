/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlin.reflect.KFunction
import me.zhanghai.android.files.BuildConfig

lateinit var application: Application private set

class AppProvider : ContentProvider() {
    private val LOG_TAG = AppProvider::class.java.simpleName

    override fun onCreate(): Boolean {
        application = context as Application
        for (initializer in appInitializers) {
            val startMillis = SystemClock.elapsedRealtime()
            initializer()
            if (BuildConfig.DEBUG) {
                val name = (initializer as? KFunction<*>)?.name ?: initializer.toString()
                Log.d(LOG_TAG, "$name took ${SystemClock.elapsedRealtime() - startMillis} ms")
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException()

    override fun getType(uri: Uri): String? = throw UnsupportedOperationException()

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int = throw UnsupportedOperationException()
}
