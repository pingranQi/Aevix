#pragma once

#include <dlfcn.h>
#if defined(__aarch64__) || defined(__arm__)
#include <shadowhook.h>
#endif
#include <dobby.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <utils/hook_helper.hpp>

#include "core/inline_hook_registry.h"
#include "common/config.h"
#include "common/logging.h"

/**
 * @file native_api.h
 * @brief Manages the native module ecosystem and provides a stable API for them.
 *
 * This component is responsible for hooking the dynamic library loader (`dlopen`) to
 * detect when registered native modules are loaded.
 * It then provides these modules with a set of function pointers for
 * interacting with the Aevix core, primarily for creating native hooks.
 */

// NOTE: The following type definitions form a public ABI for native modules.
// Do not change them without careful consideration for backward compatibility.

/*
 * =========================================================================================
 *  Aevix Native API Interface
 * =========================================================================================
 *
 * This following function types and data structures allow a native library (your module) to
 * interface with the Aevix framework.
 * The core idea is that Aevix provides a set of powerful tools (like function hooking),
 * and your module consumes these tools through a well-defined entry point.
 *
 * The interaction flow is as follows:
 *
 *   1. Aevix intercepts the loading of your native library (e.g., libnative.so).
 *   2. Aevix looks for and calls the `native_init` function within your library.
 *   3. Aevix passes a `NativeAPIEntries` struct to your `native_init`,
 *      which contains function pointers to Aevix's configured inline-hook backend
 *      and matching unhook implementation (ShadowHook or Dobby).
 *   4. Your `native_init` function saves these function pointers for later use
 *      and returns a callback function (`NativeOnModuleLoaded`).
 *   5. Aevix will then invoke your returned callback every time
 *      a new native library is loaded into the target process,
 *      allowing you to perform "late" hooks on specific libraries.
 *
 *
 * Initialization Flow
 *
 *   Aevix Framework                    Your Native Module (e.g., libnative.so)
 *   -----------------                    -------------------------------------
 *
 *        |                                            |
 * [ Intercepts dlopen("libnative.so") ]               |
 *        |                                            |
 *        |----------> [ Finds & Calls native_init() ] |
 *        |                                            |
 *   [ Passes NativeAPIEntries* ]  ---> [ Stores function pointers ]
 *   (Contains hook/unhook funcs)                      |
 *        |                                            |
 *        |                                            |
 *        |             <-----------[ Returns `NativeOnModuleLoaded` callback ]
 *        |                                            |
 *        |                                            |
 *   [ Stores your callback ]                          |
 *        |                                            |
 *
 */

// Function pointer type for a native hooking implementation.
using HookFunType = int (*)(void *func, void *replace, void **backup);

// Function pointer type for a native unhooking implementation.
using UnhookFunType = int (*)(void *func);

// Callback function pointer that modules receive, invoked when any library is loaded.
using NativeOnModuleLoaded = void (*)(const char *name, void *handle);

/**
 * @struct NativeAPIEntries
 * @brief A struct containing function pointers exposed to native modules.
 */
struct NativeAPIEntries {
    uint32_t version;          // The version of this API struct.
    HookFunType hookFunc;      // Pointer to the function for inline  hooking.
    UnhookFunType unhookFunc;  // Pointer to the function for unhooking.
};

// NOTE: Module developers should not include the following INTERNAL definitions.

