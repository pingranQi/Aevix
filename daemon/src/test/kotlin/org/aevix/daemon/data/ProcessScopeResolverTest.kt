package org.aevix.daemon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessScopeResolverTest {
  @Test
  fun exactProcessScopeTakesPrecedenceOverPackageInheritance() {
    val base = ProcessScope("com.example.app", 10123)
    val isolated = ProcessScope("com.example.app:worker", 99001)
    val scopes = mapOf(base to listOf("base-module"), isolated to listOf("isolated-module"))

    assertEquals(listOf("isolated-module"), resolveProcessScope(scopes, isolated)?.modules)
  }

  @Test
  fun isolatedServiceInheritsExplicitBasePackageScopeForSameUser() {
    val base = ProcessScope("com.example.app", 10123)
    val isolated = ProcessScope("com.example.app:isolated", 99001)
    val match = resolveProcessScope(mapOf(base to listOf("module-a")), isolated)

    assertEquals(base, match?.scope)
    assertEquals(listOf("module-a"), match?.modules)
  }

  @Test
  fun inheritedScopeDoesNotCrossAndroidUsers() {
    val baseUserZero = ProcessScope("com.example.app", 10123)
    val isolatedUserTwo = ProcessScope("com.example.app:isolated", 299001)

    assertNull(resolveProcessScope(mapOf(baseUserZero to listOf("module-a")), isolatedUserTwo))
  }

  @Test
  fun ordinaryUidCannotInheritThroughAProcessSuffix() {
    val base = ProcessScope("com.example.app", 10123)
    val ordinary = ProcessScope("com.example.app:remote", 10123)

    assertNull(resolveProcessScope(mapOf(base to listOf("module-a")), ordinary))
  }

  @Test
  fun isolatedUidWithoutWellFormedPackageSuffixCannotInherit() {
    val base = ProcessScope("com.example.app", 10123)
    val missingSuffix = ProcessScope("com.example.app:", 99001)
    val noSuffix = ProcessScope("com.example.app", 99001)
    val multipleSeparators = ProcessScope("com.example.app:one:two", 99001)
    val scopes = mapOf(base to listOf("module-a"))

    assertNull(resolveProcessScope(scopes, missingSuffix))
    assertNull(resolveProcessScope(scopes, noSuffix))
    assertNull(resolveProcessScope(scopes, multipleSeparators))
  }

  @Test
  fun ambiguousBaseRowsFailClosed() {
    val baseOne = ProcessScope("com.example.app", 10123)
    val baseTwo = ProcessScope("com.example.app", 10124)
    val isolated = ProcessScope("com.example.app:isolated", 99001)

    assertNull(resolveProcessScope(mapOf(baseOne to listOf("one"), baseTwo to listOf("two")), isolated))
  }

  @Test
  fun pccUidIsNormalizedBeforeResolvingTheSameUserAppScope() {
    val base = ProcessScope("com.example.app", 110123)
    val pcc = ProcessScope("com.example.app", 130123)

    assertEquals(base, resolveProcessScope(mapOf(base to listOf("module-a")), pcc)?.scope)
  }
}