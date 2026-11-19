package org.aevix.daemon.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ModuleGenerationCacheTest {
    private class Generation(val packageName: String)
    private class Service(val generation: Generation)

    private fun cache() =
        ModuleGenerationCache<Generation, Service>(
            packageNameOf = { it.packageName },
            generationOf = { it.generation },
            create = ::Service,
        )

    @Test
    fun reusesOnlyTheExactCurrentGeneration() {
        val cache = cache()
        val first = Generation("com.example.module")

        val service = cache.serviceFor(first)
        assertSame(service, cache.serviceFor(first))
        assertEquals(1, cache.size())

        val replacement = Generation("com.example.module")
        val replacementService = cache.serviceFor(replacement)
        assertNotSame(service, replacementService)
        assertSame(replacementService, cache.serviceFor(replacement))
        assertEquals(1, cache.size())
    }

    @Test
    fun forgetsRemovedAndReplacedPackagesButKeepsCurrentIdentity() {
        val cache = cache()
        val current = Generation("com.example.current")
        val replaced = Generation("com.example.replaced")
        val removed = Generation("com.example.removed")
        cache.serviceFor(current)
        cache.serviceFor(replaced)
        cache.serviceFor(removed)

        val replacement = Generation("com.example.replaced")
        cache.forgetReplacedModules(
            mapOf(
                current.packageName to current,
                replacement.packageName to replacement,
            )
        )

        assertEquals(1, cache.size())
        assertSame(cache.serviceFor(current), cache.serviceFor(current))
        assertNotSame(cache.serviceFor(replaced), cache.serviceFor(replacement))
        assertEquals(2, cache.size())
    }

    @Test
    fun emptyCurrentModuleSetReleasesEveryService() {
        val cache = cache()
        cache.serviceFor(Generation("com.example.one"))
        cache.serviceFor(Generation("com.example.two"))

        cache.forgetReplacedModules(emptyMap())

        assertEquals(0, cache.size())
    }
}
