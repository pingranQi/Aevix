package org.aevix.daemon.utils

import android.os.SharedMemory

object ObfuscationManager {
  /**
   * Rewrites the prefixes this boot's table covers, in a DEX already mapped into [memory].
   *
   * [obfuscateFramework] controls the framework's own prefixes; [apiProtection] independently
   * controls the Xposed API prefixes. API protection can therefore be enabled without changing
   * general framework-name obfuscation.
   */
  @JvmStatic
  external fun obfuscateDex(
      memory: SharedMemory,
      obfuscateFramework: Boolean,
      apiProtection: Boolean
  ): SharedMemory

  /** The prefix table in effect, dotted form to dotted form, for the same [apiProtection] answer. */
  @JvmStatic external fun getSignatures(apiProtection: Boolean): Map<String, String>
}
