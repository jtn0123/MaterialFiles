/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import android.system.OsConstants
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** POSIX modes as integers, file types from a mode, and users and groups as principals. */
class PosixTypesTest {
    @Test
    fun everyModeBitSurvivesARoundTripThroughAnInteger() {
        for (bit in PosixFileModeBit.entries) {
            assertEquals(bit.name, setOf(bit), PosixFileMode.fromInt(setOf(bit).toInt()))
        }
        val all = PosixFileModeBit.entries.toSet()
        assertEquals(0b111_111_111_111, all.toInt())
        assertEquals(all, PosixFileMode.fromInt(0b111_111_111_111))
        assertEquals(emptySet<PosixFileModeBit>(), PosixFileMode.fromInt(0))
    }

    @Test
    fun theFileTypeBitsOfAModeAreNotModeBits() {
        val mode = OsConstants.S_IFDIR or OsConstants.S_ISVTX or 0b111_101_101
        assertEquals(0b1_111_101_101, PosixFileMode.fromInt(mode).toInt())
    }

    @Test
    fun theDefaultsAreTheUsualOctalModes() {
        assertEquals(0b111_101_101, PosixFileMode.DIRECTORY_DEFAULT.toInt())
        assertEquals(0b110_100_100, PosixFileMode.FILE_DEFAULT.toInt())
        assertEquals(0b111_111_111, PosixFileMode.SYMBOLIC_LINK_DEFAULT.toInt())
        assertEquals(0b111_111_111, PosixFileMode.CREATE_DIRECTORY_DEFAULT.toInt())
        assertEquals(0b110_110_110, PosixFileMode.CREATE_FILE_DEFAULT.toInt())
    }

    @Test
    fun everyPermissionIsAModeBitAndBack() {
        val permissions = PosixFilePermission.entries.toSet()
        val mode = permissions.toMode()
        assertEquals(9, mode.size)
        assertEquals(permissions, mode.toPermissions())
        for (permission in PosixFilePermission.entries) {
            assertEquals(permission.name, permission.name, setOf(permission).toMode().single().name)
        }
    }

    @Test
    fun anAttributeWhoseValueIsNotAModeIsRefused() {
        val attribute = object : FileAttribute<String> {
            override fun name(): String = "posix:mode"

            override fun value(): String = "rwx"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            PosixFileMode.fromAttribute(attribute)
        }
        assertNull(PosixFileMode.fromAttributes(emptyArray()))
        val mode = setOf(PosixFileModeBit.OWNER_READ)
        // The last attribute given wins.
        assertEquals(
            mode,
            PosixFileMode.fromAttributes(
                arrayOf(PosixFileMode.FILE_DEFAULT.toAttribute(), mode.toAttribute())
            )
        )
    }

    @Test
    fun theFileTypeComesFromTheTypeBitsOfAMode() {
        for (type in PosixFileType.entries) {
            assertEquals(type, PosixFileType.fromMode(type.mode or 0b110_100_100))
        }
        assertEquals(PosixFileType.UNKNOWN, PosixFileType.fromMode(0b111_111_111))
        assertEquals(PosixFileType.UNKNOWN, PosixFileType.fromMode(0xE000))
    }

    @Test
    fun principalsAreEqualByIdAndName() {
        val user = PosixUser(1000, "user".toByteString())
        assertEquals(user, PosixUser(1000, "user".toByteString()))
        assertEquals(user.hashCode(), PosixUser(1000, "user".toByteString()).hashCode())
        assertEquals(user, user)
        assertNotEquals(user, PosixUser(1000, null))
        assertNotEquals(user, PosixUser(1001, "user".toByteString()))
        // A group is not a user even with the same id and name.
        assertFalse(user.equals(PosixGroup(1000, "user".toByteString())))
        assertFalse(user.equals(null))
        assertEquals("user", user.name)
        assertNull(PosixGroup(0, null).name)
        assertEquals(0, PosixGroup(0, null).id)
    }
}
