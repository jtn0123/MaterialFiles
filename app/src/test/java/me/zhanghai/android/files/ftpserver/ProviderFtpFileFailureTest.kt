/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import me.zhanghai.android.files.provider.common.FailingFileSystem
import me.zhanghai.android.files.util.RecordedWarning
import me.zhanghai.android.files.util.recordWarnings
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The FTP server shares whatever the user picked, which can be a folder on a server that stops
 * answering. A client then gets a failed command or an empty answer, and the app says in its log
 * what failed where, rather than the server thread dying.
 */
class ProviderFtpFileFailureTest {
    private val fileSystem = FailingFileSystem(setOf("/share"))

    private val user = BaseUser().apply {
        name = "tester"
        authorities = listOf(WritePermission())
    }

    private fun file(name: String): ProviderFtpFile =
        ProviderFtpFile(fileSystem.getPath("/share", name), fileSystem.getPath(name), user)

    private fun assertLogged(operation: String, warnings: List<RecordedWarning>) {
        val warning = warnings.single()
        assertEquals("ProviderFtpFile", warning.tag)
        assertEquals(operation, warning.operation)
        assertEquals("Connection reset", warning.throwable.message)
    }

    @Test
    fun attributesThatCannotBeReadFallBackToPlaceholders() {
        val file = file("movie.mp4")
        lateinit var owner: String
        assertLogged(
            "Get the owner name of /share/movie.mp4",
            recordWarnings { owner = file.ownerName }
        )
        assertEquals("user", owner)
        lateinit var group: String
        assertLogged(
            "Get the group name of /share/movie.mp4",
            recordWarnings { group = file.groupName }
        )
        assertEquals("group", group)
        var size = -1L
        assertLogged("Get the size of /share/movie.mp4", recordWarnings { size = file.size })
        assertEquals(0, size)
        var lastModified = -1L
        assertLogged(
            "Get the last modified time of /share/movie.mp4",
            recordWarnings { lastModified = file.lastModified }
        )
        assertEquals(0, lastModified)
    }

    @Test
    fun changesThatFailAreRefusedAndLogged() {
        val file = file("movie.mp4")
        var isChanged = true
        assertLogged(
            "Set the last modified time of /share/movie.mp4",
            recordWarnings { isChanged = file.setLastModified(1_000) }
        )
        assertFalse(isChanged)
        isChanged = true
        assertLogged(
            "Create the directory /share/movie.mp4",
            recordWarnings { isChanged = file.mkdir() }
        )
        assertFalse(isChanged)
        isChanged = true
        assertLogged("Delete /share/movie.mp4", recordWarnings { isChanged = file.delete() })
        assertFalse(isChanged)
        isChanged = true
        assertLogged(
            "Move /share/movie.mp4 to /share/renamed.mp4",
            recordWarnings { isChanged = file.move(file("renamed.mp4")) }
        )
        assertFalse(isChanged)
    }

    @Test
    fun aListingThatFailsIsNoListing() {
        val directory = file("folder")
        var files: List<ProviderFtpFile>? = emptyList()
        assertLogged(
            "List the files of /share/folder",
            recordWarnings { files = directory.listFiles() }
        )
        assertNull(files)
    }
}
