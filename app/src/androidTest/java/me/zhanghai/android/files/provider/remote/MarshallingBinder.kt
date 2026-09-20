/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.remote

import android.os.IBinder
import android.os.IInterface

/**
 * Hides [delegate]'s local interface, so that `Stub.asInterface()` hands back a proxy instead of
 * the stub itself and every argument and result really goes through a [android.os.Parcel], the
 * way it does when the file service runs in another process. The transaction is still delivered
 * synchronously to `onTransact()` in this process, so the test needs no second process.
 */
class MarshallingBinder(private val delegate: IBinder) : IBinder by delegate {
    override fun queryLocalInterface(descriptor: String): IInterface? = null
}
