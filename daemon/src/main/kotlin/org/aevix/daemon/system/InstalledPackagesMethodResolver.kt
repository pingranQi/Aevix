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
    // Android 13 changed flags to long; Android 17 keeps the long-flags hidden entry point.
    val preferred = if (sdkInt >= 33) Long::class.javaPrimitiveType else Int::class.javaPrimitiveType
    val candidates =
        type.declaredMethods.filter { method ->
          val params = method.parameterTypes
          method.name == "getInstalledPackages" &&
              params.size == 2 &&
              (params[0] == Int::class.javaPrimitiveType ||
                  params[0] == Long::class.javaPrimitiveType) &&
              params[1] == Int::class.javaPrimitiveType
        }
    val selected =
        candidates.firstOrNull { it.parameterTypes[0] == preferred }
            ?: candidates.firstOrNull()
            ?: return null
    return runCatching { selected.apply { isAccessible = true } }.getOrNull()
  }
}
