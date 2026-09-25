/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileModeBit.GROUP_EXECUTE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.GROUP_READ
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OTHERS_EXECUTE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OTHERS_READ
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_EXECUTE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_READ
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_WRITE
import org.junit.Assert.assertEquals
import org.junit.Test

/** What an uppercase X in [SetFileModeJob] sets on a file, given the mode it has now. */
class SetFileModeUppercaseXTest {
    private val requested = setOf(
        OWNER_READ,
        OWNER_WRITE,
        OWNER_EXECUTE,
        GROUP_READ,
        GROUP_EXECUTE,
        OTHERS_READ,
        OTHERS_EXECUTE
    )

    @Test
    fun executeIsKeptOnlyForTheClassesThatAlreadyHaveIt() {
        assertEquals(
            setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, OTHERS_READ),
            requested.withExecuteOnlyWhereSet(setOf(OWNER_READ, OWNER_EXECUTE))
        )
    }

    @Test
    fun aFileThatAlreadyHasEveryExecuteBitKeepsThemAll() {
        assertEquals(
            requested,
            requested.withExecuteOnlyWhereSet(setOf(OWNER_EXECUTE, GROUP_EXECUTE, OTHERS_EXECUTE))
        )
    }

    @Test
    fun aFileWithoutAPosixModeGetsNoExecuteBitInsteadOfCrashing() {
        assertEquals(
            setOf<PosixFileModeBit>(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ),
            requested.withExecuteOnlyWhereSet(null)
        )
    }
}
