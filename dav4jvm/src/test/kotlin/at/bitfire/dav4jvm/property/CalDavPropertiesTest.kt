/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package at.bitfire.dav4jvm.property

import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.caldav.CalendarProxyReadFor
import at.bitfire.dav4jvm.property.caldav.CalendarProxyWriteFor
import at.bitfire.dav4jvm.property.caldav.CalendarTimezone
import at.bitfire.dav4jvm.property.caldav.CalendarTimezoneId
import at.bitfire.dav4jvm.property.caldav.CalendarUserAddressSet
import at.bitfire.dav4jvm.property.caldav.GetCTag
import at.bitfire.dav4jvm.property.caldav.MaxResourceSize
import at.bitfire.dav4jvm.property.caldav.ScheduleTag
import at.bitfire.dav4jvm.property.caldav.Source
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CAL = "xmlns=\"urn:ietf:params:xml:ns:caldav\" xmlns:D=\"DAV:\""
private const val CS = "xmlns=\"http://calendarserver.org/ns/\" xmlns:D=\"DAV:\""

class CalDavPropertiesTest: PropertyTest() {

    @Test
    fun testCalendarColor() {
        assertEquals(0xFF112233.toInt(), (parseProperty(
            "<calendar-color xmlns=\"http://apple.com/ns/ical/\">#112233</calendar-color>"
        ).first() as CalendarColor).color)
        assertEquals(0x80112233.toInt(), (parseProperty(
            "<calendar-color xmlns=\"http://apple.com/ns/ical/\">11223380</calendar-color>"
        ).first() as CalendarColor).color)
        // an unparseable color is ignored instead of failing the whole PROPFIND
        assertNull((parseProperty(
            "<calendar-color xmlns=\"http://apple.com/ns/ical/\">blue</calendar-color>"
        ).first() as CalendarColor).color)
        assertNull((parseProperty(
            "<calendar-color xmlns=\"http://apple.com/ns/ical/\"/>"
        ).first() as CalendarColor).color)
    }

