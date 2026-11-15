package org.aevix.daemon.data

import org.aevix.daemon.system.PER_USER_RANGE
import org.aevix.daemon.system.appUidOf

private const val FIRST_APP_ZYGOTE_ISOLATED_UID = 90000
private const val LAST_ISOLATED_UID = 99999

/** The exact configured scope row that supplied a process's module list. */
internal data class ProcessScopeMatch<T>(
  val scope: ProcessScope,
  val modules: List<T>,
)

/**
 * Resolve a process scope without widening ordinary app processes.
 *
 * Exact process rows take precedence. PCC identities are then normalized to their owning app UID.
 * Only an isolated UID with a well-formed `package:suffix` process name may inherit a base
 * package scope, and only when exactly one matching row exists for the same Android user.
 * Ambiguous or missing base scopes fail closed.
 */
internal fun <T> resolveProcessScope(
  scopes: Map<ProcessScope, List<T>>,
  requested: ProcessScope,
): ProcessScopeMatch<T>? {
  scopes[requested]?.let { return ProcessScopeMatch(requested, it) }

  val normalized = requested.copy(uid = appUidOf(requested.uid))
  if (normalized != requested) {
    scopes[normalized]?.let { return ProcessScopeMatch(normalized, it) }
  }

  val appId = normalized.uid % PER_USER_RANGE
  if (appId !in FIRST_APP_ZYGOTE_ISOLATED_UID..LAST_ISOLATED_UID) return null

  val separator = requested.processName.indexOf(':')
  if (separator <= 0 || separator == requested.processName.lastIndex) return null
  val basePackage = requested.processName.substring(0, separator)
  val suffix = requested.processName.substring(separator + 1)
  if (suffix.contains(':')) return null

  val userId = normalized.uid / PER_USER_RANGE
  val candidates = scopes.entries.filter { (candidate, _) ->
    candidate.processName == basePackage && candidate.uid / PER_USER_RANGE == userId
  }
  if (candidates.size != 1) return null

  val (matchedScope, modules) = candidates.single()
  return ProcessScopeMatch(matchedScope, modules)
}