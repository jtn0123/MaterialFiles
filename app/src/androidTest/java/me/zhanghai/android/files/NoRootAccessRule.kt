/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files

import androidx.test.platform.app.InstrumentationRegistry
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import org.junit.rules.ExternalResource

/**
 * Keeps the app off the root service while a test runs.
 *
 * A file outside a storage volume, which is where tests put theirs, is otherwise read through
 * root, and an emulator has none.
 */
class NoRootAccessRule : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var previousStrategy: RootStrategy? = null

    override fun before() {
        instrumentation.runOnMainSync {
            previousStrategy = Settings.ROOT_STRATEGY.value
            Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
        }
    }

    override fun after() {
        instrumentation.runOnMainSync {
            previousStrategy?.let { Settings.ROOT_STRATEGY.putValue(it) }
        }
    }
}
