package com.r3dzf.nerqo

import org.junit.Assert.*
import org.junit.Test

class TransferControlTest {
    @Test fun uiStopReturnsWhileAProviderCloseIsBlocked() {
        val control = TransferControl()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        control.attach("blocked-provider") {
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            closed.countDown()
        }
        try {
            control.interrupt(TransferStop.PAUSE, closeAsync = true)
            assertEquals(TransferStop.PAUSE, control.stop)
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertThrows(TransferPaused::class.java) { control.checkpoint() }
        } finally { release.countDown() }
        assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }
    @Test fun pauseAbortsConnectionAndStopsAtCheckpoint() {
        val control = TransferControl()
        var disconnected = false
        control.attach("socket") { disconnected = true }
        control.interrupt(TransferStop.PAUSE)
        assertTrue(disconnected)
        assertThrows(TransferPaused::class.java) { control.checkpoint() }
    }
    @Test fun cancelSupersedesPauseAndCannotBeDowngraded() {
        val control = TransferControl()
        control.interrupt(TransferStop.PAUSE)
        control.interrupt(TransferStop.CANCEL)
        control.interrupt(TransferStop.PAUSE)
        assertThrows(TransferCancelled::class.java) { control.checkpoint() }
        assertThrows(TransferCancelled::class.java) { control.attach("later") { fail("must not register") } }
    }
    @Test fun pauseAlsoClosesBlockingSourceOrDestinationResource() {
        val control = TransferControl()
        var closed = false
        control.resource(java.io.Closeable { closed = true }) {
            control.interrupt(TransferStop.PAUSE)
            assertTrue(closed)
            assertThrows(TransferPaused::class.java) { control.checkpoint() }
        }
    }
    @Test fun detachedConnectionIsNotAbortedAndScopeIsRestored() {
        val control = TransferControl()
        control.run {
            assertSame(control, TransferControl.current.get())
            control.attach("done") { fail("Already detached") }
            control.detach("done")
            control.interrupt(TransferStop.PAUSE)
        }
        assertNull(TransferControl.current.get())
    }
}
