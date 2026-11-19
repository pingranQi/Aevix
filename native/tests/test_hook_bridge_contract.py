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

# Retired hooks with no callbacks should not allocate empty JNI snapshot arrays.
# The fast path belongs to callbackSnapshot (jobjectArray), never hookMethod (jboolean).
hook_method = source.split("AEVIX_DEF_NATIVE_METHOD(jboolean, HookBridge, hookMethod", 1)[1].split(
    "AEVIX_DEF_NATIVE_METHOD(jboolean, HookBridge, unhookMethod", 1
)[0]
callback_snapshot = source.split(
    "AEVIX_DEF_NATIVE_METHOD(jobjectArray, HookBridge, callbackSnapshot", 1
)[1].split(
    "AEVIX_DEF_NATIVE_METHOD", 1
)[0]
assert "modern_callbacks.empty() && hook_item->legacy_callbacks.empty()" not in hook_method
assert "hook_item->modern_callbacks.empty() && hook_item->legacy_callbacks.empty()) return nullptr;" in callback_snapshot

# JNI exceptions raised while reading or converting an argument abort before invoking the target.
assert "if (env->ExceptionCheck())" in source
assert "StoreWidened(env, *wrapper, declared, element, a[i]);" in source
