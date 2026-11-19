#!/usr/bin/env python3
"""Static regression guards for hot-reload callback and DEX resource ownership."""
from pathlib import Path
root = Path(".")
handle = (root / "xposed/src/main/kotlin/org/aevix/impl/hooks/AevixHookHandle.kt").read_text()
manager = (root / "xposed/src/main/kotlin/org/aevix/impl/core/AevixModuleManager.kt").read_text()
loader = (root / "xposed/src/main/kotlin/org/aevix/impl/utils/AevixModuleClassLoader.kt").read_text()
scope = (root / "xposed/src/main/kotlin/org/aevix/impl/utils/DexMemoryScope.kt").read_text()
channel = (root / "xposed/src/main/kotlin/org/aevix/impl/core/AevixProcessChannel.kt").read_text()
executor = (root / "xposed/src/main/kotlin/org/aevix/impl/core/OnDemandSerialExecutor.kt").read_text()
native = (root / "native/src/jni/hook_bridge.cpp").read_text()
assert "private var record: AevixHookRecord? = initialRecord" in handle
assert "private val id = initialRecord.id" in handle
assert "this.record = null" in handle
assert "getId(): String? = id" in handle
assert "WeakReference(old.classLoader)" in manager
assert "retiredLoader.get()?.let" in manager
assert "callbackIsSystemServer" in manager and "callbackProcessName" in manager
assert "synchronized(old)" in manager
assert "withConsumedDexBuffers(dexes)" in loader
assert "dex.mapReadOnly()" in scope and "SharedMemory.unmap(buffer)" in scope
assert "closeDexHandles(dexes)" in scope
assert "closeDexHandles(module?.code?.preLoadedDexes)" in channel
assert "queue = null" in executor and "running = false" in executor
assert "Executors.newSingleThreadExecutor" not in channel
snapshot = native.index("AEVIX_DEF_NATIVE_METHOD(jobjectArray, HookBridge, callbackSnapshot")
lock = native.index("lsplant::JNIMonitor monitor(env, backup);", snapshot)
fast_path = native.index("hook_item->modern_callbacks.empty()", lock)
allocation = native.index("env->NewObjectArray", lock)
assert lock < fast_path < allocation
