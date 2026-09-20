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

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyFactory
import at.bitfire.dav4jvm.property.caldav.NS_APPLE_ICAL
import at.bitfire.dav4jvm.property.caldav.NS_CALDAV
import at.bitfire.dav4jvm.property.caldav.NS_CALENDARSERVER
import at.bitfire.dav4jvm.property.carddav.NS_CARDDAV
import at.bitfire.dav4jvm.property.push.NS_WEBDAV_PUSH
import at.bitfire.dav4jvm.property.webdav.NS_WEBDAV
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The element name a factory is registered for decides whether a property of a PROPFIND response
 * is recognized at all, so check every factory against the name from its specification.
 */
class PropertyFactoryNamesTest {

    private fun assertName(namespace: String, name: String, factory: PropertyFactory) {
        assertEquals(Property.Name(namespace, name), factory.getName())
    }

    @Test
    fun testWebDavNames() {
        assertName(NS_WEBDAV, "add-member", at.bitfire.dav4jvm.property.webdav.AddMember.Factory)
        assertName(NS_WEBDAV, "creationdate", at.bitfire.dav4jvm.property.webdav.CreationDate.Factory)
        assertName(NS_WEBDAV, "current-user-principal", at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal.Factory)
        assertName(NS_WEBDAV, "current-user-privilege-set", at.bitfire.dav4jvm.property.webdav.CurrentUserPrivilegeSet.Factory)
        assertName(NS_WEBDAV, "depth", at.bitfire.dav4jvm.property.webdav.Depth.Factory)
        assertName(NS_WEBDAV, "displayname", at.bitfire.dav4jvm.property.webdav.DisplayName.Factory)
        assertName(NS_WEBDAV, "getcontentlength", at.bitfire.dav4jvm.property.webdav.GetContentLength.Factory)
        assertName(NS_WEBDAV, "getcontenttype", at.bitfire.dav4jvm.property.webdav.GetContentType.Factory)
        assertName(NS_WEBDAV, "getetag", at.bitfire.dav4jvm.property.webdav.GetETag.Factory)
        assertName(NS_WEBDAV, "getlastmodified", at.bitfire.dav4jvm.property.webdav.GetLastModified.Factory)
        assertName(NS_WEBDAV, "group-membership", at.bitfire.dav4jvm.property.webdav.GroupMembership.Factory)
        assertName(NS_WEBDAV, "owner", at.bitfire.dav4jvm.property.webdav.Owner.Factory)
        assertName(NS_WEBDAV, "quota-available-bytes", at.bitfire.dav4jvm.property.webdav.QuotaAvailableBytes.Factory)
        assertName(NS_WEBDAV, "quota-used-bytes", at.bitfire.dav4jvm.property.webdav.QuotaUsedBytes.Factory)
        assertName(NS_WEBDAV, "resourcetype", at.bitfire.dav4jvm.property.webdav.ResourceType.Factory)
        assertName(NS_WEBDAV, "supported-report-set", at.bitfire.dav4jvm.property.webdav.SupportedReportSet.Factory)
        assertName(NS_WEBDAV, "sync-level", at.bitfire.dav4jvm.property.webdav.SyncLevel.Factory)
        assertName(NS_WEBDAV, "sync-token", at.bitfire.dav4jvm.property.webdav.SyncToken.Factory)
    }

