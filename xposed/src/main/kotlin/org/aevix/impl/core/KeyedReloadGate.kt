package org.aevix.impl.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Non-blocking per-key gate for lifecycle operations.
 *
 * A held key rejects a concurrent operation instead of making a Binder worker wait behind module
 * code. Different keys remain independent and can progress concurrently.
 */
internal class KeyedReloadGate<K : Any> {
  private val locks = ConcurrentHashMap<K, Semaphore>()

  fun tryAcquire(key: K): AutoCloseable? {
    val semaphore = locks.computeIfAbsent(key) { Semaphore(1) }
    if (!semaphore.tryAcquire()) return null
    return AutoCloseable { semaphore.release() }
  }
}
