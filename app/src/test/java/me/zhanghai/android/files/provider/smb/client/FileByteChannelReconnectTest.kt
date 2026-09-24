/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.nio.ByteBuffer
import java.util.EnumSet
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * An open file outlives the connection it was opened on, which SMBJ closes once it has been silent
 * for its socket timeout (a paused video). Closing the connection by hand is what that timeout
 * does, without waiting ten minutes for it.
 */
class FileByteChannelReconnectTest {
    private lateinit var client: Client

    private lateinit var authority: Authority

    @Before
    fun setUp() {
        val port = System.getProperty("material.smb.port")
        assumeNotNull("Run tools/network-tests.py to provision the SMB fixture", port)
        authority = Authority("127.0.0.1", port!!.toInt(), "test", null)
        client = Client(
            object : Authenticator {
                override fun getPassword(authority: Authority) = "test-only"
            }
        )
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) {
            client.clientFor(authority).close()
        }
    }

    @Test
    fun aChannelKeepsReadingAfterItsConnectionClosed() {
        val path = TestPath(authority, Client.Path.SharePath("test", "reconnect-read.bin"))
        // Larger than the first read, so that the second one has to go to the server.
        val content = Random(1).nextBytes(1024 * 1024)
        openChannel(path, setOf(AccessMask.GENERIC_WRITE), SMB2CreateDisposition.FILE_OVERWRITE_IF)
            .use { it.write(ByteBuffer.wrap(content)) }

        openChannel(path, setOf(AccessMask.GENERIC_READ), SMB2CreateDisposition.FILE_OPEN).use {
            val start = ByteBuffer.allocate(16)
            it.read(start)
            assertArrayEquals(content.copyOfRange(0, 16), start.array())

            val connection = client.getSession(authority).connection
            connection.close()
            assertFalse(connection.isConnected)

            it.position(content.size - 16L)
            val end = ByteBuffer.allocate(16)
            it.read(end)
            assertArrayEquals(content.copyOfRange(content.size - 16, content.size), end.array())
        }
    }

    @Test
    fun aChannelKeepsWritingAfterItsConnectionClosedWithoutStartingOver() {
        val path = TestPath(authority, Client.Path.SharePath("test", "reconnect-write.txt"))
        openChannel(
            path,
            setOf(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
            SMB2CreateDisposition.FILE_OVERWRITE_IF
        ).use {
            it.write(ByteBuffer.wrap("first ".toByteArray()))
            client.getSession(authority).connection.close()
            // Reopening must not replace the file again, which would lose the first write.
            it.write(ByteBuffer.wrap("second".toByteArray()))
        }

        val read = openChannel(
            path,
            setOf(AccessMask.GENERIC_READ),
            SMB2CreateDisposition.FILE_OPEN
        )
            .use {
                val buffer = ByteBuffer.allocate(64)
                it.read(buffer)
                String(buffer.array(), 0, buffer.position())
            }
        assertEquals("first second", read)
    }

    private fun openChannel(
        path: Client.Path,
        desiredAccess: Set<AccessMask>,
        createDisposition: SMB2CreateDisposition
    ) = client.openByteChannel(
        path,
        desiredAccess,
        EnumSet.noneOf(FileAttributes::class.java),
        SMB2ShareAccess.ALL,
        createDisposition,
        EnumSet.noneOf(SMB2CreateOptions::class.java),
        false
    )

    private data class TestPath(
        override val authority: Authority,
        override val sharePath: Client.Path.SharePath?
    ) : Client.Path {
        override fun resolve(other: String): Client.Path = throw UnsupportedOperationException()
    }
}
