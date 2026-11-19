package org.aevix.impl.core

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory

/** One finite worker per non-empty batch; no idle thread or shared executor pool. */
internal class OnDemandSerialExecutor(
    private val threadFactory: ThreadFactory,
    private val onFailure: (Throwable) -> Unit,
) : Executor {
    private val lock = Any()
    private var queue: ArrayDeque<Runnable>? = null
    private var running = false

    override fun execute(command: Runnable) {
        synchronized(lock) {
            val pending = queue ?: ArrayDeque<Runnable>().also { queue = it }
            pending.addLast(command)
            if (running) return
            running = true
            try {
                val thread =
                    threadFactory.newThread(Runnable { drain() })
                        ?: throw IllegalStateException("Hot reload thread factory returned null")
                thread.start()
            } catch (failure: Throwable) {
                pending.removeLast()
                queue = null
                running = false
                throw failure
            }
        }
    }

    private fun drain() {
        while (true) {
            val next =
                synchronized(lock) {
                    val pending = queue
                    if (pending == null || pending.isEmpty()) {
                        queue = null
                        running = false
                        return
                    }
                    pending.removeFirst()
                }
            Thread.interrupted()
            try {
                next.run()
            } catch (failure: Throwable) {
                try {
                    onFailure(failure)
                } catch (_: Throwable) {
                    // A broken reporter must not strand accepted work.
                }
            }
        }
    }
}
