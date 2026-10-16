package com.r3dzf.nerqo

import org.junit.Assert.*
import org.junit.Test

class TransferControlTest {
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
