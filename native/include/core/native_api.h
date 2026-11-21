#pragma once

#include <dlfcn.h>
#if defined(__aarch64__) || defined(__arm__)
#include <shadowhook.h>
#endif
#include <dobby.h>

#include <atomic>
#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <utils/hook_helper.hpp>

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
 *      which contains function pointers to Aevix's hooking
 *      and unhooking implementations (powered by Dobby).
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
inline std::mutex g_shadowhook_mutex;

enum class InlineHookEngine : uint8_t {
    Dobby,
    ShadowHook,
};

struct InlineHookRecord {
    InlineHookEngine engine;
    void *shadowhook_stub = nullptr;
};

// Keep a LIFO record for every successful installation. The public Unhook API identifies a
// hook by target address only, so LIFO mirrors the order in which stacked hooks are removed.
inline std::map<uintptr_t, std::vector<InlineHookRecord>> g_inline_hook_records;

inline bool ShadowHookInit() {
    std::call_once(g_shadowhook_init_once, [] {
        g_shadowhook_init_result = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
        if (g_shadowhook_init_result == 0) {
            LOGD("ShadowHook engine ready (unique mode).");
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

/** Registers a native library by its filename for module initialization. */
void RegisterNativeLib(const std::string &library_name);

/** A wrapper around the configured inline hook backend. */
inline int HookInline(void *original, void *replace, void **backup) {
#if defined(__aarch64__) || defined(__arm__)
    const bool shadowhook_selected =
        g_inline_hook_backend.load(std::memory_order_relaxed) == kInlineHookBackendShadowHook;
    if (shadowhook_selected && ShadowHookInit()) {
        // Serialize hook/unhook operations and preserve the backend for this exact installation.
        // Unique mode deliberately rejects duplicate targets. Do not stack a Dobby hook on top of
        // an existing ShadowHook entry when that happens: the two engines cannot safely share the
        // same patched instructions or trampoline lifetime.
        std::lock_guard<std::mutex> lock(g_shadowhook_mutex);
        LogInlineHook(kInlineHookBackendShadowHook, original, true);
        void *stub = shadowhook_hook_func_addr(original, replace, backup);
        if (stub == nullptr) {
            LOGE("ShadowHook failed for %p: %s",
                 original, shadowhook_to_errmsg(shadowhook_get_errno()));
            return -1;
        }
        g_inline_hook_records[reinterpret_cast<uintptr_t>(original)].push_back(
            {InlineHookEngine::ShadowHook, stub});
        return 0;
    }
#endif

    // Dobby is the configured backend, or the fallback when ShadowHook itself could not
    // initialize. A runtime ShadowHook hook failure is not a backend initialization failure and
    // must not silently fall through to Dobby.
    LogInlineHook(kInlineHookBackendDobby, original, true);
    const int result = DobbyHook(original, reinterpret_cast<dobby_dummy_func_t>(replace),
                                 reinterpret_cast<dobby_dummy_func_t *>(backup));
#if defined(__aarch64__) || defined(__arm__)
    if (result == 0) {
        std::lock_guard<std::mutex> lock(g_shadowhook_mutex);
        g_inline_hook_records[reinterpret_cast<uintptr_t>(original)].push_back(
            {InlineHookEngine::Dobby, nullptr});
    }
#endif
    return result;
}

/** A wrapper around the configured inline hook backend. */
inline int UnhookInline(void *original) {
#if defined(__aarch64__) || defined(__arm__)
    {
        std::lock_guard<std::mutex> lock(g_shadowhook_mutex);
        auto it = g_inline_hook_records.find(reinterpret_cast<uintptr_t>(original));
        if (it != g_inline_hook_records.end() && !it->second.empty()) {
            const InlineHookRecord record = it->second.back();
            int result = -1;
            if (record.engine == InlineHookEngine::ShadowHook) {
                result = shadowhook_unhook(record.shadowhook_stub);
                if (result == 0) {
                    LogInlineHook(kInlineHookBackendShadowHook, original, false);
                } else {
                    LOGE("ShadowHook unhook failed for %p: %s; retaining stub for retry",
                         original, shadowhook_to_errmsg(shadowhook_get_errno()));
                }
            } else {
                LogInlineHook(kInlineHookBackendDobby, original, false);
                result = DobbyDestroy(original);
            }

            // Keep the exact backend/stub record when teardown fails. Removing it early would
            // make a retry dispatch to the wrong hook engine and lose ShadowHook's only handle.
            if (result == 0) {
                it->second.pop_back();
                if (it->second.empty()) g_inline_hook_records.erase(it);
            }
            return result;
        }
    }
#endif
    // Compatibility for hooks installed before backend bookkeeping was introduced.
    LogInlineHook(kInlineHookBackendDobby, original, false);
    return DobbyDestroy(original);
}

}  // namespace vector::native
