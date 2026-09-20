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
import at.bitfire.dav4jvm.XmlUtils
import java.io.StringReader

open class PropertyTest {

    companion object {

        fun parseProperty(s: String): List<Property> {
            val parser = XmlUtils.newPullParser()
            parser.setInput(StringReader("<test>$s</test>"))
            parser.nextTag()    // move into <test>
            return Property.parse(parser)
        }

        /**
         * Parses a single property with the given factory. Needed for properties which are not
         * registered in the [at.bitfire.dav4jvm.PropertyRegistry] (they only appear as children
         * of other properties).
         */
        fun parseProperty(factory: PropertyFactory, s: String): Property {
            val parser = XmlUtils.newPullParser()
            parser.setInput(StringReader(s))
            parser.nextTag()    // move into the property element
            return factory.create(parser)
        }

    }

}