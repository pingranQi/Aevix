package org.aevix.daemon.data

import java.nio.file.Files
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApkFingerprintTest {
  @Test
  fun versionCodeIsPartOfTheFingerprint() {
    val apk = Files.createTempFile("aevix-module", ".apk").toFile()
    try {
      apk.writeBytes(byteArrayOf(1, 2, 3))
      val first = ApkFingerprint.read(apk, 1)
      val upgraded = ApkFingerprint.read(apk, 2)
      assertNotNull(first)
      assertNotEquals(first, upgraded)
    } finally {
      apk.delete()
    }
  }

  @Test
  fun inPlaceContentChangeInvalidatesFingerprint() {
    val apk = Files.createTempFile("aevix-module", ".apk").toFile()
    try {
      apk.writeBytes(byteArrayOf(1, 2, 3))
      val first = ApkFingerprint.read(apk, 7)
      apk.writeBytes(byteArrayOf(1, 2, 3, 4))
      val second = ApkFingerprint.read(apk, 7)
      assertNotNull(first)
      assertNotEquals(first, second)
    } finally {
      apk.delete()
    }
  }

  @Test
  fun missingApkCannotBeReused() {
    val directory = Files.createTempDirectory("aevix-missing")
    assertNull(ApkFingerprint.read(directory.resolve("missing.apk").toFile(), 1))
    directory.toFile().delete()
  }
}
