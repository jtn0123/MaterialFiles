/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.nio.ByteBuffer
import java8.nio.channels.SeekableByteChannel

fun DelegateSeekableByteChannel(channel: SeekableByteChannel): SeekableByteChannel =
    if (channel is ForceableChannel) {
        DelegateForceableSeekableByteChannel(channel)
    } else {
        DelegateNonForceableSeekableByteChannel(channel)
    }

open class DelegateNonForceableSeekableByteChannel(channel: SeekableByteChannel) :
    BaseDelegateSeekableByteChannel(channel) {
    init {
        require(channel !is ForceableChannel) {
            "Use DelegateForceableSeekableByteChannel for channels that are ForceableChannel"
        }
    }
}

open class DelegateForceableSeekableByteChannel(private val channel: SeekableByteChannel) :
    BaseDelegateSeekableByteChannel(channel),
    ForceableChannel {
    init {
        require(channel is ForceableChannel) {
            "Use DelegateNonForceableSeekableByteChannel for channels that aren't ForceableChannel"
        }
    }

    override fun force(metaData: Boolean) {
        (channel as ForceableChannel).force(metaData)
    }
}

abstract class BaseDelegateSeekableByteChannel internal constructor(
    private val channel: SeekableByteChannel
) : SeekableByteChannel by channel {
    // Overridden so that the caller keeps this channel, and not the one behind it.
    @Throws(IOException::class)
    override fun position(newPosition: Long): SeekableByteChannel {
        channel.position(newPosition)
        return this
    }

    @Throws(IOException::class)
    override fun truncate(size: Long): SeekableByteChannel {
        channel.truncate(size)
        return this
    }
}
