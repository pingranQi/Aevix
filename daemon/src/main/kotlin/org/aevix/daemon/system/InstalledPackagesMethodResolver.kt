package org.aevix.daemon.system

import java.lang.reflect.Method

/**
 * Resolves the hidden IPackageManager#getInstalledPackages overload without assuming that a
 * particular Android release exposes only one primitive flag width.
 *
 * Android 13 changed package flags from int to long. Some platform builds retain both overloads;
 * prefer the SDK-appropriate one, then accept the other primitive numeric overload as a fallback.
 * The second argument remains the user id. No invocation is attempted when the shape is unknown.
 */
internal object InstalledPackagesMethodResolver {
  fun resolve(type: Class<*>, sdkInt: Int): Method? {
    // Android 17 (API 37) moved this call behind a hidden reflective entry point. Earlier
    // releases must use their generated Binder interface methods directly.
    if (sdkInt < 37) return null
    val preferred = Long::class.javaPrimitiveType
    val candidates =
        type.declaredMethods.filter { method ->
          val params = method.parameterTypes
          method.name == "getInstalledPackages" &&
              params.size == 2 &&
              params[0] == Long::class.javaPrimitiveType &&
              params[1] == Int::class.javaPrimitiveType
        }
    val selected =
        candidates.firstOrNull { it.parameterTypes[0] == preferred }
            ?: candidates.firstOrNull()
            ?: return null
    return runCatching { selected.apply { isAccessible = true } }.getOrNull()
  }
}