namespace vector::native {

inline constexpr int kInlineHookBackendDobby = 0;
inline constexpr int kInlineHookBackendShadowHook = 1;

inline std::atomic<int> g_inline_hook_backend{kInlineHookBackendDobby};
inline std::atomic<HookFunType> g_external_inline_hook{nullptr};
inline std::atomic<UnhookFunType> g_external_inline_unhook{nullptr};

// The runtime owns its inline-hook engine in HyperOS-spawned processes. Install both callbacks
// together so a hook can never be created by one backend and removed by another.
inline void SetExternalInlineHookBackend(HookFunType hook, UnhookFunType unhook) {
    if (hook == nullptr || unhook == nullptr) {
        g_external_inline_hook.store(nullptr, std::memory_order_release);
        g_external_inline_unhook.store(nullptr, std::memory_order_release);
        return;
    }
    g_external_inline_hook.store(hook, std::memory_order_release);
    g_external_inline_unhook.store(unhook, std::memory_order_release);
}

inline void SetInlineHookBackend(int backend) {
#if defined(__aarch64__) || defined(__arm__)
    backend = backend == kInlineHookBackendShadowHook
                  ? kInlineHookBackendShadowHook
                  : kInlineHookBackendDobby;
#else
    backend = kInlineHookBackendDobby;
#endif
    g_inline_hook_backend.store(backend, std::memory_order_relaxed);
    LOGD("Inline hook backend set to {}",
         backend == kInlineHookBackendShadowHook ? "ShadowHook" : "Dobby");
}

#if defined(__aarch64__) || defined(__arm__)
inline std::once_flag g_shadowhook_init_once;
inline int g_shadowhook_init_result = -1;
inline InlineHookRegistry<void *> g_inline_hook_registry;

inline bool ShadowHookInit() {
    std::call_once(g_shadowhook_init_once, [] {
        g_shadowhook_init_result = shadowhook_init(SHADOWHOOK_MODE_MULTI, false);
        if (g_shadowhook_init_result == 0) {
            LOGD("ShadowHook engine ready (multi mode).");
        } else {
            LOGE("ShadowHook init failed: %s",
                 shadowhook_to_errmsg(shadowhook_get_init_errno()));
        }
    });
    return g_shadowhook_init_result == 0;
}
#endif

inline void LogInlineHook(int backend, void *address, bool installing) {
    if constexpr (kIsDebugBuild) {
        Dl_info info;
        if (dladdr(address, &info)) {
            LOGD("{} {} {} ({}) from {} ({})",
                 backend == kInlineHookBackendShadowHook ? "ShadowHook" : "Dobby",
                 installing ? "hooking" : "unhooking",
                 info.dli_sname ? info.dli_sname : "(unknown symbol)",
                 info.dli_saddr ? info.dli_saddr : address,
                 info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
        }
    }
}

// The entry point function that native modules must export (`native_init`).
using NativeInit = NativeOnModuleLoaded (*)(const NativeAPIEntries *entries);

/** Installs the hooks required for the native API to function. */
bool InstallNativeAPI(const lsplant::HookHandler &handler);

/** Initializes the shared Native API table and installs the loader hook with the supplied backend. */
bool InitializeNativeAPI(const lsplant::HookHandler &handler);

/** Returns the immutable Native API table, or null before it has been initialized. */
const NativeAPIEntries *GetNativeAPIEntries();

/** Lock the native module registry around fork; pair with the parent/child callbacks below. */
void NativeAPIAtForkPrepare();
void NativeAPIAtForkParent();
void NativeAPIAtForkChild();

/** Registers a native library by its filename for module initialization. */
void RegisterNativeLib(const std::string &library_name);

/** A wrapper around the configured inline hook backend. */
inline int HookInline(void *original, void *replace, void **backup) {
    if (auto hook = g_external_inline_hook.load(std::memory_order_acquire)) {
        return hook(original, replace, backup);
    }
#if defined(__aarch64__) || defined(__arm__)
    if (g_inline_hook_backend.load(std::memory_order_relaxed) == kInlineHookBackendShadowHook) {
        if (ShadowHookInit()) {
            // MULTI mode requires a persistent orig_addr slot. Do not silently switch to Dobby
            // when ShadowHook rejects a hook: that can stack a second backend on one entry point.
            if (backup == nullptr) {
                LOGE("ShadowHook MULTI mode requires a non-null backup slot for %p", original);
                return -1;
            }
            LogInlineHook(kInlineHookBackendShadowHook, original, true);
            const int status = g_inline_hook_registry.InstallHook(
                reinterpret_cast<uintptr_t>(original), InlineHookBackendKind::kShadowHook, [&] {
                    void *stub = shadowhook_hook_func_addr(original, replace, backup);
                    return std::make_pair(stub == nullptr ? -1 : 0, stub);
                });
            if (status != 0) {
                LOGE("ShadowHook failed for %p: %s", original, shadowhook_to_errmsg(shadowhook_get_errno()));
            }
            return status;
        }
        // Initialization failure is the only ShadowHook path that may fall back to Dobby.
        LOGW("ShadowHook initialization unavailable; using Dobby for %p", original);
    }
#endif
    LogInlineHook(kInlineHookBackendDobby, original, true);
#if defined(__aarch64__) || defined(__arm__)
    return g_inline_hook_registry.InstallHook(
        reinterpret_cast<uintptr_t>(original), InlineHookBackendKind::kDobby, [&] {
            const int status = DobbyHook(original, reinterpret_cast<dobby_dummy_func_t>(replace),
                                         reinterpret_cast<dobby_dummy_func_t *>(backup));
            return std::make_pair(status, static_cast<void *>(nullptr));
        });
#else
    return DobbyHook(original, reinterpret_cast<dobby_dummy_func_t>(replace),
                     reinterpret_cast<dobby_dummy_func_t *>(backup));
#endif
}

/** A wrapper around the configured inline hook backend. */
inline int UnhookInline(void *original) {
    if (auto unhook = g_external_inline_unhook.load(std::memory_order_acquire)) {
        return unhook(original);
    }
#if defined(__aarch64__) || defined(__arm__)
    const uintptr_t target = reinterpret_cast<uintptr_t>(original);
    const auto result = g_inline_hook_registry.UnhookLast(
        target, [original](const InlineHookRegistry<void *>::Record &record) {
            if (record.backend == InlineHookBackendKind::kShadowHook) {
                if (shadowhook_unhook(record.stub) == 0) {
                    LogInlineHook(kInlineHookBackendShadowHook, original, false);
                    return true;
                }
                LOGE("ShadowHook unhook failed for %p: %s",
                     original, shadowhook_to_errmsg(shadowhook_get_errno()));
                return false;
            }
            LogInlineHook(kInlineHookBackendDobby, original, false);
            const int status = DobbyDestroy(original);
            if (status != 0) LOGE("Dobby unhook failed for %p: %d", original, status);
            return status == 0;
        });
    if (result == InlineHookUnhookResult::kRemoved) return 0;
    if (result == InlineHookUnhookResult::kFailed) return -1;
    LOGE("No recorded inline hook backend for %p; refusing an ambiguous Dobby unhook", original);
    return -1;
#else
    LogInlineHook(kInlineHookBackendDobby, original, false);
    return DobbyDestroy(original);
#endif
}
}  // namespace vector::native
