package org.aevix.daemon.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Cheap identity for the installed module artifact.
 *
 * Package name and sourceDir are stable across in-place APK updates. Version code catches normal
 * upgrades; size, mtime and filesystem fileKey catch replacements that reuse both package name and
 * path (including same-version reinstall/rollback). A missing stat is deliberately not reusable.
 */
internal data class ApkFingerprint(
    val versionCode: Long,
    val size: Long,
    val modifiedMillis: Long,
    val fileKey: String?,
) {
  companion object {
    fun read(apk: File, versionCode: Long): ApkFingerprint? {
      return runCatching {
        val attributes = Files.readAttributes(apk.toPath(), BasicFileAttributes::class.java)
        if (!attributes.isRegularFile) null
        else
          ApkFingerprint(
              versionCode,
              attributes.size(),
              attributes.lastModifiedTime().toMillis(),
              attributes.fileKey()?.toString(),
          )
      }.getOrNull()
    }
  }
}
