package org.aevix.daemon.data

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineHookProcessPolicyTest {
    @Test
    fun syntheticSystemPackageMatchesOnlySystemUi() {
        val configured = setOf(SYSTEM_UI_VIRTUAL_PACKAGE)
        assertTrue(
            InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
                configured, SYSTEM_UI_PROCESS, Process.SYSTEM_UID
            )
        )
        assertFalse(
            InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
                configured, "system", Process.SYSTEM_UID
            )
        )
        assertFalse(
            InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
                configured, SYSTEM_UI_PROCESS, 10001
            )
        )
    }

    @Test
    fun ordinaryPackageMatchesOnlyItsUidAndDeclaredProcesses() {
        val components = setOf("com.example.app:remote", "com.example.app:service")
        assertTrue(
            InlineHookProcessPolicy.matchesPackage(
                11001, 11001, "com.example.app:service", "com.example.app", components
            )
        )
        assertTrue(
            InlineHookProcessPolicy.matchesPackage(
                11001, 11001, "com.example.app", "com.example.app", components
            )
        )
        assertFalse(
            InlineHookProcessPolicy.matchesPackage(
                11001, 21001, "com.example.app:service", "com.example.app", components
            )
        )
        assertFalse(
            InlineHookProcessPolicy.matchesPackage(
                11001, 11001, "com.example.other", "com.example.app", components
            )
        )
    }

    @Test
    fun systemServerIsNeverEligibleButSystemUiIs() {
        assertFalse(InlineHookProcessPolicy.mayInvalidate("system", Process.SYSTEM_UID))
        assertTrue(InlineHookProcessPolicy.mayInvalidate(SYSTEM_UI_PROCESS, Process.SYSTEM_UID))
        assertTrue(InlineHookProcessPolicy.mayInvalidate("com.example.app", 11001))
    }
}