    @Test
    fun testCalDavNames() {
        assertName(NS_APPLE_ICAL, "calendar-color", at.bitfire.dav4jvm.property.caldav.CalendarColor.Factory)
        assertName(NS_CALDAV, "calendar-data", at.bitfire.dav4jvm.property.caldav.CalendarData.Factory)
        assertName(NS_CALDAV, "calendar-description", at.bitfire.dav4jvm.property.caldav.CalendarDescription.Factory)
        assertName(NS_CALDAV, "calendar-home-set", at.bitfire.dav4jvm.property.caldav.CalendarHomeSet.Factory)
        assertName(NS_CALDAV, "calendar-timezone", at.bitfire.dav4jvm.property.caldav.CalendarTimezone.Factory)
        assertName(NS_CALDAV, "calendar-timezone-id", at.bitfire.dav4jvm.property.caldav.CalendarTimezoneId.Factory)
        assertName(NS_CALDAV, "calendar-user-address-set", at.bitfire.dav4jvm.property.caldav.CalendarUserAddressSet.Factory)
        assertName(NS_CALDAV, "max-resource-size", at.bitfire.dav4jvm.property.caldav.MaxResourceSize.Factory)
        assertName(NS_CALDAV, "schedule-tag", at.bitfire.dav4jvm.property.caldav.ScheduleTag.Factory)
        assertName(NS_CALDAV, "supported-calendar-component-set", at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet.Factory)
        assertName(NS_CALDAV, "supported-calendar-data", at.bitfire.dav4jvm.property.caldav.SupportedCalendarData.Factory)
        assertName(NS_CALENDARSERVER, "calendar-proxy-read-for", at.bitfire.dav4jvm.property.caldav.CalendarProxyReadFor.Factory)
        assertName(NS_CALENDARSERVER, "calendar-proxy-write-for", at.bitfire.dav4jvm.property.caldav.CalendarProxyWriteFor.Factory)
        assertName(NS_CALENDARSERVER, "getctag", at.bitfire.dav4jvm.property.caldav.GetCTag.Factory)
        assertName(NS_CALENDARSERVER, "source", at.bitfire.dav4jvm.property.caldav.Source.Factory)
    }

    @Test
    fun testCardDavNames() {
        assertName(NS_CARDDAV, "address-data", at.bitfire.dav4jvm.property.carddav.AddressData.Factory)
        assertName(NS_CARDDAV, "addressbook-description", at.bitfire.dav4jvm.property.carddav.AddressbookDescription.Factory)
        assertName(NS_CARDDAV, "addressbook-home-set", at.bitfire.dav4jvm.property.carddav.AddressbookHomeSet.Factory)
        assertName(NS_CARDDAV, "max-resource-size", at.bitfire.dav4jvm.property.carddav.MaxResourceSize.Factory)
        assertName(NS_CARDDAV, "supported-address-data", at.bitfire.dav4jvm.property.carddav.SupportedAddressData.Factory)
    }

    @Test
    fun testPushNames() {
        assertName(NS_WEBDAV_PUSH, "auth-secret", at.bitfire.dav4jvm.property.push.AuthSecret.Factory)
        assertName(NS_WEBDAV_PUSH, "content-update", at.bitfire.dav4jvm.property.push.ContentUpdate.Factory)
        assertName(NS_WEBDAV_PUSH, "property-update", at.bitfire.dav4jvm.property.push.PropertyUpdate.Factory)
        assertName(NS_WEBDAV_PUSH, "push-message", at.bitfire.dav4jvm.property.push.PushMessage.Factory)
        assertName(NS_WEBDAV_PUSH, "push-register", at.bitfire.dav4jvm.property.push.PushRegister.Factory)
        assertName(NS_WEBDAV_PUSH, "push-resource", at.bitfire.dav4jvm.property.push.PushResource.Factory)
        assertName(NS_WEBDAV_PUSH, "subscription", at.bitfire.dav4jvm.property.push.Subscription.Factory)
        assertName(NS_WEBDAV_PUSH, "subscription-public-key", at.bitfire.dav4jvm.property.push.SubscriptionPublicKey.Factory)
        assertName(NS_WEBDAV_PUSH, "supported-triggers", at.bitfire.dav4jvm.property.push.SupportedTriggers.Factory)
        assertName(NS_WEBDAV_PUSH, "topic", at.bitfire.dav4jvm.property.push.Topic.Factory)
        assertName(NS_WEBDAV_PUSH, "transports", at.bitfire.dav4jvm.property.push.PushTransports.Factory)
        assertName(NS_WEBDAV_PUSH, "trigger", at.bitfire.dav4jvm.property.push.Trigger.Factory)
        assertName(NS_WEBDAV_PUSH, "vapid-public-key", at.bitfire.dav4jvm.property.push.VapidPublicKey.Factory)
        assertName(NS_WEBDAV_PUSH, "web-push", at.bitfire.dav4jvm.property.push.WebPush.Factory)
        assertName(NS_WEBDAV_PUSH, "web-push-subscription", at.bitfire.dav4jvm.property.push.WebPushSubscription.Factory)
    }

}
