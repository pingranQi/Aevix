package org.aevix.daemon.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InvalidClassException
import java.io.InvalidObjectException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import org.aevix.daemon.BuildConfig
import org.aevix.daemon.system.*
import org.aevix.daemon.system.*

private const val TAG = "AevixPreferenceStore"
private const val INVALIDATE_ART_INLINE_HOOKS_KEY_PREFIX = "invalidate_art_inline_hooks:"
const val SYSTEM_UI_VIRTUAL_PACKAGE = "system"
const val SYSTEM_UI_PROCESS = "system:ui"
private const val MAX_PREFERENCE_BLOB_SIZE = 1024 * 1024

private val allowedPreferenceClasses =
    setOf(
        "java.lang.Boolean",
        "java.lang.Float",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Number",
        "java.lang.String",
        "java.util.Collections\$EmptySet",
        "kotlin.collections.EmptySet",
        "java.util.Collections\$SingletonSet",
        "java.util.HashSet",
        "java.util.LinkedHashSet",
    )

/**
 * RemotePreferences has SharedPreferences value semantics: scalar primitives, strings and
 * string sets. Do not invoke arbitrary Serializable implementations in the root daemon.
 */
internal fun isSupportedPreferenceValue(value: Any?): Boolean =
    when (value) {
      is String, is Boolean, is Int, is Long, is Float -> true
      is Set<*> -> value.javaClass.name in allowedPreferenceClasses && value.all { it is String }
      else -> false
    }

internal fun serializePreferenceValue(value: Any): ByteArray {
  require(isSupportedPreferenceValue(value)) {
    "Unsupported remote preference value type: ${value.javaClass.name}"
  }
  val bytes = ByteArrayOutputStream()
  ObjectOutputStream(bytes).use { it.writeObject(value) }
  return bytes.toByteArray()
}

internal fun deserializePreferenceValue(blob: ByteArray): Any {
  if (blob.size > MAX_PREFERENCE_BLOB_SIZE) {
    throw InvalidObjectException("Remote preference exceeds the size limit")
  }
  val value =
      SafePreferenceObjectInputStream(ByteArrayInputStream(blob)).use { input ->
        input.readObject()
      }
  if (!isSupportedPreferenceValue(value)) {
    throw InvalidObjectException("Unsupported remote preference value")
  }
  return value
}

private class SafePreferenceObjectInputStream(input: ByteArrayInputStream) :
    ObjectInputStream(input) {
  override fun resolveClass(desc: ObjectStreamClass): Class<*> {
    if (desc.name !in allowedPreferenceClasses) {
      throw InvalidClassException("Rejected remote preference class", desc.name)
    }
    return super.resolveClass(desc)
  }

  override fun resolveProxyClass(interfaces: Array<out String>): Class<*> {
    throw InvalidClassException("Serialized proxy classes are not allowed")
  }
}

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
        .use { cursor ->
          while (cursor.moveToNext()) {
            val key = cursor.getString(0)
            val blob = cursor.getBlob(1)
            runCatching { deserializePreferenceValue(blob) }
                .onSuccess { result[key] = it }
                .onFailure {
                  Log.w(TAG, "Ignoring invalid preference $packageName/$userId/$group/$key", it)
                }
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
        if (value == null) {
          db.delete(
              "configs",
              "module_pkg_name=? AND user_id=? AND `group`=? AND `key`=?",
              arrayOf(moduleName, userId.toString(), group, key))
        } else {
          val values =
              ContentValues().apply {
                put("`group`", group)
                put("`key`", key)
                put("data", serializePreferenceValue(value))
                put("module_pkg_name", moduleName)
                put("user_id", userId.toString())
              }
          db.insertWithOnConflict("configs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
      getModulePrefs("lspd", 0, "config")
          .asSequence()
          .filter { (key, value) -> key.startsWith(INVALIDATE_ART_INLINE_HOOKS_KEY_PREFIX) && value == true }
          .map { (key, _) -> key.removePrefix(INVALIDATE_ART_INLINE_HOOKS_KEY_PREFIX) }
          .filter { it.isNotBlank() }
          .toSet()

  fun setInvalidateArtInlineHooks(packageName: String, enabled: Boolean): Boolean {
    val normalized = packageName.trim()
    if (normalized.isEmpty()) return false
    updateModulePref("lspd", 0, "config", INVALIDATE_ART_INLINE_HOOKS_KEY_PREFIX + normalized,
        if (enabled) true else null)
    return true
  }

  fun shouldInvalidateArtInlineHooks(processName: String, uid: Int): Boolean {
    val configured = getInvalidateArtInlineHookPackages()
    if (configured.isEmpty()) return false
    if (InlineHookProcessPolicy.matchesSystemUiVirtualPackage(configured, processName, uid)) return true
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
