/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.QuotedStringUtils
import at.bitfire.dav4jvm.ResponseCallback
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.HttpException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import me.zhanghai.android.files.provider.common.DelegateOutputStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Pipe
import okio.buffer

@Throws(DavException::class, IOException::class)
fun DavResource.getCompat(accept: String, headers: Headers?): InputStream =
    get(accept, headers).also { checkStatus(it) }.body.byteStream()

@Throws(DavException::class, IOException::class)
fun DavResource.getRangeCompat(
    accept: String,
    offset: Long,
    size: Int,
    headers: Headers?
): InputStream = followRedirects {
    val request = Request.Builder().get().url(location)
    if (headers != null) {
        request.headers(headers)
    }
    request.header("Accept", accept)
    val lastIndex = offset + size - 1
    request.header("Range", "bytes=$offset-$lastIndex")
    httpClient.newCall(request.build()).execute()
}
    .also {
        checkStatus(it)
        if (it.code != HttpURLConnection.HTTP_PARTIAL) {
            throw HttpException(it)
        }
    }
    .body.byteStream()

// This doesn't follow redirects since the request body is one-shot anyway.
@Throws(DavException::class, IOException::class)
fun DavResource.putCompat(
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    headers: Map<String, String> = emptyMap()
): OutputStream {
    val pipe = Pipe(DEFAULT_BUFFER_SIZE.toLong())
    val body = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeAll(pipe.source)
        }
    }
    val builder = Request.Builder().put(body).url(location)
    if (ifETag != null) {
        // only overwrite specific version
        builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
    }
    if (ifScheduleTag != null) {
        // only overwrite specific version
        builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
    }
    if (ifNoneMatch) {
        // don't overwrite anything existing
        builder.header("If-None-Match", "*")
    }
    // Add custom headers
    for ((key, value) in headers) {
        builder.header(key, value)
    }
    val upload = PipedUpload(pipe)
    httpClient.newCall(builder.build()).enqueue(upload)
    return upload.outputStream { checkStatus(it) }
}

/**
 * The call of a streaming PUT and the stream feeding its body through [pipe].
 *
 * Once the call is over, nothing reads the pipe any more, so it is cancelled: a writer blocked on
 * a full pipe (because the server could not be reached, or refused the upload before reading all
 * of it) then fails instead of waiting forever, with the failure of the call rather than that of
 * the pipe. The response is closed after its status is checked, so that its connection goes back
 * to the pool even when the server sent a body with it.
 */
private class PipedUpload(private val pipe: Pipe) : Callback {
    @Volatile
    private var failure: IOException? = null

    @Volatile
    private var response: Response? = null

    private val latch = CountDownLatch(1)

    override fun onFailure(call: Call, e: IOException) {
        failure = e
        latch.countDown()
        pipe.cancel()
    }

    override fun onResponse(call: Call, response: Response) {
        this.response = response
        latch.countDown()
        pipe.cancel()
    }

    fun outputStream(checkStatus: (Response) -> Unit): OutputStream =
        object : DelegateOutputStream(pipe.sink.buffer().outputStream()) {
            override fun write(b: Int) {
                whenWriting { super.write(b) }
            }

            override fun write(b: ByteArray) {
                whenWriting { super.write(b) }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                whenWriting { super.write(b, off, len) }
            }

            override fun flush() {
                whenWriting { super.flush() }
            }

            override fun close() {
                val closeFailure = try {
                    super.close()
                    null
                } catch (e: IOException) {
                    e
                }
                latch.await()
                checkResult(closeFailure, checkStatus)
                closeFailure?.let { throw it }
            }

            private fun whenWriting(block: () -> Unit) {
                try {
                    block()
                } catch (e: IOException) {
                    if (latch.count == 0L) {
                        checkResult(e, checkStatus)
                    }
                    throw e
                }
            }
        }

    // Only the first write or close to fail reports why; a close after a failed write (as in `use`)
    // then does not throw the same exception again, which would be added to itself as suppressed.
    private var isResultReported = false

    private fun checkResult(pipeFailure: IOException?, checkStatus: (Response) -> Unit) {
        if (isResultReported) {
            return
        }
        isResultReported = true
        failure?.let { callFailure ->
            pipeFailure?.let { callFailure.addSuppressed(it) }
            throw callFailure
        }
        response!!.use { checkStatus(it) }
    }
}

enum class PatchSupport {
    NONE,
    APACHE,
    SABRE
}

@Throws(DavException::class, IOException::class)
fun DavResource.getPatchSupport(): PatchSupport {
    lateinit var patchSupport: PatchSupport
    options { davCapabilities, response ->
        patchSupport = when {
            response.headers["Server"]?.contains("Apache") == true &&
                "<http://apache.org/dav/propset/fs/1>" in davCapabilities ->
                PatchSupport.APACHE

            "sabredav-partialupdate" in davCapabilities -> PatchSupport.SABRE

            else -> PatchSupport.NONE
        }
    }
    return patchSupport
}

// https://sabre.io/dav/http-patch/
@Throws(DavException::class, IOException::class)
fun DavResource.patchCompat(
    buffer: ByteBuffer,
    offset: Long,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    callback: ResponseCallback
) {
    followRedirects {
        val builder = Request.Builder()
            .patch(buffer.toRequestBody("application/x-sabredav-partialupdate".toMediaType()))
            .url(location)
        val lastIndex = offset + buffer.remaining() - 1
        builder.header("X-Update-Range", "bytes=$offset-$lastIndex")
        if (ifETag != null) {
            // only overwrite specific version
            builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
        }
        if (ifScheduleTag != null) {
            // only overwrite specific version
            builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
        }
        if (ifNoneMatch) {
            // don't overwrite anything existing
            builder.header("If-None-Match", "*")
        }
        httpClient.newCall(builder.build()).execute()
    }.use { response ->
        checkStatus(response)
        callback.onResponse(response)
    }
}

@Throws(DavException::class, IOException::class)
fun DavResource.putRangeCompat(
    buffer: ByteBuffer,
    offset: Long,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    callback: ResponseCallback
) {
    followRedirects {
        val builder = Request.Builder()
            .put(buffer.toRequestBody())
            .url(location)
        val lastIndex = offset + buffer.remaining() - 1
        builder.header("Range", "bytes=$offset-$lastIndex/*")
        if (ifETag != null) {
            // only overwrite specific version
            builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
        }
        if (ifScheduleTag != null) {
            // only overwrite specific version
            builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
        }
        if (ifNoneMatch) {
            // don't overwrite anything existing
            builder.header("If-None-Match", "*")
        }
        httpClient.newCall(builder.build()).execute()
    }.use { response ->
        checkStatus(response)
        callback.onResponse(response)
    }
}

private fun ByteBuffer.toRequestBody(contentType: MediaType? = null): RequestBody {
    val contentLength = remaining().toLong()
    mark()
    return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength(): Long = contentLength

        override fun writeTo(sink: BufferedSink) {
            reset()
            sink.write(this@toRequestBody)
        }
    }
}
