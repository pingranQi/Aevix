package org.aevix.impl.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyedReloadGateTest {
  @Test
  fun concurrentReloadForSameModuleIsRejected() {
    val gate = KeyedReloadGate<String>()
    val first = gate.tryAcquire("module")
    assertNotNull(first)
    assertNull(gate.tryAcquire("module"))
    first!!.close()
    val second = gate.tryAcquire("module")
    assertNotNull(second)
    second!!.close()
  }

  @Test
  fun differentModulesDoNotBlockEachOther() {
    val gate = KeyedReloadGate<String>()
    val first = gate.tryAcquire("first")
    val second = gate.tryAcquire("second")
    assertNotNull(first)
    assertNotNull(second)
    first!!.close()
    second!!.close()
  }

  @Test
  fun leaseCanBeReleasedAfterFailurePath() {
    val gate = KeyedReloadGate<String>()
    try {
      gate.tryAcquire("module")!!.use { error("simulated reload failure") }
    } catch (_: IllegalStateException) {
      // The finally/close path is the contract under test.
    }
    assertNotNull(gate.tryAcquire("module"))
  }
}
