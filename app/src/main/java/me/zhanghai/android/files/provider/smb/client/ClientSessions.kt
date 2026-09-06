/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Share
import java.io.IOException
import java.net.Inet4Address
import java.net.UnknownHostException
import jcifs.context.SingletonContext
import me.zhanghai.android.files.util.closeSafe

@Throws(ClientException::class)
internal fun Client.getSession(authority: Authority): Session {
    synchronized(sessions) {
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
            client.connect(hostAddress, authority.port)
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

@Throws(ClientException::class)
private fun resolveHostName(hostName: String): String {
    val nameServiceClient = SingletonContext.getInstance().nameServiceClient
    val addresses = try {
        nameServiceClient.getAllByName(hostName, false).mapNotNull { it.toInetAddress() }
    } catch (e: UnknownHostException) {
        throw ClientException(e)
    }
    val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
    return address.hostAddress!!
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
