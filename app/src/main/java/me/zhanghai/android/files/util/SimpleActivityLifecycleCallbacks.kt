/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.app.Activity
import android.app.Application
import android.os.Bundle

interface SimpleActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityPaused(activity: Activity) {
        // No-op default; implementers override only the callbacks they need.
    }

    override fun onActivityStarted(activity: Activity) {
        // No-op default; implementers override only the callbacks they need.
    }

    override fun onActivityDestroyed(activity: Activity) {
        // No-op default; implementers override only the callbacks they need.
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // No-op default; implementers override only the callbacks they need.
    }

    override fun onActivityStopped(activity: Activity) {
        // No-op default; implementers override only the callbacks they need.
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // No-op default; implementers override only the callbacks they need.
    }

    override fun onActivityResumed(activity: Activity) {
        // No-op default; implementers override only the callbacks they need.
    }
}
