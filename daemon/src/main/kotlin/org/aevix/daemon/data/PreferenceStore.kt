package org.aevix.daemon.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.aevix.daemon.BuildConfig
import org.aevix.daemon.system.*
import org.apache.commons.lang3.SerializationUtilsX

private const val TAG = "AevixPreferenceStore"
private const val ART_INLINE_HOOK_PACKAGES_KEY = "invalidate_art_inline_hook_packages"
const val SYSTEM_UI_VIRTUAL_PACKAGE = "system"
const val SYSTEM_UI_PROCESS = "system:ui"

object PreferenceStore {

  fun getModulePrefs(
      packageName: String,
      userId: Int,
      group: String,
      db: SQLiteDatabase = ModuleDatabase.dbHelper.readableDatabase
  ): Map<String, Any> {
    val result = mutableMapOf<String, Any>()

    db.query(
            "configs",
            arrayOf("`key`", "data"),
            "module_pkg_name = ? AND user_id = ? AND `group` = ?",
            arrayOf(packageName, userId.toString(), group),
            null,
            null,
            null)
        .use { cursor -> // We only close the cursor
          while (cursor.moveToNext()) {
            val key = cursor.getString(0)
            val blob = cursor.getBlob(1)
            val obj = SerializationUtilsX.deserialize<Any>(blob)
            if (obj != null) result[key] = obj
          }
        }
    return result
  }

  fun updateModulePref(moduleName: String, userId: Int, group: String, key: String, value: Any?) {
    updateModulePrefs(moduleName, userId, group, mapOf(key to value))
  }

  fun updateModulePrefs(moduleName: String, userId: Int, group: String, diff: Map<String, Any?>) {
    val db = ModuleDatabase.dbHelper.writableDatabase
    db.beginTransaction()
    try {
      for ((key, value) in diff) {
        if (value is java.io.Serializable) {
          val values =
              ContentValues().apply {
                put("`group`", group)
                put("`key`", key)
                put("data", SerializationUtilsX.serialize(value))
                put("module_pkg_name", moduleName)
                put("user_id", userId.toString())
              }
          db.insertWithOnConflict("configs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
          db.delete(
              "configs",
              "module_pkg_name=? AND user_id=? AND `group`=? AND `key`=?",
              arrayOf(moduleName, userId.toString(), group, key))
        }
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun deleteModulePrefs(moduleName: String, userId: Int? = null, group: String? = null) {
    val db = ModuleDatabase.dbHelper.writableDatabase
    val whereClause = StringBuilder("module_pkg_name = ?")
    val whereArgs = mutableListOf(moduleName)

    if (userId != null) {
      whereClause.append(" AND user_id = ?")
      whereArgs.add(userId.toString())
    }

    if (group != null) {
      whereClause.append(" AND `group` = ?")
      whereArgs.add(group)
    }

    db.delete("configs", whereClause.toString(), whereArgs.toTypedArray())
  }

  fun isStatusNotificationEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_status_notification"] as? Boolean ?: true

  fun setStatusNotification(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_status_notification", enabled)

  // Off by default on a release build. The logcat reader is cheap but never idle, and what it
  // writes exists for bug reports -- something a user opts into by turning this on. A debug build
  // keeps the old default so its author still gets logs without asking.
  fun isVerboseLogEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_verbose_log"] as? Boolean ?: BuildConfig.DEBUG

  fun setVerboseLog(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_verbose_log", enabled)

  fun isModuleLogEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_module_log"] as? Boolean ?: BuildConfig.DEBUG

  fun setModuleLogEnabled(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_module_log", enabled)

  fun isApiProtectionEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_api_protection"] as? Boolean ?: false

  fun setApiProtectionEnabled(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_api_protection", enabled)

  fun isScopeRequestBlocked(pkg: String): Boolean =
      (getModulePrefs("lspd", 0, "config")["scope_request_blocked"] as? Set<*>)?.contains(pkg) ==
          true

  fun getInvalidateArtInlineHookPackages(): Set<String> =
      (getModulePrefs("lspd", 0, "config")[ART_INLINE_HOOK_PACKAGES_KEY] as? Set<*>)
          ?.filterIsInstance<String>()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

  fun setInvalidateArtInlineHooks(packageName: String, enabled: Boolean): Boolean {
    val normalized = packageName.trim()
    if (normalized.isEmpty()) return false
    val packages = getInvalidateArtInlineHookPackages().toMutableSet()
    if (enabled) packages.add(normalized) else packages.remove(normalized)
    updateModulePref("lspd", 0, "config", ART_INLINE_HOOK_PACKAGES_KEY, packages)
    return true
  }

  /**
   * Resolve configured package names against actual app/component process names for this Android user.
   * system_server itself is excluded; the synthetic "system" option only targets system:ui.
   */
  fun shouldInvalidateArtInlineHookProcess(processName: String, uid: Int): Boolean {
    val configured = getInvalidateArtInlineHookPackages()
    if (configured.isEmpty()) return false
    if (InlineHookProcessPolicy.matchesSystemUiVirtualPackage(configured, processName, uid)) {
      return true
    }
    val userId = uid / PER_USER_RANGE
    return configured.any { packageName ->
      if (packageName == SYSTEM_UI_VIRTUAL_PACKAGE) return@any false
      val info = packageManager?.getPackageInfoWithComponents(packageName, MATCH_ALL_FLAGS, userId)
          ?: return@any false
      val appInfo = info.applicationInfo ?: return@any false
      InlineHookProcessPolicy.matchesPackage(
          expectedUid = appInfo.uid,
          actualUid = uid,
          processName = processName,
          applicationProcessName = appInfo.processName,
          componentProcesses = info.fetchProcesses())
    }
  }
}