    @Test
    fun testCalendarColor_parseARGBColor() {
        assertEquals(0xFFFF0000.toInt(), CalendarColor.parseARGBColor("#FF0000"))
        assertEquals(0xFFFF0000.toInt(), CalendarColor.parseARGBColor("FF0000"))
        assertEquals(0x00FF0000, CalendarColor.parseARGBColor("#FF000000"))
        try {
            CalendarColor.parseARGBColor("#FF00")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("#FF00"))
        }
    }

    @Test
    fun testTextProperties() {
        assertEquals("BEGIN:VCALENDAR", (parseProperty(
            "<calendar-data $CAL>BEGIN:VCALENDAR</calendar-data>").first() as CalendarData).iCalendar)
        assertEquals("BEGIN:VTIMEZONE", (parseProperty(
            "<calendar-timezone $CAL>BEGIN:VTIMEZONE</calendar-timezone>").first() as CalendarTimezone).vTimeZone)
        assertEquals("Europe/Vienna", (parseProperty(
            "<calendar-timezone-id $CAL>Europe/Vienna</calendar-timezone-id>").first() as CalendarTimezoneId).identifier)
        assertEquals(10485760L, (parseProperty(
            "<max-resource-size $CAL>10485760</max-resource-size>").first() as MaxResourceSize).maxSize)
        assertEquals("ctag-1", (parseProperty(
            "<getctag $CS>ctag-1</getctag>").first() as GetCTag).cTag)
    }

    @Test
    fun testScheduleTag() {
        val scheduleTag = parseProperty("<schedule-tag $CAL>\"abc\"</schedule-tag>").first() as ScheduleTag
        assertEquals("abc", scheduleTag.scheduleTag)
        assertEquals("abc", scheduleTag.toString())
        assertEquals("(null)", ScheduleTag(null).toString())
    }

    @Test
    fun testHrefListProperties() {
        assertEquals(listOf("/cal/"), (parseProperty(
            "<calendar-home-set $CAL><D:href>/cal/</D:href></calendar-home-set>").first() as CalendarHomeSet).hrefs)
        assertEquals(listOf("mailto:me@example.com"), (parseProperty(
            "<calendar-user-address-set $CAL><D:href>mailto:me@example.com</D:href></calendar-user-address-set>")
            .first() as CalendarUserAddressSet).hrefs)
        assertEquals(listOf("/principals/a/"), (parseProperty(
            "<calendar-proxy-read-for $CS><D:href>/principals/a/</D:href></calendar-proxy-read-for>")
            .first() as CalendarProxyReadFor).hrefs)
        assertEquals(listOf("/principals/b/"), (parseProperty(
            "<calendar-proxy-write-for $CS><D:href>/principals/b/</D:href></calendar-proxy-write-for>")
            .first() as CalendarProxyWriteFor).hrefs)
        assertEquals(listOf("https://example.com/feed.ics"), (parseProperty(
            "<source $CS><D:href>https://example.com/feed.ics</D:href></source>").first() as Source).hrefs)
        // no <href> at all
        assertEquals(emptyList<String>(), (parseProperty(
            "<calendar-home-set $CAL/>").first() as CalendarHomeSet).hrefs)
    }

    @Test
    fun testSupportedCalendarComponentSet() {
        assertEquals(
            SupportedCalendarComponentSet(supportsEvents = true, supportsTasks = false, supportsJournal = false),
            parseProperty("<supported-calendar-component-set $CAL><comp name=\"VEVENT\"/></supported-calendar-component-set>")
                .first()
        )
        assertEquals(
            SupportedCalendarComponentSet(supportsEvents = false, supportsTasks = true, supportsJournal = true),
            parseProperty("<supported-calendar-component-set $CAL>" +
                "<comp name=\"vtodo\"/><comp name=\"VJOURNAL\"/><comp name=\"VFREEBUSY\"/>" +
                "</supported-calendar-component-set>").first()
        )
        assertEquals(
            SupportedCalendarComponentSet(supportsEvents = true, supportsTasks = true, supportsJournal = true),
            parseProperty("<supported-calendar-component-set $CAL><allcomp/></supported-calendar-component-set>")
                .first()
        )
        assertEquals(
            SupportedCalendarComponentSet(supportsEvents = false, supportsTasks = false, supportsJournal = false),
            parseProperty("<supported-calendar-component-set $CAL/>").first()
        )
    }

    @Test
    fun testSupportedCalendarData() {
        val types = parseProperty("<supported-calendar-data $CAL>" +
            "<calendar-data content-type=\"text/calendar\" version=\"2.0\"/>" +
            "<calendar-data content-type=\"application/calendar+json\"/>" +
            "</supported-calendar-data>").first() as SupportedCalendarData
        assertTrue(types.hasJCal())
        assertEquals(2, types.types.size)

        val iCalOnly = parseProperty("<supported-calendar-data $CAL>" +
            "<calendar-data content-type=\"text/calendar\" version=\"2.0\"/>" +
            "</supported-calendar-data>").first() as SupportedCalendarData
        assertFalse(iCalOnly.hasJCal())
    }

    @Test
    fun testScheduleTagFromResponse() {
        val response = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.com/cal/1.ics").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200).message("OK")
            .header("Schedule-Tag", "\"abc\"")
            .build()
        assertEquals("abc", ScheduleTag.fromResponse(response)?.scheduleTag)
        assertNull(ScheduleTag.fromResponse(response.newBuilder().removeHeader("Schedule-Tag").build()))
    }

    @Test
    fun testEmptyElementsGiveDefaults() {
        assertEquals(
            SupportedCalendarData().types,
            (parseProperty("<supported-calendar-data $CAL/>").first() as SupportedCalendarData).types
        )
    }

}
