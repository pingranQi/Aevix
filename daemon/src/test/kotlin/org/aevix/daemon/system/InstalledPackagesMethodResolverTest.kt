package org.aevix.daemon.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InstalledPackagesMethodResolverTest {
  class PlatformMethods {
    fun getInstalledPackages(flags: Int, userId: Int): String = "int"
    fun getInstalledPackages(flags: Long, userId: Int): String = "long"
  }

  @Test
  fun prefersLongFlagsOnAndroid13AndLater() {
    val method = InstalledPackagesMethodResolver.resolve(PlatformMethods::class.java, 33)
    assertNotNull(method)
    assertEquals(Long::class.javaPrimitiveType, method!!.parameterTypes[0])
  }

  @Test
  fun prefersIntFlagsOnOlderAndroid() {
    val method = InstalledPackagesMethodResolver.resolve(PlatformMethods::class.java, 32)
    assertNotNull(method)
    assertEquals(Int::class.javaPrimitiveType, method!!.parameterTypes[0])
  }

  @Test
  fun returnsNullWhenNoCompatibleOverloadExists() {
    assertEquals(null, InstalledPackagesMethodResolver.resolve(String::class.java, 37))
  }
}
