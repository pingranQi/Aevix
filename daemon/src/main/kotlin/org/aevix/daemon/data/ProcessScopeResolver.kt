package org.aevix.daemon.data

import org.aevix.daemon.system.FIRST_APP_ZYGOTE_ISOLATED_UID
import org.aevix.daemon.system.LAST_ISOLATED_UID
import org.aevix.daemon.system.PER_USER_RANGE
import org.aevix.daemon.system.appUidOf
import org.aevix.ipc.LoadedModule

/**
 * Resolves a process to the module scopes that are allowed to run in it.
 *
 * Ordinary processes and PCC processes use their exact process name and owning app UID. An
 * isolated service has a transient UID and a generated ":..." process suffix, so it may inherit
 * only the base package's explicitly stored scope, and only for the same Android user.
 */
internal fun resolveModulesForProcess(
    scopes: Map<ProcessScope, List<LoadedModule>>,
    processName: String,
    uid: Int,
): List<LoadedModule>? {
    val normalized = ProcessScope(processName, appUidOf(uid))
    scopes[normalized]?.let { return it }

    val appId = uid % PER_USER_RANGE
    if (appId !in FIRST_APP_ZYGOTE_ISOLATED_UID..LAST_ISOLATED_UID) return null

    val separator = processName.indexOf(':')
    if (separator <= 0 || separator == processName.lastIndex) return null
    val basePackage = processName.substring(0, separator)
    val userId = uid / PER_USER_RANGE

    return scopes.entries.firstOrNull { (candidate, _) ->
        candidate.processName == basePackage && candidate.uid / PER_USER_RANGE == userId
    }?.value
}
