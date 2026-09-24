/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path as Java8Path
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedOutputStream
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedSeekableByteChannel
import me.zhanghai.android.files.util.closeSafe
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException

/**
 * The SFTP sessions of the app, one per [Authority], created on demand with credentials from
 * [authenticator] and host keys checked against [hostKeyStore]. Owned by the file system
 * provider; a test constructs its own with fakes.
 */
class Client(internal val authenticator: Authenticator, internal val hostKeyStore: HostKeyStore) {
    private val clients = ConcurrentHashMap<Authority, SFTPClient>()

    // One lock per authority: connecting and authenticating to a host that does not answer
    // takes until the timeout, and must not hold up the sessions to every other host.
    private val clientLocks = ConcurrentHashMap<Authority, Any>()

    private val directoryFileAttributesCache =
        Collections.synchronizedMap(WeakHashMap<Path, FileAttributes>())

    // Whether the server at an authority takes the arguments of a symbolic link the other way
    // round, filled in when the session to it is opened.
    private val reversedSymlinkArguments = ConcurrentHashMap<Authority, Boolean>()

    @Throws(ClientException::class)
    fun access(path: Path, flags: Set<OpenMode>) {
        val file = open(path, flags, FileAttributes.EMPTY)
        try {
            file.close()
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun lstat(path: Path): FileAttributes {
        val client = getClient(path.authority)
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                return it.also { directoryFileAttributesCache -= path }
            }
        }
        return try {
            client.lstat(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun mkdir(path: Path, attributes: FileAttributes) {
        val client = getClient(path.authority)
        try {
            client.sftpEngine.makeDir(path.remotePath, attributes)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    @Throws(ClientException::class)
    private fun open(path: Path, flags: Set<OpenMode>, attributes: FileAttributes): RemoteFile {
        val client = getClient(path.authority)
        return try {
            client.open(path.remotePath, flags, attributes)
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    /**
     * A write-only stream that pipelines its writes; use it instead of
     * [openByteChannel]`.newOutputStream()` when the file is only written from start to end.
     */
    @Throws(ClientException::class)
    fun openOutputStream(
        path: Path,
        flags: Set<OpenMode>,
        attributes: FileAttributes
    ): OutputStream {
        val file = open(path, flags, attributes)
        return NotifyEntryModifiedOutputStream(
            PipelinedOutputStream(RemoteFileWriteTarget(file)),
            path as Java8Path
        )
    }

    @Throws(ClientException::class)
    fun openByteChannel(
        path: Path,
        flags: Set<OpenMode>,
        attributes: FileAttributes
    ): SeekableByteChannel {
        val file = open(path, flags, attributes)
        return NotifyEntryModifiedSeekableByteChannel(
            FileByteChannel(file, flags.contains(OpenMode.APPEND)),
            path as Java8Path
        )
    }

    @Throws(ClientException::class)
    fun readlink(path: Path): String {
        val client = getClient(path.authority)
        return try {
            client.readlink(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun realpath(path: Path): Path {
        val client = getClient(path.authority)
        val realPath = try {
            client.canonicalize(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        return path.resolve(realPath)
    }

    @Throws(ClientException::class)
    fun remove(path: Path) {
        val attributes = lstat(path)
        val isDirectory = attributes.type == FileMode.Type.DIRECTORY
        if (isDirectory) {
            rmdir(path)
        } else {
            unlink(path)
        }
    }

    // Note that unlike POSIX rename(), this won't overwrite an existing file.
    @Throws(ClientException::class)
    fun rename(path: Path, newPath: Path) {
        if (newPath.authority != path.authority) {
            throw ClientException(
                SFTPException(Response.StatusCode.FAILURE, "Paths aren't on the same authority")
            )
        }
        val client = getClient(path.authority)
        try {
            client.rename(path.remotePath, newPath.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        directoryFileAttributesCache -= newPath
        LocalWatchService.onEntryDeleted(path as Java8Path)
        LocalWatchService.onEntryCreated(newPath as Java8Path)
    }

    @Throws(ClientException::class)
    fun rmdir(path: Path) {
        val client = getClient(path.authority)
        try {
            client.rmdir(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun scandir(path: Path): List<Path> {
        val client = getClient(path.authority)
        val files: List<RemoteResourceInfo> = try {
            client.ls(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        return files.map { file ->
            // The attributes here are from lstat().
            // https://github.com/openssh/openssh-portable/blob/71241fc05db4bbb11bb29340b44b92e2575373d8/sftp-server.c#L1110
            path.resolve(file.name).also { directoryFileAttributesCache[it] = file.attributes }
        }
    }

    @Throws(ClientException::class)
    fun setstat(path: Path, attributes: FileAttributes) {
        val client = getClient(path.authority)
        try {
            client.setattr(path.remotePath, attributes)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryModified(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun stat(path: Path): FileAttributes {
        val client = getClient(path.authority)
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                if (it.type != FileMode.Type.SYMLINK) {
                    return it.also { directoryFileAttributesCache -= path }
                }
            }
        }
        return try {
            client.stat(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun symlink(link: Path, target: String) {
        val authority = link.authority
        val client = getClient(authority)
        try {
            if (reversedSymlinkArguments[authority] == true) {
                client.symlink(target, link.remotePath)
            } else {
                client.symlink(link.remotePath, target)
            }
        } catch (e: IOException) {
            throw ClientException(e)
        }
        LocalWatchService.onEntryCreated(link as Java8Path)
    }

    @Throws(ClientException::class)
    fun unlink(path: Path) {
        val client = getClient(path.authority)
        try {
            client.rm(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(ClientException::class)
    private fun getClient(authority: Authority): SFTPClient {
        synchronized(clientLocks.getOrPut(authority) { Any() }) {
            var client = clients[authority]
            if (client != null) {
                if (client.sftpEngine.subsystem.isOpen) {
                    return client
                } else {
                    client.closeSafe()
                    clients -= authority
                }
            }
            val authentication = authenticator.getAuthentication(authority)
                ?: throw ClientException("No authentication found for $authority")
            val hostKeyVerifier =
                TrustOnFirstUseHostKeyVerifier(authority.host, authority.port, hostKeyStore)
            SecurityProviderHelper.ensureInitialized()
            val sshClient = SSHClient().apply { addHostKeyVerifier(hostKeyVerifier) }
            try {
                sshClient.connect(authority.host, authority.port)
            } catch (e: IOException) {
                sshClient.closeSafe()
                // sshj reports a refused host key as a generic transport error; ours has the
                // fingerprints the user needs to decide.
                throw ClientException(hostKeyVerifier.hostKeyChangedException ?: e)
            }
            try {
                sshClient.auth(authority.username, authentication.toAuthMethod())
            } catch (e: UserAuthException) {
                sshClient.closeSafe()
                throw ClientException(e)
            } catch (e: TransportException) {
                sshClient.closeSafe()
                throw ClientException(e)
            }
            // OpenSSH reads the two paths of a symbolic link in the order opposite to the one
            // the draft everything else here follows asks for, and says so in its own PROTOCOL
            // file; sshj sends what the draft says. Almost every SFTP server is OpenSSH, and on
            // one the link would otherwise be created where its target should be, pointing back
            // at it.
            reversedSymlinkArguments[authority] =
                OPENSSH_IDENTIFICATION in sshClient.transport.serverVersion
            client = sshClient.newSFTPClient()
            clients[authority] = client
            return client
        }
    }

    private companion object {
        const val OPENSSH_IDENTIFICATION = "OpenSSH"
    }

    interface Path {
        val authority: Authority
        val remotePath: String
        fun resolve(other: String): Path
    }
}
