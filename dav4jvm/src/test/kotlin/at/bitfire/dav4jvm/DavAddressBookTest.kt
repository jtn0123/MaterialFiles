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

import at.bitfire.dav4jvm.property.carddav.AddressData
import at.bitfire.dav4jvm.property.webdav.GetETag
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DavAddressBookTest {

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build()
    private val mockServer = MockWebServer()

    @Before
    fun startServer() {
        mockServer.start()
    }

    @After
    fun stopServer() {
        mockServer.close()
    }

    private fun multiStatus(body: String) =
        MockResponse.Builder()
            .code(207)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .body(body)
            .build()

    @Test
    fun addressbookQuery() {
        val book = DavAddressBook(httpClient, mockServer.url("/book/"))
        mockServer.enqueue(multiStatus(
            "<multistatus xmlns=\"DAV:\">" +
                "<response><href>/book/a.vcf</href><propstat><prop><getetag>\"e1\"</getetag></prop>" +
                "<status>HTTP/1.1 200 OK</status></propstat></response>" +
            "</multistatus>"))

        val etags = mutableMapOf<String, String?>()
        val relations = mutableListOf<Response.HrefRelation>()
        book.addressbookQuery { response, relation ->
            etags[response.hrefName()] = response[GetETag::class.java]?.eTag
            relations += relation
        }

        val rq = mockServer.takeRequest()
        assertEquals("REPORT", rq.method)
        assertEquals("1", rq.headers["Depth"])
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<CARD:addressbook-query xmlns=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">" +
                    "<prop><getetag /></prop>" +
                    "<CARD:filter />" +
                "</CARD:addressbook-query>", rq.body?.utf8())
        assertEquals(mapOf("a.vcf" to "e1"), etags)
        assertEquals(listOf(Response.HrefRelation.MEMBER), relations)
    }

    @Test
    fun multiget() {
        val book = DavAddressBook(httpClient, mockServer.url("/book/"))
        mockServer.enqueue(multiStatus(
            "<multistatus xmlns=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">" +
                "<response><href>/book/a.vcf</href><propstat><prop>" +
                    "<CARD:address-data>BEGIN:VCARD&#13;\nEND:VCARD</CARD:address-data>" +
                "</prop><status>HTTP/1.1 200 OK</status></propstat></response>" +
            "</multistatus>"))

        val cards = mutableListOf<String?>()
        book.multiget(
            listOf(mockServer.url("/book/a.vcf"), mockServer.url("/book/b%20c.vcf")),
            contentType = "text/vcard",
            version = "4.0"
        ) { response, _ ->
            cards += response[AddressData::class.java]?.card
        }

        val rq = mockServer.takeRequest()
        assertEquals("REPORT", rq.method)
        assertEquals("0", rq.headers["Depth"])
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<CARD:addressbook-multiget xmlns=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">" +
                    "<prop>" +
                        "<getcontenttype />" +
                        "<getetag />" +
                        "<CARD:address-data content-type=\"text/vcard\" version=\"4.0\" />" +
                    "</prop>" +
                    "<href>/book/a.vcf</href>" +
                    "<href>/book/b%20c.vcf</href>" +
                "</CARD:addressbook-multiget>", rq.body?.utf8())
        assertEquals(listOf("BEGIN:VCARD\r\nEND:VCARD"), cards)
    }

    @Test
    fun multigetWithoutFormat() {
        val book = DavAddressBook(httpClient, mockServer.url("/book/"))
        mockServer.enqueue(multiStatus("<multistatus xmlns=\"DAV:\"/>"))
        book.multiget(listOf(mockServer.url("/book/a.vcf"))) { _, _ -> }
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<CARD:addressbook-multiget xmlns=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">" +
                    "<prop><getcontenttype /><getetag /><CARD:address-data /></prop>" +
                    "<href>/book/a.vcf</href>" +
                "</CARD:addressbook-multiget>", mockServer.takeRequest().body?.utf8())
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/vcard+json", DavAddressBook.MIME_JCARD.toString())
        assertEquals("utf-8", DavAddressBook.MIME_VCARD3_UTF8.charset()?.name()?.lowercase())
        assertEquals("4.0", DavAddressBook.MIME_VCARD4.parameter("version"))
    }

}
