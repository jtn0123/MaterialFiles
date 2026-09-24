/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxPathAccessTest {
    private val volume = File("/storage/emulated/0")

    private fun File.isAccessible(
        isAttributeAccess: Boolean = false,
        isObbAccessAllowed: Boolean = false
    ): Boolean = isAccessibleInStorageVolume(volume, isAttributeAccess, PACKAGE) {
        isObbAccessAllowed
    }

    @Test
    fun ordinaryFilesAreAccessible() {
        assertTrue(File(volume, "Movies/clip.mp4").isAccessible())
        assertTrue(File(volume, "Android").isAccessible())
        assertTrue(File(volume, "Android/media/other.app").isAccessible())
    }

    @Test
    fun onlyTheAppsOwnDataDirectoryIsAccessible() {
        assertTrue(File(volume, "Android/data/$PACKAGE/files/a").isAccessible())
        assertFalse(File(volume, "Android/data/other.app/files/a").isAccessible())
        assertFalse(File(volume, "Android/data").isAccessible())
    }

    @Test
    fun obbAccessAllowedOpensUpEveryObbDirectory() {
        val otherObb = File(volume, "Android/obb/other.app/main.obb")

        assertFalse(otherObb.isAccessible())
        assertTrue(otherObb.isAccessible(isObbAccessAllowed = true))
        assertTrue(File(volume, "Android/obb/$PACKAGE/main.obb").isAccessible())
    }

    @Test
    fun obbAccessAllowedDoesNotOpenUpOtherAppsData() {
        assertFalse(
            File(volume, "Android/data/other.app").isAccessible(isObbAccessAllowed = true)
        )
    }

    @Test
    fun obbAccessIsOnlyAskedForInsideAndroidObb() {
        var asked = 0
        val isAccessible = { file: File ->
            file.isAccessibleInStorageVolume(volume, false, PACKAGE) {
                ++asked
                true
            }
        }

        isAccessible(File(volume, "Movies/clip.mp4"))
        isAccessible(File(volume, "Android/data/other.app"))
        assertEquals(0, asked)
        isAccessible(File(volume, "Android/obb/other.app"))
        assertEquals(1, asked)
    }

    @Test
    fun attributeAccessLooksAtTheEntryInTheParentDirectory() {
        // Android/data's own entry is in Android, so its attributes can be read even though its
        // contents can't be listed.
        val androidData = File(volume, "Android/data")
        assertTrue(androidData.isAccessible(isAttributeAccess = true))
        assertFalse(androidData.isAccessible())

        assertFalse(File(volume, "Android/data/other.app").isAccessible(true))

        assertFalse(File(volume, "Android/data/other.app/files").isAccessible(true))
        assertTrue(File(volume, "Android/data/$PACKAGE/files").isAccessible(true))
        assertFalse(File(volume, "Android/obb/other.app/main.obb").isAccessible(true))
        assertTrue(
            File(volume, "Android/obb/other.app/main.obb")
                .isAccessible(isAttributeAccess = true, isObbAccessAllowed = true)
        )
    }

    @Test
    fun attributeAccessOfTheRootUsesTheFileItself() {
        assertTrue(File("/").isAccessibleInStorageVolume(File("/"), true, PACKAGE) { false })
    }

    companion object {
        private const val PACKAGE = "me.zhanghai.android.files"
    }
}
