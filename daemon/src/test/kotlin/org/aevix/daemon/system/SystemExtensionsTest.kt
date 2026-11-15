package org.aevix.daemon.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemExtensionsTest {
  @Test
  fun mapsPccUidsBackToTheirAppUid() {
    assertEquals(10000, appUidOf(30000))
    assertEquals(19999, appUidOf(39999))
  }

  @Test
  fun preservesTheAndroidUserIdWhenMappingPccUids() {
    assertEquals(210000, appUidOf(230000))
    assertEquals(219999, appUidOf(239999))
  }

  @Test
  fun leavesNonPccUidsUnchanged() {
    assertEquals(9999, appUidOf(9999))
    assertEquals(10000, appUidOf(10000))
    assertEquals(29999, appUidOf(29999))
    assertEquals(40000, appUidOf(40000))
    assertEquals(13000, appUidOf(13000))
  }
}