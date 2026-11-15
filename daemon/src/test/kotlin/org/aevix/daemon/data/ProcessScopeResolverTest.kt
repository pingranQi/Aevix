package org.aevix.daemon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessScopeResolverTest {
  @Test
  fun exactProcessScopeTakesPrecedenceOverPackageInheritance() {
    val base = ProcessScope("com.example.app", 10123)
    val isolated = ProcessScope("com.example.app:worker", 99001)
    val runtimeScopes = mapOf(base to listOf("base-module"), isolated to listOf("isolated-module"))
    val explicitScopes = mapOf(base to listOf("base-module"))

    assertEquals(listOf("isolated-module"), resolveProcessScope(runtimeScopes, isolated, explicitScopes)?.modules)
  }

  @Test
  fun isolatedServiceInheritsExplicitBasePackageScopeForSameUser() {
    val base = ProcessScope("com.example.app", 10123)
    val isolated = ProcessScope("com.example.app:isolated", 99001)
    val explicitScopes = mapOf(base to listOf("module-a"))
    val match = resolveProcessScope(explicitScopes, isolated, explicitScopes)

    assertEquals(base, match?.scope)
    assertEquals(listOf("module-a"), match?.modules)
  }

  @Test
  fun derivedLegacySelfScopeIsNotEligibleForInheritance() {
    val derivedLegacyScope = ProcessScope("com.example.app", 10123)
    val isolated = ProcessScope("com.example.app:isolated", 99001)
    val runtimeScopes = mapOf(derivedLegacyScope to listOf("legacy-module"))

    assertNull(resolveProcessScope(runtimeScopes, isolated, emptyMap()))
  }

  @Test
  fun inheritedScopeDoesNotCrossAndroidUsers() {
    val baseUserZero = ProcessScope("com.example.app", 10123)
    val isolatedUserTwo = ProcessScope("com.example.app:isolated", 299001)
    val explicitScopes = mapOf(baseUserZero to listOf("module-a"))

    assertNull(resolveProcessScope(explicitScopes, isolatedUserTwo, explicitScopes))
  }

  @Test
  fun ordinaryUidCannotInheritThroughAProcessSuffix() {
    val base = ProcessScope("com.example.app", 10123)
    val ordinary = ProcessScope("com.example.app:remote", 10123)
    val explicitScopes = mapOf(base to listOf("module-a"))

    assertNull(resolveProcessScope(explicitScopes, ordinary, explicitScopes))
  }

  @Test
  fun isolatedUidWithoutWellFormedPackageSuffixCannotInherit() {
    val base = ProcessScope("com.example.app", 10123)
    val missingSuffix = ProcessScope("com.example.app:", 99001)
    val noSuffix = ProcessScope("com.example.app", 99001)
    val multipleSeparators = ProcessScope("com.example.app:one:two", 99001)
    val explicitScopes = mapOf(base to listOf("module-a"))

    assertNull(resolveProcessScope(explicitScopes, missingSuffix, explicitScopes))
    assertNull(resolveProcessScope(explicitScopes, noSuffix, explicitScopes))
    assertNull(resolveProcessScope(explicitScopes, multipleSeparators, explicitScopes))
  }

  @Test
  fun ambiguousBaseRowsFailClosed() {
    val baseOne = ProcessScope("com.example.app", 10123)
    val baseTwo = ProcessScope("com.example.app", 10124)
    val isolated = ProcessScope("com.example.app:isolated", 99001)
    val explicitScopes = mapOf(baseOne to listOf("one"), baseTwo to listOf("two"))

    assertNull(resolveProcessScope(explicitScopes, isolated, explicitScopes))
  }

  @Test
  fun pccUidIsNormalizedBeforeResolvingTheSameUserAppScope() {
    val base = ProcessScope("com.example.app", 110123)
    val pcc = ProcessScope("com.example.app", 130123)
    val explicitScopes = mapOf(base to listOf("module-a"))

    assertEquals(base, resolveProcessScope(explicitScopes, pcc, explicitScopes)?.scope)
  }
}