/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import me.zhanghai.android.files.hiddenapi.RestrictedHiddenApi
import me.zhanghai.android.files.util.lazyReflectedClass
import me.zhanghai.android.files.util.lazyReflectedMethod

/*
 * @see android.os.SELinux
 * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/jb-mr1-release/core/java/android/os/SELinux.java">
 *      jb-mr1-release/SELinux.java</a>
 * @see <a href="https://android.googlesource.com/platform/prebuilts/runtime/+/master/appcompat/hiddenapi-light-greylist.txt">
 *      hiddenapi-light-greylist.txt</a>
 */
object SELinuxCompat {
    private val seLinuxClass by lazyReflectedClass("android.os.SELinux")

    @RestrictedHiddenApi
    private val nativeRestoreconMethod by lazyReflectedMethod(
        seLinuxClass,
        "native_restorecon",
        String::class.java,
        Int::class.java
    )

    fun native_restorecon(pathname: String?, flags: Int): Boolean =
        nativeRestoreconMethod.invoke(null, pathname, flags) as Boolean
}
