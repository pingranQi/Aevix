# Aevix inline-hook and Manager device smoke matrix

This checklist requires rooted Android devices with the Aevix Zygisk module installed. Host CI
builds and contract tests do not prove ART mapping or Zygisk process-injection behavior.

## ABI matrix

Run all scenarios on an arm64-v8a device and an armeabi-v7a device (or 32-bit ARM userspace).
Record Android API level, build fingerprint, module commit, Manager version hash, and backend.

## Hook and module scenarios

1. Install this branch build, reboot, and verify daemon/Manager connectivity.
2. Select Dobby; enable a test module that hooks a known Java method; verify before/after callbacks.
3. Disable that module and verify its hook is removed. Repeat with ShadowHook selected.
4. Install two hooks at the same native target, remove them in reverse order, and verify each
   original-call trampoline remains valid until its matching unhook succeeds.
5. Force an unhook failure in a test build; verify the stub remains registered for retry and that
   DobbyDestroy is not called for a ShadowHook record.
6. Keep ART compatibility disabled (default), then opt in a non-system test app. Verify the
   invalidation start and completion logs.
7. Inject a failure after one executable segment is replaced. Compare original bytes and
   /proc/self/maps before/after rollback; confirm the process remains usable.

## Manager workflow

1. Install the matching Manager APK and reboot.
2. Launch org.aevix.manager; verify the home screen appears and the process remains alive.
3. Open System Status; verify daemon connection, protocol version, and module state.
4. Change the inline-hook backend, navigate away and back, and verify the selection persists.
5. Enable/disable ART compatibility for a test app; verify package/process selection persists.
6. Force-stop and relaunch Manager; repeat the home, System Status, and configuration checks.

Suggested capture:

    adb logcat -c
    adb shell monkey -p org.aevix.manager 1
    adb logcat -d -b crash
    adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'
