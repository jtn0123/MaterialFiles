/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state a one-shot action goes through, which view models expose and views switch on. */
class ActionStateTest {
    private val ready = ActionState.Ready<String, Int>()
    private val running = ActionState.Running<String, Int>("argument")
    private val success = ActionState.Success("argument", 1)
    private val error = ActionState.Error<String, Int>("argument", RuntimeException())

    @Test
    fun onlyReadyIsReady() {
        assertTrue(ready.isReady)
        assertFalse(running.isReady)
        assertFalse(success.isReady)
        assertFalse(error.isReady)
    }

    @Test
    fun onlyRunningIsRunning() {
        assertFalse(ready.isRunning)
        assertTrue(running.isRunning)
        assertFalse(success.isRunning)
        assertFalse(error.isRunning)
    }

    @Test
    fun successAndErrorAreFinished() {
        assertFalse(ready.isFinished)
        assertFalse(running.isFinished)
        assertTrue(success.isFinished)
        assertTrue(error.isFinished)
    }

    /** Ready carries nothing, so any two of them are the same state to an observer. */
    @Test
    fun readyStatesAreAllEqual() {
        assertEquals(ready, ActionState.Ready<String, Int>())
        assertEquals(ready.hashCode(), ActionState.Ready<String, Int>().hashCode())
        assertEquals(ready, ready)
        assertNotEquals(ready, running)
        assertFalse(ready.equals(null))
    }

    @Test
    fun theOthersCompareByWhatTheyCarry() {
        assertEquals(running, ActionState.Running<String, Int>("argument"))
        assertNotEquals(running, ActionState.Running<String, Int>("other"))
        assertEquals(success, ActionState.Success("argument", 1))
        assertNotEquals(success, ActionState.Success("argument", 2))
        assertEquals("argument", error.argument)
        assertEquals(running.argument, success.argument)
    }
}
