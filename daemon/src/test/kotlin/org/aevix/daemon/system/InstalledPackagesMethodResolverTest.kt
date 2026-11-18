package org.aevix.daemon.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledPackagesMethodResolverTest {
  class PlatformMethods {
    fun getInstalledPackages(flags: Int, userId: Int): String = "int"
    fun getInstalledPackages(flags: Long, userId: Int): String = "long"
  }

  class Android17Methods {
    fun getInstalledPackages(flags: Long, userId: Int): String = "long"
  }

  @Test
  fun prefersLongFlagsOnAndroid13AndLaterIncludingAndroid17() {
    for (sdk in listOf(33, 36, 37)) {
      val method = InstalledPackagesMethodResolver.resolve(PlatformMethods::class.java, sdk)
      assertNotNull(method)
      assertEquals(Long::class.javaPrimitiveType, method!!.parameterTypes[0])
    }
    assertNotNull(InstalledPackagesMethodResolver.resolve(Android17Methods::class.java, 37))
  }

  @Test
  fun prefersIntFlagsOnOlderAndroid() {
    val method = InstalledPackagesMethodResolver.resolve(PlatformMethods::class.java, 32)
    assertNotNull(method)
    assertEquals(Int::class.javaPrimitiveType, method!!.parameterTypes[0])
  }

  @Test
  fun returnsNullWhenNoCompatibleOverloadExists() {
    assertNull(InstalledPackagesMethodResolver.resolve(String::class.java, 37))
  }
}
