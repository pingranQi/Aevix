package org.aevix.daemon.ipc

import java.util.concurrent.ConcurrentHashMap

/**
 * Retains one service for the currently loaded generation of each package, never one per historical
 * generation. The generation comparison is identity-based: a reload creates a new LoadedModule
 * instance even when its package name and version happen to be unchanged.
 */
internal class ModuleGenerationCache<G : Any, S : Any>(
    private val packageNameOf: (G) -> String,
    private val generationOf: (S) -> G,
    private val create: (G) -> S,
) {
    private val services = ConcurrentHashMap<String, S>()

    fun serviceFor(generation: G): S =
        services.compute(packageNameOf(generation)) { _, current ->
            current?.takeIf { generationOf(it) === generation } ?: create(generation)
        }!!

    fun forgetReplacedModules(currentModules: Map<String, G>) {
        services.entries.removeIf { (packageName, service) ->
            currentModules[packageName] !== generationOf(service)
        }
    }

    internal fun size(): Int = services.size
}
