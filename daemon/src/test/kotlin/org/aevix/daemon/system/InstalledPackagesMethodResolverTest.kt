package org.aevix.daemon.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledPackagesMethodResolverTest {
  class Android17Methods {
    fun getInstalledPackages(flags: Long, userId: Int): String = "long"
  }

  class LegacyMethods {
    fun getInstalledPackages(flags: Int, userId: Int): String = "int"
  }

  @Test
  fun resolvesLongFlagsOnlyForAndroid17() {
    assertNull(InstalledPackagesMethodResolver.resolve(Android17Methods::class.java, 36))
    val method = InstalledPackagesMethodResolver.resolve(Android17Methods::class.java, 37)
    assertNotNull(method)
    assertEquals(Long::class.javaPrimitiveType, method!!.parameterTypes[0])
  }

  @Test
  fun rejectsLegacyIntOverloadOnAndroid17() {
    assertNull(InstalledPackagesMethodResolver.resolve(LegacyMethods::class.java, 37))
  }

  @Test
  fun returnsNullWhenAndroid17MethodIsAbsent() {
    assertNull(InstalledPackagesMethodResolver.resolve(String::class.java, 37))
  }
}
