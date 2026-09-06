/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionExtensionsTest {
    private enum class Bit { A, B, C }

    @Test
    fun removeFirstRemovesAndReturnsTheHead() {
        val list = mutableListOf(1, 2, 3)
        assertEquals(1, list.removeFirst())
        assertEquals(listOf(2, 3), list)
        val map = linkedMapOf("a" to 1, "b" to 2)
        val entry = map.removeFirst()
        assertEquals("a", entry.key)
        assertEquals(mapOf("b" to 2), map)
    }

    @Test
    fun removeFirstWithPredicateRemovesOnlyTheFirstMatch() {
        val list = mutableListOf(1, 2, 3, 4)
        assertEquals(2, list.removeFirst { it % 2 == 0 })
        assertEquals(listOf(1, 3, 4), list)
        assertNull(list.removeFirst { it > 10 })
        assertEquals(listOf(1, 3, 4), list)
        val map = linkedMapOf("a" to 1, "b" to 2, "c" to 2)
        assertEquals("b", map.removeFirst { it.value == 2 }?.key)
        assertEquals(mapOf("a" to 1, "c" to 2), map)
    }

    @Test
    fun enumSetHelpers() {
        assertEquals(EnumSet.noneOf(Bit::class.java), enumSetOf<Bit>())
        assertEquals(EnumSet.of(Bit.A), enumSetOf(Bit.A))
        assertEquals(EnumSet.of(Bit.A, Bit.C), enumSetOf(Bit.A, Bit.C))
        assertEquals(EnumSet.allOf(Bit::class.java), enumSetOf(Bit.A, Bit.B, Bit.C))
        assertEquals(EnumSet.of(Bit.B, Bit.C), listOf(Bit.C, Bit.B).toEnumSet())
        assertEquals(EnumSet.noneOf(Bit::class.java), emptyList<Bit>().toEnumSet())
    }

    @Test
    fun takeIfNotEmptyAndLinkedSet() {
        assertNull(emptyList<Int>().takeIfNotEmpty())
        assertEquals(listOf(1), listOf(1).takeIfNotEmpty())
        assertEquals(listOf(3, 1, 2), listOf(3, 1, 3, 2, 1).toLinkedSet().toList())
    }
}
