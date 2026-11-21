package org.aevix.daemon.data

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineHookProcessPolicyTest {
  @Test
  fun systemServerIsNeverEligible() {
    assertFalse(InlineHookProcessPolicy.mayInvalidate("system", Process.SYSTEM_UID))
  }

  @Test
  fun systemUiCanBeSelectedSeparatelyFromSystemServer() {
    assertTrue(InlineHookProcessPolicy.mayInvalidate("system:ui", Process.SYSTEM_UID))
    assertTrue(
        InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
            setOf(SYSTEM_UI_VIRTUAL_PACKAGE), SYSTEM_UI_PROCESS, Process.SYSTEM_UID))
    assertFalse(
        InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
            setOf(SYSTEM_UI_VIRTUAL_PACKAGE), "system", Process.SYSTEM_UID))
  }

  @Test
  fun packageAndComponentProcessesMustMatchUid() {
    val componentProcesses = setOf("com.example.app", "com.example.app:remote")
    assertTrue(
        InlineHookProcessPolicy.matchesPackage(
            10123, 10123, "com.example.app:remote", "com.example.app", componentProcesses))
    assertFalse(
        InlineHookProcessPolicy.matchesPackage(
            10123, 10124, "com.example.app:remote", "com.example.app", componentProcesses))
    assertFalse(
        InlineHookProcessPolicy.matchesPackage(
            10123, 10123, "com.example.other", "com.example.app", componentProcesses))
  }
}
