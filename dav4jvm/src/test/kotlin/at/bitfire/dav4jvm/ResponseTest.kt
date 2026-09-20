/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package at.bitfire.dav4jvm

import at.bitfire.dav4jvm.property.webdav.DisplayName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.internal.http.StatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ResponseTest {

    private val location = "https://example.com/dav/collection/".toHttpUrl()

    private fun parse(xml: String): List<Pair<Response, Response.HrefRelation>> {
        val parser = XmlUtils.newPullParser()
        parser.setInput(StringReader(xml))
        parser.nextTag()    // move into <response>
        val results = mutableListOf<Pair<Response, Response.HrefRelation>>()
        Response.parse(parser, location) { response, relation ->
            results += response to relation
        }
        return results
    }

    private fun response(href: String, body: String = "") =
        "<response xmlns=\"DAV:\"><href>$href</href>$body</response>"

    @Test
    fun testRelations() {
        assertEquals(Response.HrefRelation.SELF, parse(response("/dav/collection/")).first().second)
        assertEquals(Response.HrefRelation.SELF, parse(response("/dav/collection")).first().second)
        assertEquals(Response.HrefRelation.MEMBER, parse(response("/dav/collection/file.txt")).first().second)
        // another collection on the same server
        assertEquals(Response.HrefRelation.OTHER, parse(response("/other/file.txt")).first().second)
        // the parent collection
        assertEquals(Response.HrefRelation.OTHER, parse(response("/dav/")).first().second)
        // another server
        assertEquals(Response.HrefRelation.OTHER, parse(response("https://other.example.com/dav/collection/x")).first().second)
    }

    @Test
    fun testRelativeHrefWithColon() {
        // "a:b.vcf" must not be parsed as the scheme "a"
        val response = parse(response("a:b.vcf")).first().first
        assertEquals("https://example.com/dav/collection/a:b.vcf", response.href.toString())
        assertEquals("a:b.vcf", response.hrefName())

        // a real absolute URL is kept
        assertEquals(
            "https://example.com/dav/collection/other.vcf",
            parse(response("https://example.com/dav/collection/other.vcf")).first().first.href.toString()
        )
    }

    @Test
    fun testWithoutHref() {
        assertTrue(parse("<response xmlns=\"DAV:\"><status>HTTP/1.1 404 Not Found</status></response>").isEmpty())
    }

    @Test
    fun testStatusAndProperties() {
        val (response, _) = parse(response("/dav/collection/file.txt",
            "<propstat><prop><displayname>File</displayname></prop>" +
            "<status>HTTP/1.1 200 OK</status></propstat>" +
            "<propstat><prop><getetag/></prop>" +
            "<status>HTTP/1.1 404 Not Found</status></propstat>")).first()
        assertTrue(response.isSuccess())
        assertEquals("File", response[DisplayName::class.java]?.displayName)
        assertEquals(1, response.properties.size)
        assertEquals("file.txt", response.hrefName())
        assertEquals(location, response.requestedUrl)
    }

    @Test
    fun testInvalidStatusIsServerError() {
        val (response, _) = parse(response("/dav/collection/file.txt", "<status>Some Garbage</status>")).first()
        assertEquals(500, response.status?.code)
        assertFalse(response.isSuccess())
        // properties of a failed response are not reported
        assertTrue(response.properties.isEmpty())
    }

    @Test
    fun testCollectionGetsTrailingSlash() {
        val (response, relation) = parse(response("/dav/collection/sub",
            "<propstat><prop><resourcetype><collection/></resourcetype></prop>" +
            "<status>HTTP/1.1 200 OK</status></propstat>")).first()
        assertEquals("https://example.com/dav/collection/sub/", response.href.toString())
        assertEquals(Response.HrefRelation.MEMBER, relation)
        assertEquals("sub", response.hrefName())
    }

    @Test
    fun testErrorAndNewLocation() {
        val (response, _) = parse(response("/dav/collection/file.txt",
            "<error><need-privileges/></error>" +
            // note: dav4jvm reads the URL as the text of <location>, not from a nested <href>
            "<location>https://example.com/dav/moved.txt</location>" +
            "<status>HTTP/1.1 403 Forbidden</status>")).first()
        assertEquals(listOf(Error.NEED_PRIVILEGES), response.error)
        assertEquals("https://example.com/dav/moved.txt", response.newLocation.toString())
    }

    @Test
    fun testConstructorDefaults() {
        val response = Response(
            requestedUrl = location,
            href = location.resolve("file.txt")!!,
            status = StatusLine(Protocol.HTTP_1_1, 200, "OK"),
            propstat = emptyList()
        )
        assertTrue(response.propstat.isEmpty())
        assertNull(response.error)
        assertNull(response.newLocation)
        assertTrue(response.isSuccess())
    }

    @Test
    fun testError() {
        assertEquals(Error.NEED_PRIVILEGES, Error(Property.Name("DAV:", "need-privileges")))
        assertEquals(
            Error.VALID_SYNC_TOKEN.hashCode(),
            Error(Property.Name("DAV:", "valid-sync-token")).hashCode()
        )
        assertFalse(Error.NEED_PRIVILEGES.equals("need-privileges"))
    }

}
