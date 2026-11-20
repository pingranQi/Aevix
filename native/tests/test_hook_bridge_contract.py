#!/usr/bin/env python3
"""Guard the ART HookBridge JNI contract for arrays, exceptions and return values."""

from pathlib import Path

source = Path("native/src/jni/hook_bridge.cpp").read_text()

# Reference arguments (including arrays) must be checked against the declared parameter Class,
# not accepted merely because their shorty is 'L'.
assert "if (declared == 'L')" in source
assert "env->IsInstanceOf(element, param)" in source
assert "argument type mismatch" in source

# A target exception is wrapped as InvocationTargetException; validation failures above remain
# direct IllegalArgumentException/NullPointerException failures.
assert 'java/lang/reflect/InvocationTargetException' in source
assert "env->ExceptionOccurred()" in source
assert "env->ExceptionClear()" in source
assert "env->NewObject(cls_ITE, ctor_ite, thrown)" in source

# Object/array return values must be passed through unchanged; primitive returns are boxed using
# the wrapper corresponding to the method's return shorty.
assert "if (returns == 'L')" in source
assert "value = ret_val.l;" in source
assert "WrapperFor(env, returns)" in source
assert "env->CallStaticObjectMethodA(wrapper->clazz, wrapper->box, &ret_val)" in source

# JNI exceptions raised while reading or converting an argument abort before invoking the target.
assert "if (env->ExceptionCheck())" in source
assert "StoreWidened(env, *wrapper, declared, element, a[i]);" in source


# callbackSnapshot reserves nullptr exclusively for the no-hook/no-backup cases.
# Allocation and argument failures must propagate instead of masquerading as an empty snapshot.
snapshot_start = source.index("AEVIX_DEF_NATIVE_METHOD(jobjectArray, HookBridge, callbackSnapshot")
snapshot_end = source.index("\\n}\\n", snapshot_start) + 2
snapshot = source[snapshot_start:snapshot_end]
assert "env->ExceptionClear()" not in snapshot
assert 'throw_if_no_exception("java/lang/OutOfMemoryError"' in snapshot
assert 'throw_if_no_exception("java/lang/NullPointerException"' in snapshot
assert "env->ThrowNew(exception_class, message);" in snapshot
assert "if (env->ExceptionCheck()) return fail_snapshot();" in snapshot
