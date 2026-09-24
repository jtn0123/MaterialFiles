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

import at.bitfire.dav4jvm.exception.InvalidPropertyException
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetETag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

class PropertyRegistryTest {

    private val brokenName = Property.Name("https://example.com/test", "broken")
    private val invalidName = Property.Name("https://example.com/test", "invalid")

    /** Factory which fails like a parser that stumbles over unexpected XML. */
    private val brokenFactory = object: PropertyFactory {
        override fun getName() = brokenName
        override fun create(parser: XmlPullParser): Property = throw XmlPullParserException("broken")
    }

    /** Factory which rejects the property value. */
    private val invalidFactory = object: PropertyFactory {
        override fun getName() = invalidName
        override fun create(parser: XmlPullParser): Property = throw InvalidPropertyException("invalid")
    }

    private fun parse(s: String): List<Property> {
        val parser = XmlUtils.newPullParser()
        parser.setInput(StringReader("<test>$s</test>"))
        parser.nextTag()
        return Property.parse(parser)
    }

    @Test
    fun testKnownProperty() {
        assertEquals(
            listOf(DisplayName("Test"), GetETag("12345")),
            parse("<displayname xmlns=\"DAV:\">Test</displayname><getetag xmlns=\"DAV:\">12345</getetag>")
        )
    }

    @Test
    fun testUnknownPropertyIsIgnored() {
        assertTrue(parse("<unknown-property xmlns=\"https://example.com/test\">x</unknown-property>").isEmpty())
        assertNull(PropertyRegistry.create(Property.Name("https://example.com/test", "unknown"), XmlUtils.newPullParser()))
    }

    @Test
    fun testBrokenFactoryIsIgnored() {
        // a factory which can't parse its property must not fail the whole response
        PropertyRegistry.register(brokenFactory)
        assertEquals(
            listOf(DisplayName("Test")),
            parse(
                "<broken xmlns=\"https://example.com/test\"/>" +
                "<displayname xmlns=\"DAV:\">Test</displayname>"
            )
        )
    }

    @Test
    fun testInvalidPropertyIsIgnored() {
        PropertyRegistry.register(listOf(invalidFactory))
        assertEquals(
            listOf(DisplayName("Test")),
            parse(
                "<invalid xmlns=\"https://example.com/test\"/>" +
                "<displayname xmlns=\"DAV:\">Test</displayname>"
            )
        )
    }

}
