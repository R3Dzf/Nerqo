package com.r3dzf.nerqo

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class TransferStop { PAUSE, CANCEL }
internal class TransferPaused : IOException("Transfer paused")
internal class TransferCancelled : IOException("Transfer cancelled")

/** Cooperative checkpoints plus immediate interruption of an active network request. */
internal class TransferControl {
    @Volatile var stop: TransferStop? = null
        private set
    private val gate = Any()
    private val connections = mutableMapOf<Any, () -> Unit>()

    fun interrupt(reason: TransferStop) {
        val actions = synchronized(gate) {
            if (stop != TransferStop.CANCEL) stop = reason
            connections.values.toList()
        }
        actions.forEach { runCatching(it) }
    }
    fun checkpoint() {
        when (stop) {
            TransferStop.PAUSE -> throw TransferPaused()
            TransferStop.CANCEL -> throw TransferCancelled()
            null -> if (Thread.currentThread().isInterrupted) throw TransferPaused()
        }
    }
    fun attach(key: Any, disconnect: () -> Unit) {
        synchronized(gate) {
            checkpoint()
            connections[key] = disconnect
        }
    }
    fun detach(key: Any) { synchronized(gate) { connections.remove(key) } }
    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try { checkpoint(); return action() }
        finally { current.set(previous); synchronized(gate) { connections.clear() } }
    }
    companion object {
        val current = ThreadLocal<TransferControl?>()
        // Prevent an Activity being recreated from starting a second writer while
        // its predecessor is still closing the same interrupted operation.
        val workerActive = AtomicBoolean(false)
        fun check() { current.get()?.checkpoint() }
    }
}
