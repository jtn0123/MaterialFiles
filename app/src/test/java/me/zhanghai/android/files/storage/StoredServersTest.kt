/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.ftp.client.Authority as FtpAuthority
import me.zhanghai.android.files.provider.ftp.client.Mode
import me.zhanghai.android.files.provider.ftp.client.Protocol
import me.zhanghai.android.files.provider.ftp.createFtpRootPath
import me.zhanghai.android.files.provider.sftp.client.Authority as SftpAuthority
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication
import me.zhanghai.android.files.provider.sftp.createSftpRootPath
import me.zhanghai.android.files.provider.smb.client.Authority as SmbAuthority
import me.zhanghai.android.files.provider.smb.createSmbRootPath
import me.zhanghai.android.files.provider.webdav.client.Authority as WebDavAuthority
import me.zhanghai.android.files.provider.webdav.client.PasswordAuthentication as WebDavPasswordAuthentication
import me.zhanghai.android.files.provider.webdav.client.Protocol as WebDavProtocol
import me.zhanghai.android.files.provider.webdav.createWebDavRootPath
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A folder on a server that turned us away leads to the stored server whose password to fix. */
class StoredServersTest {
    private val smbAuthority = SmbAuthority("nas.local", 445, "tester", null)
    private val sftpAuthority = SftpAuthority("nas.local", 22, "tester")
    private val ftpAuthority =
        FtpAuthority(Protocol.FTP, "nas.local", 21, "tester", Mode.PASSIVE, "UTF-8")

    private val smbServer = SmbServer(1L, null, smbAuthority, "secret", "share")
    private val sftpServer =
        SftpServer(2L, null, sftpAuthority, PasswordAuthentication("secret"), "")
    private val ftpServer = FtpServer(3L, null, ftpAuthority, "secret", "")
    private val webDavAuthority = WebDavAuthority(WebDavProtocol.DAVS, "nas.local", 443, "tester")
    private val webDavServer = WebDavServer(
        4L,
        null,
        webDavAuthority,
        WebDavPasswordAuthentication("secret"),
        ""
    )
    private val deviceStorage = FileSystemRoot(null, true)

    private val storages = listOf(deviceStorage, smbServer, sftpServer, ftpServer, webDavServer)

    @Test
    fun theServerSigningInAsTheAuthorityIsFound() {
        assertSame(smbServer, findStoredServer(smbAuthority, storages))
        assertSame(sftpServer, findStoredServer(sftpAuthority, storages))
        assertSame(ftpServer, findStoredServer(ftpAuthority, storages))
        // A copy of the authority, as a path has, is just as good.
        assertSame(smbServer, findStoredServer(smbAuthority.copy(), storages))
    }

    @Test
    fun anotherUserOrAnUnknownServerIsNotFound() {
        assertNull(findStoredServer(smbAuthority.copy(username = "other"), storages))
        assertNull(findStoredServer(sftpAuthority, listOf(smbServer, ftpServer)))
        assertNull(findStoredServer("nas.local", storages))
    }

    @Test
    fun onlyServersHaveAnAuthority() {
        assertSame(smbAuthority, smbServer.serverAuthority)
        assertSame(sftpAuthority, sftpServer.serverAuthority)
        assertSame(ftpAuthority, ftpServer.serverAuthority)
        assertSame(webDavAuthority, webDavServer.serverAuthority)
        assertNull(deviceStorage.serverAuthority)
    }

    @Test
    fun aFolderOnAServerLeadsToItsStoredServer() {
        val shareFolder = smbAuthority.createSmbRootPath().resolve("share/Movies")
        assertSame(smbServer, findStoredServer(shareFolder, storages))
        assertSame(sftpServer, findStoredServer(sftpAuthority.createSftpRootPath(), storages))
        assertSame(ftpServer, findStoredServer(ftpAuthority.createFtpRootPath(), storages))
        assertSame(webDavServer, findStoredServer(webDavAuthority.createWebDavRootPath(), storages))
        // The same server signed in as someone else is not the stored one.
        val otherUser = sftpAuthority.copy(username = "other").createSftpRootPath()
        assertNull(findStoredServer(otherUser, storages))
    }

    @Test
    fun aLocalFolderIsOnNoServer() {
        val localFolder = TestPath("/storage/emulated/0")
        assertNull(findStoredServer(localFolder, storages))
    }
}
