/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.HttpUtils
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.Collections

/**
 * An in-memory WebDAV server, speaking just enough of RFC 4918 for the provider: PROPFIND with
 * depth 0 and 1, GET, HEAD, PUT, MKCOL, DELETE, MOVE and OPTIONS.
 *
 * Paths are stored without their trailing slash, the root being the empty string, so that a
 * request for `/dir` and one for `/dir/` address the same collection - which is the point of
 * [requests], where every request is recorded with the path exactly as it arrived (and, for a
 * MOVE or a ranged GET, the `Destination` path or the `Range`).
 */
internal class FakeWebDavServer(private val password: String? = null) {
    private class Entry(
        val isCollection: Boolean,
        var content: ByteArray = ByteArray(0),
        val lastModified: Instant = Instant.ofEpochSecond(1_600_000_000),
        val creationDate: String? = null
    )

    private val entries = linkedMapOf("" to Entry(true))

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Every request received, as `METHOD path`, in order. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** Requests, as `METHOD path`, answered with 403 instead of being served. */
    val refusedRequests = mutableSetOf<String>()

    /** Anything the handler itself threw; a test asserts this stays empty. */
    val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf<Throwable>())

    val port: Int
        get() = server.address.port

    fun start() {
        server.createContext("/") { handle(it) }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    fun addFile(
        path: String,
        content: String,
        lastModified: Instant = Instant.ofEpochSecond(1_600_000_000),
        creationDate: String? = HttpUtils.formatDate(Instant.ofEpochSecond(1_500_000_000))
    ) {
        entries[key(path)] = Entry(false, content.toByteArray(), lastModified, creationDate)
    }

    fun addCollection(path: String) {
        entries[key(path)] = Entry(true)
    }

    fun fileContent(path: String): String? = entries[key(path)]?.let { String(it.content) }

    fun exists(path: String): Boolean = key(path) in entries

    fun paths(): Set<String> = entries.keys.toSet()

    private fun key(path: String): String = path.removeSuffix("/")

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val destination = exchange.requestHeaders.getFirst("Destination")
            val range = exchange.requestHeaders.getFirst("Range")
            requests += "${exchange.requestMethod} $path" +
                when {
                    destination != null -> " -> ${URI(destination).path}"
                    range != null -> " $range"
                    else -> ""
                }
            val body = exchange.requestBody.readBytes()
            val key = key(path)
            when {
                password != null && !isAuthorized(exchange) -> {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"fake\"")
                    exchange.sendResponseHeaders(401, -1)
                }

                "${exchange.requestMethod} $key" in refusedRequests ->
                    exchange.sendResponseHeaders(403, -1)

                else -> dispatch(exchange, key, body)
            }
        } catch (e: Exception) {
            failures += e
            exchange.sendResponseHeaders(500, -1)
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(exchange: HttpExchange, key: String, body: ByteArray) {
        when (exchange.requestMethod) {
            "PROPFIND" -> propfind(exchange, key)

            "GET" -> get(exchange, key, true)

            "HEAD" -> get(exchange, key, false)

            "PUT" -> put(exchange, key, body)

            "MKCOL" -> mkCol(exchange, key)

            "DELETE" -> delete(exchange, key)

            "MOVE" -> move(exchange, key)

            "OPTIONS" -> {
                exchange.responseHeaders.add("DAV", "1, 2, 3")
                exchange.sendResponseHeaders(200, -1)
            }

            else -> exchange.sendResponseHeaders(405, -1)
        }
    }

    private fun isAuthorized(exchange: HttpExchange): Boolean {
        val expected = "Basic " + Base64.getEncoder().encodeToString(password!!.toByteArray())
        return exchange.requestHeaders.getFirst("Authorization") == expected
    }

    private fun propfind(exchange: HttpExchange, key: String) {
        val entry = entries[key]
        if (entry == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val keys = mutableListOf(key)
        if (exchange.requestHeaders.getFirst("Depth") == "1") {
            keys += childKeys(key)
        }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<multistatus xmlns=\"DAV:\">")
            keys.forEach { append(responseXml(it, entries.getValue(it))) }
            append("</multistatus>")
        }
        respond(exchange, 207, xml.toByteArray(), "application/xml; charset=utf-8")
    }

    private fun childKeys(key: String): List<String> = entries.keys.filter {
        it.startsWith("$key/") && !it.substring(key.length + 1).contains('/')
    }

    private fun responseXml(key: String, entry: Entry): String = buildString {
        append("<response><href>").append(if (entry.isCollection) "$key/" else key)
        append("</href><propstat><prop><resourcetype>")
        if (entry.isCollection) {
            append("<collection/>")
        }
        append("</resourcetype>")
        append("<getcontentlength>").append(entry.content.size).append("</getcontentlength>")
        append("<getlastmodified>").append(HttpUtils.formatDate(entry.lastModified))
        append("</getlastmodified>")
        entry.creationDate?.let {
            append("<creationdate>").append(it).append("</creationdate>")
        }
        append("</prop><status>HTTP/1.1 200 OK</status></propstat></response>")
    }

    private fun get(exchange: HttpExchange, key: String, writeBody: Boolean) {
        val entry = entries[key]
        if (entry == null || entry.isCollection) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null) {
            getRange(exchange, entry, range)
            return
        }
        if (writeBody) {
            respond(exchange, 200, entry.content, "application/octet-stream")
        } else {
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, entry.content.size.toLong())
        }
    }

    private fun getRange(exchange: HttpExchange, entry: Entry, range: String) {
        val (first, last) = range.removePrefix("bytes=").split('-')
        val start = first.toInt()
        val size = entry.content.size
        if (start >= size) {
            exchange.sendResponseHeaders(416, -1)
            return
        }
        val end = minOf(last.toInt(), size - 1)
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
        respond(
            exchange,
            206,
            entry.content.copyOfRange(start, end + 1),
            "application/octet-stream"
        )
    }

    private fun put(exchange: HttpExchange, key: String, body: ByteArray) {
        val existing = entries[key]
        if (existing != null && existing.isCollection) {
            exchange.sendResponseHeaders(409, -1)
            return
        }
        entries[key] = Entry(false, body, creationDate = existing?.creationDate)
        exchange.sendResponseHeaders(if (existing != null) 204 else 201, -1)
    }

    private fun mkCol(exchange: HttpExchange, key: String) {
        when {
            key in entries -> exchange.sendResponseHeaders(405, -1)

            key.substringBeforeLast('/', "") !in entries -> exchange.sendResponseHeaders(409, -1)

            else -> {
                entries[key] = Entry(true)
                exchange.sendResponseHeaders(201, -1)
            }
        }
    }

    private fun delete(exchange: HttpExchange, key: String) {
        if (key !in entries) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        removeSubtree(key)
        exchange.sendResponseHeaders(204, -1)
    }

    private fun move(exchange: HttpExchange, key: String) {
        val entry = entries[key]
        if (entry == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val target = key(URI(exchange.requestHeaders.getFirst("Destination")).path)
        val existing = entries[target]
        if (existing != null && exchange.requestHeaders.getFirst("Overwrite") == "F") {
            exchange.sendResponseHeaders(412, -1)
            return
        }
        if (existing != null) {
            removeSubtree(target)
        }
        for (movedKey in subtree(key)) {
            entries[target + movedKey.substring(key.length)] = entries.getValue(movedKey)
        }
        removeSubtree(key)
        exchange.sendResponseHeaders(if (existing != null) 204 else 201, -1)
    }

    private fun subtree(key: String): List<String> =
        entries.keys.filter { it == key || it.startsWith("$key/") }

    private fun removeSubtree(key: String) {
        subtree(key).forEach { entries -= it }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray, contentType: String) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
    }
}
