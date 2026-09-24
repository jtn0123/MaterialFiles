/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider

import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Sets the root strategy to [RootStrategy.NEVER] for the duration of a test, so that paths
 * outside a storage volume (the app's own data directory, which tests write into) go to the
 * local provider instead of the root one. There is no root on the emulator, and the local
 * provider is what runs for every path the user can see anyway.
 */
class NeverUseRootRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val previousStrategy = Settings.ROOT_STRATEGY.valueCompat
                setStrategy(RootStrategy.NEVER)
                try {
                    base.evaluate()
                } finally {
                    setStrategy(previousStrategy)
                }
            }
        }

    /**
     * The setting is read back through a [androidx.lifecycle.LiveData] that the shared
     * preferences listener updates on the main thread, so the new value is not visible to the
     * test thread right away.
     */
    private fun setStrategy(strategy: RootStrategy) {
        Settings.ROOT_STRATEGY.putValue(strategy)
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (Settings.ROOT_STRATEGY.valueCompat != strategy) {
            check(System.currentTimeMillis() < deadline) { "Root strategy stayed unchanged" }
            Thread.sleep(10)
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000L
    }
}
