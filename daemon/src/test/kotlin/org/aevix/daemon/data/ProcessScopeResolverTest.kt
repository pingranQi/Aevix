package org.aevix.daemon.data

import org.aevix.daemon.system.appUidOf
import org.aevix.ipc.LoadedModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProcessScopeResolverTest {
    @Test
    fun pccUidMapsBackToApplicationUidAndPreservesUser() {
        assertEquals(10202, appUidOf(30202))
        assertEquals(110202, appUidOf(130202))
        assertEquals(10001, appUidOf(10001))
    }

    @Test
    fun pccProcessUsesOwningApplicationScope() {
        val modules = mutableListOf<LoadedModule>()
        val scopes = mapOf(ProcessScope("com.example.app", 10202) to modules)

        assertSame(modules, resolveModulesForProcess(scopes, "com.example.app", 30202))
    }

    @Test
    fun isolatedServiceInheritsExplicitBaseScopeForSameUser() {
        val modules = mutableListOf<LoadedModule>()
        val scopes = mapOf(ProcessScope("com.example.app", 10001) to modules)

        assertSame(
            modules,
            resolveModulesForProcess(
                scopes,
                "com.example.app:isolated_service:com.example.Service",
                99001,
            ),
        )
    }

    @Test
    fun isolatedServiceCannotInheritAnotherUsersScope() {
        val scopes = mapOf(ProcessScope("com.example.app", 10001) to emptyList<LoadedModule>())

        assertNull(
            resolveModulesForProcess(
                scopes,
                "com.example.app:isolated_service:com.example.Service",
                199001,
            ),
        )
    }

    @Test
    fun unscopedIsolatedProcessIsNeverInjected() {
        assertNull(
            resolveModulesForProcess(
                emptyMap(),
                "com.example.app:isolated_service:com.example.Service",
                99001,
            ),
        )
    }

    @Test
    fun ordinaryGeneratedProcessNameDoesNotInheritBaseScope() {
        val modules = mutableListOf<LoadedModule>()
        val scopes = mapOf(ProcessScope("com.example.app", 10001) to modules)

        assertNull(resolveModulesForProcess(scopes, "com.example.app:remote", 10001))
    }

    @Test
    fun isolatedUidWithoutGeneratedProcessSuffixRemainsExcluded() {
        val modules = mutableListOf<LoadedModule>()
        val scopes = mapOf(ProcessScope("com.example.app", 10001) to modules)

        assertNull(resolveModulesForProcess(scopes, "com.example.app", 99001))
    }

    @Test
    fun exactIsolatedScopeTakesPrecedenceOverInheritedBaseScope() {
        val baseModules = mutableListOf<LoadedModule>()
        val exactModules = mutableListOf<LoadedModule>()
        val process = "com.example.app:isolated_service:com.example.Service"
        val scopes = mapOf(
            ProcessScope("com.example.app", 10001) to baseModules,
            ProcessScope(process, 99001) to exactModules,
        )

        assertSame(exactModules, resolveModulesForProcess(scopes, process, 99001))
    }
}
