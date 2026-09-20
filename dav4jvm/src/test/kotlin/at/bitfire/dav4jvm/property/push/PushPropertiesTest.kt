/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package at.bitfire.dav4jvm.property.push

import at.bitfire.dav4jvm.property.PropertyTest
import at.bitfire.dav4jvm.property.webdav.Depth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class PushPropertiesTest: PropertyTest() {

    @Test
    fun testSupportedTriggers() {
        val supported = parseProperty(SupportedTriggers.Factory,
            "<supported-triggers xmlns=\"$NS_WEBDAV_PUSH\" xmlns:D=\"DAV:\">" +
                "<content-update><D:sync-level>1</D:sync-level></content-update>" +
                "<property-update><D:sync-level>infinite</D:sync-level></property-update>" +
                "<unknown-trigger/>" +
            "</supported-triggers>") as SupportedTriggers
        assertEquals(Depth(1), supported.contentUpdate?.depth)
        assertEquals(Int.MAX_VALUE, supported.propertyUpdate?.syncLevel?.level)

        val empty = parseProperty(SupportedTriggers.Factory, "<supported-triggers xmlns=\"$NS_WEBDAV_PUSH\"/>") as SupportedTriggers
        assertNull(empty.contentUpdate)
        assertNull(empty.propertyUpdate)
    }

    @Test
    fun testTrigger() {
        val trigger = parseProperty(Trigger.Factory,
            "<trigger xmlns=\"$NS_WEBDAV_PUSH\" xmlns:D=\"DAV:\">" +
                "<content-update><D:sync-token>token-1</D:sync-token></content-update>" +
                "<property-update/>" +
            "</trigger>") as Trigger
        assertEquals("token-1", trigger.contentUpdate?.syncToken?.token)
        assertEquals(PropertyUpdate(), trigger.propertyUpdate)

        val empty = parseProperty(Trigger.Factory, "<trigger xmlns=\"$NS_WEBDAV_PUSH\"/>") as Trigger
        assertEquals(Trigger(), empty)
    }

    @Test
    fun testPushResourceAndTopic() {
        val register = parseProperty(
            "<push-register xmlns=\"$NS_WEBDAV_PUSH\">" +
                "<subscription><web-push-subscription>" +
                    "<push-resource>https://push.example/1</push-resource>" +
                "</web-push-subscription></subscription>" +
            "</push-register>").first() as PushRegister
        assertEquals(
            URI("https://push.example/1"),
            register.subscription?.webPushSubscription?.pushResource?.uri
        )
        assertNull(register.expires)
        assertNull(register.trigger)

        // An invalid URI is reported as "no URI" instead of throwing.
        val invalid = parseProperty(PushResource.Factory,
            "<push-resource xmlns=\"$NS_WEBDAV_PUSH\">not a uri</push-resource>") as PushResource
        assertNull(invalid.uri)

        assertNull((parseProperty("<topic xmlns=\"$NS_WEBDAV_PUSH\"/>").first() as Topic).topic)
    }

    @Test
    fun testPushRegisterWithTrigger() {
        val register = parseProperty(
            "<push-register xmlns=\"$NS_WEBDAV_PUSH\" xmlns:D=\"DAV:\">" +
                "<expires>Wed, 20 Dec 2023 10:03:31 GMT</expires>" +
                "<trigger><content-update><D:sync-level>0</D:sync-level></content-update></trigger>" +
            "</push-register>").first() as PushRegister
        assertEquals(java.time.Instant.ofEpochSecond(1703066611), register.expires)
        assertEquals(Depth(0), register.trigger?.contentUpdate?.depth)
    }

    @Test
    fun testTransportsWithoutWebPush() {
        val transports = parseProperty(
            "<transports xmlns=\"$NS_WEBDAV_PUSH\"><some-other-transport/></transports>")
            .first() as PushTransports
        assertFalse(transports.hasWebPush())
        assertEquals(emptySet<PushTransport>(), transports.transports)
    }

    @Test
    fun testSubscriptionKeys() {
        val subscription = parseProperty(WebPushSubscription.Factory,
            "<web-push-subscription xmlns=\"$NS_WEBDAV_PUSH\">" +
                "<push-resource>https://push.example/2</push-resource>" +
                "<subscription-public-key type=\"p256dh\">key-data</subscription-public-key>" +
                "<auth-secret>secret-data</auth-secret>" +
            "</web-push-subscription>") as WebPushSubscription
        assertEquals(SubscriptionPublicKey("p256dh", "key-data"), subscription.subscriptionPublicKey)
        assertEquals(AuthSecret("secret-data"), subscription.authSecret)
    }

    @Test
    fun testEmptyElementsGiveDefaults() {
        assertEquals(Topic(), parseProperty(Topic.Factory, "<topic xmlns=\"$NS_WEBDAV_PUSH\"/>"))
        assertEquals(AuthSecret(), parseProperty(AuthSecret.Factory, "<auth-secret xmlns=\"$NS_WEBDAV_PUSH\"/>"))
        assertEquals(PushResource(), parseProperty(PushResource.Factory, "<push-resource xmlns=\"$NS_WEBDAV_PUSH\"/>"))
        assertEquals(WebPush(), parseProperty(WebPush.Factory, "<web-push xmlns=\"$NS_WEBDAV_PUSH\"/>"))
        assertEquals(
            VapidPublicKey(),
            parseProperty(VapidPublicKey.Factory, "<vapid-public-key xmlns=\"$NS_WEBDAV_PUSH\"/>")
        )
        assertEquals(
            SubscriptionPublicKey(),
            parseProperty(SubscriptionPublicKey.Factory, "<subscription-public-key xmlns=\"$NS_WEBDAV_PUSH\"/>")
        )
    }

}
