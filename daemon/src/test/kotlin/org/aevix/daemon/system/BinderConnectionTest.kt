package org.aevix.daemon.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinderConnectionTest {
  @Test
  fun deathOfCurrentBinderClearsService() {
    val connection = BinderConnection<String, Any>()
    val binder = Any()
    connection.publish(binder, "service")
    connection.clearIfCurrent(binder)
    assertNull(connection.value)
  }

  @Test
  fun delayedDeathOfPreviousBinderDoesNotClearReplacement() {
    val connection = BinderConnection<String, Any>()
    val oldBinder = Any()
    val newBinder = Any()
    connection.publish(oldBinder, "old")
    connection.publish(newBinder, "new")
    connection.clearIfCurrent(oldBinder)
    assertEquals("new", connection.value)
  }
}
