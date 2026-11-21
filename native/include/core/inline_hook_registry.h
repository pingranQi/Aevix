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

    void Add(uintptr_t target, InlineHookBackendKind backend, Stub stub) {
        std::lock_guard<std::mutex> lock(mutex_);
        records_[target].push_back(Record{backend, std::move(stub)});
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
