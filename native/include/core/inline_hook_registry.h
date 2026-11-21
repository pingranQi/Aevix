#pragma once

#include <cstdint>
#include <map>
#include <mutex>
#include <utility>
#include <vector>

namespace vector::native {

enum class InlineHookBackendKind { kDobby, kShadowHook };
enum class InlineHookUnhookResult { kNotFound, kFailed, kRemoved };

/**
 * Tracks each successful installation independently. A target may have several ShadowHook
 * MULTI entries; the most recently installed entry is removed first. Failed removals remain
 * recorded so callers can retry and never dispatch an unhook to the wrong backend.
 */
template <typename Stub>
class InlineHookRegistry {
public:
    struct Record {
        InlineHookBackendKind backend;
        Stub stub;
    };

    template <typename Install>
    int InstallHook(uintptr_t target, InlineHookBackendKind backend, Install &&install) {
        // Keep installation and registration atomic with respect to UnhookLast. Otherwise an
        // unhook racing between the native hook call and Add() could dispatch to the wrong backend.
        std::lock_guard<std::mutex> lock(mutex_);
        auto &entries = records_[target];
        // Allocate registry storage before patching executable code. Once the hook succeeds,
        // recording its handle must not fail because the vector needs to grow.
        entries.reserve(entries.size() + 1);
        auto result = install();
        if (result.first == 0) {
            entries.push_back(Record{backend, std::move(result.second)});
        } else if (entries.empty()) {
            records_.erase(target);
        }
        return result.first;
    }

    template <typename Unhook>
    InlineHookUnhookResult UnhookLast(uintptr_t target, Unhook &&unhook) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = records_.find(target);
        if (it == records_.end() || it->second.empty()) return InlineHookUnhookResult::kNotFound;
        if (!unhook(it->second.back())) return InlineHookUnhookResult::kFailed;
        it->second.pop_back();
        if (it->second.empty()) records_.erase(it);
        return InlineHookUnhookResult::kRemoved;
    }

private:
    std::mutex mutex_;
    std::map<uintptr_t, std::vector<Record>> records_;
};

}  // namespace vector::native
