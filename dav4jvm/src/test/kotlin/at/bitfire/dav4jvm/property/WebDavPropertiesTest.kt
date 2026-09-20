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

import at.bitfire.dav4jvm.property.webdav.AddMember
import at.bitfire.dav4jvm.property.webdav.CreationDate
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrivilegeSet
import at.bitfire.dav4jvm.property.webdav.Depth
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import at.bitfire.dav4jvm.property.webdav.GetContentType
import at.bitfire.dav4jvm.property.webdav.GetLastModified
import at.bitfire.dav4jvm.property.webdav.GroupMembership
import at.bitfire.dav4jvm.property.webdav.QuotaAvailableBytes
import at.bitfire.dav4jvm.property.webdav.QuotaUsedBytes
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.SupportedReportSet
import at.bitfire.dav4jvm.property.webdav.SyncLevel
import at.bitfire.dav4jvm.property.webdav.SyncToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WebDavPropertiesTest: PropertyTest() {

    @Test
    fun testAddMember() {
        val addMember = parseProperty("<add-member xmlns=\"DAV:\"><href>/coll/;add-member</href></add-member>")
            .first() as AddMember
        assertEquals("/coll/;add-member", addMember.href)
        assertNull((parseProperty("<add-member xmlns=\"DAV:\"/>").first() as AddMember).href)
    }

    @Test
    fun testCreationDateAndLastModified() {
        val created = parseProperty("<creationdate xmlns=\"DAV:\">1997-12-01T17:42:21-08:00</creationdate>")
            .first() as CreationDate
        assertEquals("1997-12-01T17:42:21-08:00", created.creationDate)

        val modified = parseProperty("<getlastmodified xmlns=\"DAV:\">Mon, 12 Jan 1998 09:25:56 GMT</getlastmodified>")
            .first() as GetLastModified
        assertEquals(Instant.ofEpochSecond(884597156), modified.lastModified)

        val unparseable = parseProperty("<getlastmodified xmlns=\"DAV:\">yesterday</getlastmodified>")
            .first() as GetLastModified
        assertNull(unparseable.lastModified)
    }

    @Test
    fun testContentLengthAndType() {
        assertEquals(
            4096L,
            (parseProperty("<getcontentlength xmlns=\"DAV:\">4096</getcontentlength>").first() as GetContentLength)
                .contentLength
        )
        // A non-numeric value is reported as "unknown" instead of crashing the whole PROPFIND.
        assertNull(
            (parseProperty("<getcontentlength xmlns=\"DAV:\">big</getcontentlength>").first() as GetContentLength)
                .contentLength
        )
        assertEquals(
            "text/html",
            (parseProperty("<getcontenttype xmlns=\"DAV:\">text/html; charset=utf-8</getcontenttype>")
                .first() as GetContentType).type?.let { "${it.type}/${it.subtype}" }
        )
        assertNull(
            (parseProperty("<getcontenttype xmlns=\"DAV:\">no-mime-type</getcontenttype>")
                .first() as GetContentType).type
        )
    }

    @Test
    fun testQuota() {
        val properties = parseProperty(
            "<quota-available-bytes xmlns=\"DAV:\">1024</quota-available-bytes>" +
            "<quota-used-bytes xmlns=\"DAV:\">2048</quota-used-bytes>"
        )
        assertEquals(1024L, (properties[0] as QuotaAvailableBytes).quotaAvailableBytes)
        assertEquals(2048L, (properties[1] as QuotaUsedBytes).quotaUsedBytes)
    }

    @Test
    fun testDisplayNameAndPrincipal() {
        assertEquals(
            "My Files",
            (parseProperty("<displayname xmlns=\"DAV:\">My Files</displayname>").first() as DisplayName).displayName
        )
        assertEquals(
            "/principals/me/",
            (parseProperty("<current-user-principal xmlns=\"DAV:\"><href>/principals/me/</href></current-user-principal>")
                .first() as CurrentUserPrincipal).href
        )
        // "unauthenticated" instead of an href
        assertNull(
            (parseProperty("<current-user-principal xmlns=\"DAV:\"><unauthenticated/></current-user-principal>")
                .first() as CurrentUserPrincipal).href
        )
        assertEquals(
            listOf("/groups/a/", "/groups/b/"),
            (parseProperty("<group-membership xmlns=\"DAV:\"><href>/groups/a/</href><href>/groups/b/</href></group-membership>")
                .first() as GroupMembership).hrefs
        )
    }

    @Test
    fun testDepthAndSyncLevel() {
        assertEquals(Depth(0), parseProperty(Depth.Factory, "<depth xmlns=\"DAV:\">0</depth>"))
        assertEquals(Depth(Depth.INFINITY), parseProperty(Depth.Factory, "<depth xmlns=\"DAV:\">Infinity</depth>"))
        assertEquals(Depth(null), parseProperty(Depth.Factory, "<depth xmlns=\"DAV:\">deep</depth>"))

        assertEquals(SyncLevel(1), parseProperty(SyncLevel.Factory, "<sync-level xmlns=\"DAV:\">1</sync-level>"))
        assertEquals(
            SyncLevel(Int.MAX_VALUE),
            parseProperty(SyncLevel.Factory, "<sync-level xmlns=\"DAV:\">infinite</sync-level>")
        )
        assertEquals(SyncLevel(null), parseProperty(SyncLevel.Factory, "<sync-level xmlns=\"DAV:\">all</sync-level>"))

        assertEquals(
            "http://example.com/ns/sync/1234",
            (parseProperty("<sync-token xmlns=\"DAV:\">http://example.com/ns/sync/1234</sync-token>")
                .first() as SyncToken).token
        )
    }

    @Test
    fun testResourceType() {
        val types = (parseProperty(
            "<resourcetype xmlns=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">" +
                "<collection/><CARD:addressbook/><unknown-type/>" +
            "</resourcetype>").first() as ResourceType).types
        assertTrue(types.contains(ResourceType.COLLECTION))
        assertTrue(types.contains(ResourceType.ADDRESSBOOK))
        assertEquals(3, types.size)

        assertTrue((parseProperty("<resourcetype xmlns=\"DAV:\"/>").first() as ResourceType).types.isEmpty())
    }

    @Test
    fun testSupportedReportSet() {
        val reports = (parseProperty(
            "<supported-report-set xmlns=\"DAV:\">" +
                "<supported-report><report><sync-collection/></report></supported-report>" +
                "<supported-report><report><expand-property/></report></supported-report>" +
            "</supported-report-set>").first() as SupportedReportSet).reports
        assertEquals(setOf(SupportedReportSet.SYNC_COLLECTION, "DAV:expand-property"), reports)
    }

    @Test
    fun testCurrentUserPrivilegeSet() {
        val readOnly = parseProperty(
            "<current-user-privilege-set xmlns=\"DAV:\">" +
                "<privilege><read/></privilege>" +
            "</current-user-privilege-set>").first() as CurrentUserPrivilegeSet
        assertEquals(CurrentUserPrivilegeSet(mayRead = true), readOnly)

        val write = parseProperty(
            "<current-user-privilege-set xmlns=\"DAV:\">" +
                "<privilege><write/></privilege>" +
            "</current-user-privilege-set>").first() as CurrentUserPrivilegeSet
        assertEquals(
            CurrentUserPrivilegeSet(
                mayWriteProperties = true, mayWriteContent = true, mayBind = true, mayUnbind = true
            ),
            write
        )

        val parts = parseProperty(
            "<current-user-privilege-set xmlns=\"DAV:\">" +
                "<privilege><write-content/></privilege>" +
                "<privilege><write-properties/></privilege>" +
                "<privilege><bind/></privilege>" +
                "<privilege><unbind/></privilege>" +
                "<privilege><unknown-privilege/></privilege>" +
            "</current-user-privilege-set>").first() as CurrentUserPrivilegeSet
        assertEquals(
            CurrentUserPrivilegeSet(
                mayWriteProperties = true, mayWriteContent = true, mayBind = true, mayUnbind = true
            ),
            parts
        )

        val all = parseProperty(
            "<current-user-privilege-set xmlns=\"DAV:\">" +
                "<privilege><all/></privilege>" +
            "</current-user-privilege-set>").first() as CurrentUserPrivilegeSet
        assertEquals(
            CurrentUserPrivilegeSet(
                mayRead = true, mayWriteProperties = true, mayWriteContent = true,
                mayBind = true, mayUnbind = true
            ),
            all
        )
    }

}
