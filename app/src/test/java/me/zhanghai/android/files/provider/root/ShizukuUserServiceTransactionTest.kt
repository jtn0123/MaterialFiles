/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.annotation.SuppressLint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.ShizukuApiConstants

class ShizukuUserServiceTransactionTest {
    @Test
    fun theDestroyTransactionDestroysTheService() {
        var destroyed = 0

        val handled = handleShizukuUserServiceTransaction(DESTROY) { ++destroyed }

        assertTrue(handled)
        assertEquals(1, destroyed)
    }

    @Test
    fun otherTransactionsAreLeftAlone() {
        var destroyed = 0

        assertFalse(handleShizukuUserServiceTransaction(DESTROY - 1) { ++destroyed })
        assertFalse(handleShizukuUserServiceTransaction(1) { ++destroyed })
        assertEquals(0, destroyed)
    }

    companion object {
        @SuppressLint("RestrictedApi")
        private const val DESTROY = ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy
    }
}
