/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * The data of the current entry of an archive being read, through [readData], which is
 * `Archive.readData`: it fills the buffer from its position towards its limit and moves the
 * position past what it read, which is nothing at the end of the entry.
 */
internal class ArchiveDataInputStream(private val readData: (ByteBuffer) -> Unit) :
    InputStream() {
    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    @Throws(IOException::class)
    override fun read(): Int {
        oneByteBuffer.clear()
        readData(oneByteBuffer)
        oneByteBuffer.flip()
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // Not cleared: that would make the buffer the whole array, and the data would land at
        // its start, overwriting what the caller had there, and could be more than len bytes.
        val buffer = ByteBuffer.wrap(b, off, len)
        if (len == 0) {
            return 0
        }
        readData(buffer)
        val count = buffer.position() - off
        return if (count > 0) count else -1
    }
}

/**
 * The data of the current entry of an archive being written, through [writeData], which is
 * `Archive.writeData`: it writes from the position of the buffer towards its limit and moves the
 * position past what it wrote.
 */
internal class ArchiveDataOutputStream(private val writeData: (ByteBuffer) -> Unit) :
    OutputStream() {
    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    @Throws(IOException::class)
    override fun write(b: Int) {
        oneByteBuffer.clear()
        oneByteBuffer.put(b.toByte())
        writeData(oneByteBuffer)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        val buffer = ByteBuffer.wrap(b, off, len)
        while (buffer.hasRemaining()) {
            writeData(buffer)
        }
    }
}
