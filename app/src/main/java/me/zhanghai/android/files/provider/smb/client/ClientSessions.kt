/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Share
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import jcifs.context.SingletonContext
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.logWarning

// Without a socket timeout a connection that died silently (the server went away, a NAT forgot
// us) stays "connected" in SMBJ forever and every request on it waits out its own timeout. SMB is
// silent while idle, so this also closes a connection nobody used for that long (which a paused
// video or a watched folder can be), hence a generous value; the next request connects again, and
// an open file is opened again on the new connection (see FileByteChannel).
private const val SO_TIMEOUT_MINUTES = 10L

internal fun newSmbConfig(encryptData: Boolean): SmbConfig = SmbConfig.builder()
    .withEncryptData(encryptData)
    .withSoTimeout(SO_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    .build()

@Throws(ClientException::class)
internal fun Client.getSession(authority: Authority): Session {
    synchronized(sessionLocks.getOrPut(authority) { Any() }) {
        var session = sessions[authority]
        if (session != null) {
            val connection = session.connection
            if (connection.isConnected) {
                return session
            } else {
                session.closeSafe()
                connection.closeSafe()
                sessions -= authority
            }
        }
        val password = authenticator.getPassword(authority)
            ?: throw ClientException("No password found for $authority")
        val hostAddress = resolveHostName(authority.host)
        val connection = try {
            clientFor(authority).connect(hostAddress, authority.port)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        val authenticationContext =
            AuthenticationContext(authority.username, password.toCharArray(), authority.domain)
        session = try {
            connection.authenticate(authenticationContext)
        } catch (e: SMBRuntimeException) {
            // We need to close the connection here, otherwise future authentications reusing it
            // will receive an exception about no available credits.
            connection.closeSafe()
            throw ClientException(e)
            // TODO: kotlinc: Type mismatch: inferred type is Session? but TypeVariable(V) was
            //  expected
            //}
        }!!
        sessions[authority] = session
        return session
    }
}

/**
 * Runs [block] with the session for [authority]. Servers expire sessions (and tear down trees)
 * on their own schedule while the connection stays up, so when [block] fails because the server
 * no longer knows the session, the session is dropped from the cache and [block] runs once more
 * with a fresh one.
 *
 * With [isIdempotent], [block] also runs once more when the connection under the session died
 * (see [ClientException.isConnectionGone]): the connection is closed so that the retry connects
 * again. Only an operation that is safe to repeat may ask for this, since the failed attempt may
 * have reached the server before the connection went.
 */
@Throws(ClientException::class)
internal inline fun <T> Client.withSession(
    authority: Authority,
    isIdempotent: Boolean = false,
    block: (Session) -> T
): T = retryWithFreshSession(
    { getSession(authority) },
    { evictSession(authority, it) },
    isIdempotent,
    { dropConnection(authority, it) },
    block
)

/** [withSession], then the disk share that [path] is on. */
@Throws(ClientException::class)
internal inline fun <T> Client.withDiskShare(
    path: Client.Path,
    isIdempotent: Boolean = false,
    block: (DiskShare, Client.Path.SharePath) -> T
): T {
    val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
    return withSession(path.authority, isIdempotent) {
        block(getDiskShare(it, sharePath.name), sharePath)
    }
}

@Throws(ClientException::class)
internal inline fun <S, T> retryWithFreshSession(
    getSession: () -> S,
    evictSession: (S) -> Unit,
    isIdempotent: Boolean = false,
    dropConnection: (S) -> Unit = {},
    block: (S) -> T
): T {
    val session = getSession()
    return try {
        block(session)
    } catch (e: ClientException) {
        when {
            e.isSessionGone -> evictSession(session)
            isIdempotent && e.isConnectionGone -> dropConnection(session)
            else -> throw e
        }
        block(getSession())
    }
}

// The session is only forgotten, not logged off: an expired one is already gone on the server, and
// when only a tree went away, or the failure came from the other session of a copy, logging off
// would cut off the files still open through it. SMBJ lets go of it with its connection.
internal fun Client.evictSession(authority: Authority, session: Session) {
    sessions.remove(authority, session)
}

// SMBJ hands out a connection it still thinks is up again, so a dead one has to be closed for the
// retry to get a new one. Forced: a polite close logs every session off first, and each logoff
// would wait out the timeout on a dead socket. Files still open on it fail and open themselves
// again on the next connection (see FileByteChannel).
internal fun Client.dropConnection(authority: Authority, session: Session) {
    evictSession(authority, session)
    try {
        session.connection.close(true)
    } catch (e: IOException) {
        e.logWarning("SmbClient", "Close the dead connection to $authority")
    }
}

@Throws(ClientException::class)
private fun resolveHostName(hostName: String): String {
    val nameServiceClient = SingletonContext.getInstance().nameServiceClient
    val addresses = try {
        nameServiceClient.getAllByName(hostName, false).mapNotNull { it.toInetAddress() }
    } catch (e: UnknownHostException) {
        throw ClientException(e)
    }
    return pickHostAddress(hostName, addresses)
}

// An IPv4 address is preferred. A name service may answer with nothing usable (no address it could
// convert), which is reported as an unknown host rather than a crash.
@Throws(ClientException::class)
internal fun pickHostAddress(hostName: String, addresses: List<InetAddress>): String {
    val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
    return address?.hostAddress
        ?: throw ClientException(UnknownHostException("No address found for $hostName"))
}

@Throws(ClientException::class)
internal fun Client.getShare(session: Session, shareName: String): Share = try {
    session.connectShare(shareName)
} catch (e: SMBRuntimeException) {
    throw ClientException(e)
}

@Throws(ClientException::class)
internal fun Client.getDiskShare(session: Session, shareName: String): DiskShare =
    getShare(session, shareName) as? DiskShare
        ?: throw ClientException("$shareName is not a DiskShare")
