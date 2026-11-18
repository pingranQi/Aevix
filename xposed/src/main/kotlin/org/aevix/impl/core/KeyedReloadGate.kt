package org.aevix.impl.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Non-blocking per-key gate for lifecycle operations.
 *
 * A held key rejects a concurrent operation instead of making a Binder worker wait behind module
 * code. Different keys remain independent and can progress concurrently.
 */
internal class KeyedReloadGate<K : Any> {
  private val locks = ConcurrentHashMap<K, ReentrantLock>()

  fun tryAcquire(key: K): AutoCloseable? {
    val lock = locks.computeIfAbsent(key) { ReentrantLock() }
    if (!lock.tryLock()) return null
    return AutoCloseable { lock.unlock() }
  }
}
