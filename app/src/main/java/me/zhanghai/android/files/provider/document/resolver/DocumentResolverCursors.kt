/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document.resolver

import android.database.ContentObserver
import android.database.Cursor
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import me.zhanghai.android.files.provider.content.resolver.ResolverException
import me.zhanghai.android.files.util.AbstractLocalCursor

@Throws(ResolverException::class)
internal fun Cursor.waitUntilChanged() {
    try {
        runBlocking {
            suspendCancellableCoroutine<Unit> { continuation ->
                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        unregisterContentObserver(this)
                        continuation.resume(Unit)
                    }
                }
                registerContentObserver(observer)
                continuation.invokeOnCancellation {
                    try {
                        unregisterContentObserver(observer)
                        // This may be invoked when continuation is resumed but still cancelled
                        // while waiting to be dispatched.
                    } catch (ignored: IllegalStateException) {}
                }
            }
        }
    } catch (e: InterruptedException) {
        throw ResolverException(e)
    }
}

internal fun Cursor.toRowCursor(): Cursor {
    val columnNames = columnNames
    val rowValues = Array<Any?>(columnNames.size) {
        when (val type = getType(it)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(it)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(it)
            Cursor.FIELD_TYPE_STRING -> getString(it)
            Cursor.FIELD_TYPE_BLOB -> getBlob(it)
            else -> throw ResolverException("Unknown cursor column type $type")
        }
    }
    return RowCursor(columnNames, rowValues)
}

private class RowCursor(
    private val columnNames: Array<String>,
    private val rowValues: Array<Any?>
) : AbstractLocalCursor() {
    override fun getCount(): Int = 1

    override fun getColumnNames(): Array<String> = columnNames

    override fun getObject(columnIndex: Int): Any? = rowValues[columnIndex]
}
