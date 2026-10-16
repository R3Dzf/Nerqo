package com.r3dzf.nerqo

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class TransferStop { PAUSE, CANCEL }
internal class TransferPaused : IOException("Transfer paused")
internal class TransferCancelled : IOException("Transfer cancelled")

/** Cooperative checkpoints plus immediate interruption of an active network request. */
internal class TransferControl(private val onPhase: (String) -> Unit = {}) {
    @Volatile var stop: TransferStop? = null
        private set
    private val gate = Any()
    private val connections = mutableMapOf<Any, () -> Unit>()

    fun interrupt(reason: TransferStop, closeAsync: Boolean = false) {
        val actions = synchronized(gate) {
            if (stop != TransferStop.CANCEL) stop = reason
            connections.values.toList()
        }
        val close = { actions.forEach { runCatching(it) } }
        // Some providers and HTTP implementations block close() behind a read.
        // UI callers set the stop flag synchronously, then close off the UI thread.
        if (closeAsync && actions.isNotEmpty()) Thread(close, "Nerqo-transfer-stop").apply { isDaemon = true; start() }
        else close()
    }
    fun <T> resource(value: java.io.Closeable, action: () -> T): T {
        try {
            attach(value) { value.close() }
            return action()
        } finally { detach(value) }
    }
    fun phase(label: String) { onPhase(label) }
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
