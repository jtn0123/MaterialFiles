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

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.ScheduleTag
import org.junit.Before
import org.junit.Test
import java.time.Instant

class DavCalendarTest {

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


    @Test
    fun calendarQuery_formatStartEnd() {
        val cal = DavCalendar(httpClient, mockServer.url("/"))
        mockServer.enqueue(MockResponse.Builder().code(207).body("<multistatus xmlns=\"DAV:\"/>").build())
        cal.calendarQuery("VEVENT",
            start = Instant.ofEpochSecond(784111777),
            end = Instant.ofEpochSecond(1689324577)) { _, _ -> }
        val rq = mockServer.takeRequest()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<CAL:calendar-query xmlns=\"DAV:\" xmlns:CAL=\"urn:ietf:params:xml:ns:caldav\">" +
                    "<prop>" +
                        "<getetag />" +
                    "</prop>" +
                    "<CAL:filter>" +
                        "<CAL:comp-filter name=\"VCALENDAR\">" +
                            "<CAL:comp-filter name=\"VEVENT\">" +
                                "<CAL:time-range start=\"19941106T084937Z\" end=\"20230714T084937Z\" />" +
                            "</CAL:comp-filter>" +
                        "</CAL:comp-filter>" +
                    "</CAL:filter>" +
                "</CAL:calendar-query>", rq.body?.utf8())
    }

    @Test
    fun calendarQuery_onlyStart_resultsReachCallback() {
        val cal = DavCalendar(httpClient, mockServer.url("/cal/"))
        mockServer.enqueue(MockResponse.Builder()
            .code(207)
            .addHeader("Content-Type", "text/xml")
            .body("<multistatus xmlns=\"DAV:\">" +
                "<response><href>/cal/1.ics</href><propstat><prop><getetag>\"x\"</getetag></prop>" +
                "<status>HTTP/1.1 200 OK</status></propstat></response>" +
                "<sync-token>token-1</sync-token>" +
                "</multistatus>")
            .build())
        val hrefs = mutableListOf<String>()
        val extra = cal.calendarQuery("VTODO", start = Instant.ofEpochSecond(0), end = null) { response, _ ->
            hrefs += response.href.encodedPath
        }
        val rq = mockServer.takeRequest()
        assertEquals("REPORT", rq.method)
        assertEquals("1", rq.headers["Depth"])
        assertTrue(rq.body!!.utf8().contains(
            "<CAL:comp-filter name=\"VTODO\"><CAL:time-range start=\"19700101T000000Z\" /></CAL:comp-filter>"))
        assertEquals(listOf("/cal/1.ics"), hrefs)
        assertEquals(listOf(at.bitfire.dav4jvm.property.webdav.SyncToken("token-1")), extra)
    }

    @Test
    fun calendarQuery_noTimeRange() {
        val cal = DavCalendar(httpClient, mockServer.url("/"))
        mockServer.enqueue(MockResponse.Builder().code(207).body("<multistatus xmlns=\"DAV:\"/>").build())
        cal.calendarQuery("VEVENT", start = null, end = null) { _, _ -> }
        val body = mockServer.takeRequest().body!!.utf8()
        assertTrue(body.contains("<CAL:comp-filter name=\"VEVENT\" />"))
        assertFalse(body.contains("time-range"))
    }

    @Test
    fun multiget() {
        val cal = DavCalendar(httpClient, mockServer.url("/cal/"))
        mockServer.enqueue(MockResponse.Builder()
            .code(207)
            .addHeader("Content-Type", "application/xml")
            .body("<multistatus xmlns=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">" +
                "<response><href>/cal/1.ics</href><propstat><prop>" +
                "<C:calendar-data>BEGIN:VCALENDAR</C:calendar-data>" +
                "<C:schedule-tag>\"s1\"</C:schedule-tag>" +
                "</prop><status>HTTP/1.1 200 OK</status></propstat></response>" +
                "</multistatus>")
            .build())
        var data: String? = null
        var scheduleTag: String? = null
        cal.multiget(listOf(mockServer.url("/cal/1.ics")), "text/calendar", "2.0") { response, _ ->
            data = response[CalendarData::class.java]?.iCalendar
            scheduleTag = response[ScheduleTag::class.java]?.scheduleTag
        }
        val rq = mockServer.takeRequest()
        assertEquals("REPORT", rq.method)
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<CAL:calendar-multiget xmlns=\"DAV:\" xmlns:CAL=\"urn:ietf:params:xml:ns:caldav\">" +
                    "<prop>" +
                        "<getcontenttype />" +
                        "<getetag />" +
                        "<CAL:schedule-tag />" +
                        "<CAL:calendar-data content-type=\"text/calendar\" version=\"2.0\" />" +
                    "</prop>" +
                    "<href>/cal/1.ics</href>" +
                "</CAL:calendar-multiget>", rq.body?.utf8())
        assertEquals("BEGIN:VCALENDAR", data)
        assertEquals("s1", scheduleTag)
    }

    @Test
    fun multiget_withoutFormat() {
        val cal = DavCalendar(httpClient, mockServer.url("/cal/"))
        mockServer.enqueue(MockResponse.Builder().code(207).body("<multistatus xmlns=\"DAV:\"/>").build())
        cal.multiget(listOf(mockServer.url("/cal/1.ics"))) { _, _ -> }
        assertTrue(mockServer.takeRequest().body!!.utf8().contains("<CAL:calendar-data />"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("text/calendar", DavCalendar.MIME_ICALENDAR.toString())
        assertEquals("utf-8", DavCalendar.MIME_ICALENDAR_UTF8.charset()?.name()?.lowercase())
    }

}
