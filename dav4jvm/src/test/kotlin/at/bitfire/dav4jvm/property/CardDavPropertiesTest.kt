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

import at.bitfire.dav4jvm.property.carddav.AddressData
import at.bitfire.dav4jvm.property.carddav.AddressbookDescription
import at.bitfire.dav4jvm.property.carddav.AddressbookHomeSet
import at.bitfire.dav4jvm.property.carddav.MaxResourceSize
import at.bitfire.dav4jvm.property.carddav.SupportedAddressData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CARD = "xmlns=\"urn:ietf:params:xml:ns:carddav\" xmlns:D=\"DAV:\""

class CardDavPropertiesTest: PropertyTest() {

    @Test
    fun testSimpleProperties() {
        assertEquals("BEGIN:VCARD", (parseProperty(
            "<address-data $CARD>BEGIN:VCARD</address-data>").first() as AddressData).card)
        assertEquals("My contacts", (parseProperty(
            "<addressbook-description $CARD>My contacts</addressbook-description>")
            .first() as AddressbookDescription).description)
        assertEquals(102400L, (parseProperty(
            "<max-resource-size $CARD>102400</max-resource-size>").first() as MaxResourceSize).maxSize)
        assertEquals(listOf("/carddav/"), (parseProperty(
            "<addressbook-home-set $CARD><D:href>/carddav/</D:href></addressbook-home-set>")
            .first() as AddressbookHomeSet).hrefs)
    }

    @Test
    fun testSupportedAddressData() {
        val types = parseProperty("<supported-address-data $CARD>" +
            "<address-data-type content-type=\"text/vcard\" version=\"3.0\"/>" +
            "<address-data-type content-type=\"text/vcard\" version=\"4.0\"/>" +
            "<address-data-type content-type=\"application/vcard+json\" version=\"4.0\"/>" +
            "</supported-address-data>").first() as SupportedAddressData
        assertTrue(types.hasVCard4())
        assertTrue(types.hasJCard())
        assertEquals(3, types.types.size)
        assertTrue(types.toString().contains("text/vcard; version=4.0"))

        val vCard3 = parseProperty("<supported-address-data $CARD>" +
            "<address-data-type content-type=\"text/vcard\" version=\"3.0\"/>" +
            "</supported-address-data>").first() as SupportedAddressData
        assertFalse(vCard3.hasVCard4())
        assertFalse(vCard3.hasJCard())
    }

    @Test
    fun testEmptyElementsGiveDefaults() {
        assertEquals(AddressbookDescription(), parseProperty("<addressbook-description $CARD/>").first())
        assertEquals(
            SupportedAddressData().types,
            (parseProperty("<supported-address-data $CARD/>").first() as SupportedAddressData).types
        )
    }

}
