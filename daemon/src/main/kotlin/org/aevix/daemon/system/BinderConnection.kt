package org.aevix.daemon.system

/**
 * Atomically associates a service proxy with the Binder that produced it.
 *
 * Kept generic so the identity rule can be regression-tested without an Android Binder runtime.
 */
internal class BinderConnection<T : Any, B : Any> {
  private data class Connected<T : Any, B : Any>(val binder: B, val value: T)

  @Volatile private var connected: Connected<T, B>? = null

  val value: T?
    get() = connected?.value

  @Synchronized
  fun publish(binder: B, value: T) {
    connected = Connected(binder, value)
  }

  @Synchronized
  fun clearIfCurrent(deadBinder: B) {
    if (connected?.binder === deadBinder) connected = null
  }
}
