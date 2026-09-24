/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.attribute.PosixFilePermission
import me.zhanghai.android.files.provider.common.PosixFileModeBit.GROUP_EXECUTE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.GROUP_READ
import me.zhanghai.android.files.provider.common.PosixFileModeBit.GROUP_WRITE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OTHERS_EXECUTE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OTHERS_READ
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OTHERS_WRITE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_EXECUTE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_READ
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_WRITE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.SET_GROUP_ID
import me.zhanghai.android.files.provider.common.PosixFileModeBit.SET_USER_ID
import me.zhanghai.android.files.provider.common.PosixFileModeBit.STICKY
import me.zhanghai.android.files.util.enumSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The mode string is what the file properties screen shows, so it has to read like `ls -l`:
 * three `rwx` triplets, with the set-user-ID, set-group-ID and sticky bits taking the place of
 * the execute character, in upper case when the file is not executable.
 */
class PosixFileModeTest {
    @Test
    fun aModeReadsLikeTheOutputOfLs() {
        assertEquals("---------", modeOf().toModeString())
        assertEquals(
            "rw-r--r--",
            modeOf(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ)
                .toModeString()
        )
        assertEquals(
            "rwxr-xr-x",
            modeOf(
                OWNER_READ,
                OWNER_WRITE,
                OWNER_EXECUTE,
                GROUP_READ,
                GROUP_EXECUTE,
                OTHERS_READ,
                OTHERS_EXECUTE
            ).toModeString()
        )
        assertEquals(
            "rwxrwxrwx",
            modeOf(
                OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
                OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE
            ).toModeString()
        )
    }

    @Test
    fun theSpecialBitsTakeTheExecuteCharacterOfTheirTriplet() {
        assertEquals(
            "rwsr-sr-t",
            modeOf(
                OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, SET_USER_ID, GROUP_READ, GROUP_EXECUTE,
                SET_GROUP_ID, OTHERS_READ, OTHERS_EXECUTE, STICKY
            ).toModeString()
        )
    }

    @Test
    fun aSpecialBitWithoutExecuteIsUpperCase() {
        assertEquals(
            "rwSr-Sr-T",
            modeOf(
                OWNER_READ,
                OWNER_WRITE,
                SET_USER_ID,
                GROUP_READ,
                SET_GROUP_ID,
                OTHERS_READ,
                STICKY
            ).toModeString()
        )
    }

    @Test
    fun permissionsAndModeBitsAreTheSameThingUnderTwoNames() {
        val permissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_EXECUTE
        )
        val mode = permissions.toMode()
        assertEquals(modeOf(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_EXECUTE), mode)
        assertEquals(permissions, mode.toPermissions())
    }

    @Test
    fun aSpecialBitIsNotAPermission() {
        assertThrows(UnsupportedOperationException::class.java) {
            modeOf(SET_USER_ID).toPermissions()
        }
    }

    @Test
    fun aModeIsPassedAsAFileAttributeOfItsOwnName() {
        val mode = modeOf(OWNER_READ, OWNER_WRITE)
        val attribute = mode.toAttribute()
        assertEquals("posix:mode", attribute.name())
        assertEquals(mode, PosixFileMode.fromAttribute(attribute))
        assertEquals(mode, PosixFileMode.fromAttributes(arrayOf(attribute)))
        // Nothing was asked for, so there is no mode to create the file with.
        assertEquals(null, PosixFileMode.fromAttributes(emptyArray()))
    }

    @Test
    fun anAttributeOfAnotherNameIsRefused() {
        val attribute = object : java8.nio.file.attribute.FileAttribute<String> {
            override fun name(): String = "basic:size"

            override fun value(): String = "0"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            PosixFileMode.fromAttribute(attribute)
        }
    }

    private fun modeOf(vararg bits: PosixFileModeBit): Set<PosixFileModeBit> =
        enumSetOf<PosixFileModeBit>().apply { this += bits }
}
