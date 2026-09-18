package me.zhanghai.android.files.filejob

import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletingFutureTaskTest {
    @Test fun canceledBeforeExecutionStillCompletesOnce() {
        val count = AtomicInteger()
        val task = CompletingFutureTask({ error("Must not run") }, { count.incrementAndGet() })
        task.cancel(true)
        task.run()
        task.cancel(true)
        assertEquals(1, count.get())
    }

    @Test fun failureStillCompletesOnce() {
        val count = AtomicInteger()
        val task =
            CompletingFutureTask({ throw InterruptedIOException() }, { count.incrementAndGet() })
        task.run()
        task.cancel(true)
        assertEquals(1, count.get())
    }
}
