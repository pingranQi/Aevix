#include "core/inline_hook_registry.h"

#include <cassert>
#include <cstdint>
#include <vector>
#include <utility>

using namespace vector::native;

int main() {
    InlineHookRegistry<int> registry;
    constexpr uintptr_t target = 0x1000;
    assert(registry.InstallHook(target, InlineHookBackendKind::kShadowHook, [] {
               return std::make_pair(0, 11);
           }) == 0);
    assert(registry.InstallHook(target, InlineHookBackendKind::kShadowHook, [] {
               return std::make_pair(0, 22);
           }) == 0);
    std::vector<int> removed;
    auto remove = [&](const InlineHookRegistry<int>::Record &record) {
        removed.push_back(record.stub);
        return true;
    };
    assert(registry.UnhookLast(target, remove) == InlineHookUnhookResult::kRemoved);
    assert(registry.UnhookLast(target, remove) == InlineHookUnhookResult::kRemoved);
    assert((removed == std::vector<int>{22, 11}));
    assert(registry.UnhookLast(target, remove) == InlineHookUnhookResult::kNotFound);

    assert(registry.InstallHook(target, InlineHookBackendKind::kShadowHook, [] {
               return std::make_pair(0, 33);
           }) == 0);
    assert(registry.InstallHook(target, InlineHookBackendKind::kShadowHook, [] {
               return std::make_pair(-1, 99);
           }) == -1);
    assert(registry.InstallHook(0x3000, InlineHookBackendKind::kDobby, [] {
               return std::make_pair(-1, 0);
           }) == -1);
    assert(registry.UnhookLast(0x3000, remove) == InlineHookUnhookResult::kNotFound);
    assert(registry.UnhookLast(target, [](const auto &) { return false; }) ==
           InlineHookUnhookResult::kFailed);
    int retried_stub = 0;
    assert(registry.UnhookLast(target, [&](const auto &record) {
               retried_stub = record.stub;
               return true;
           }) == InlineHookUnhookResult::kRemoved);
    assert(retried_stub == 33);

    assert(registry.InstallHook(target, InlineHookBackendKind::kShadowHook, [] {
               return std::make_pair(0, 44);
           }) == 0);
    assert(registry.InstallHook(target, InlineHookBackendKind::kDobby, [] {
               return std::make_pair(0, 0);
           }) == 0);
    std::vector<InlineHookBackendKind> backends;
    auto remove_backend = [&](const auto &record) {
        backends.push_back(record.backend);
        return true;
    };
    assert(registry.UnhookLast(target, remove_backend) == InlineHookUnhookResult::kRemoved);
    assert(registry.UnhookLast(target, remove_backend) == InlineHookUnhookResult::kRemoved);
    assert(backends.size() == 2);
    assert(backends[0] == InlineHookBackendKind::kDobby);
    assert(backends[1] == InlineHookBackendKind::kShadowHook);

    assert(registry.InstallHook(0x2000, InlineHookBackendKind::kDobby, [] {
               return std::make_pair(0, 0);
           }) == 0);
    assert(registry.UnhookLast(target, remove_backend) == InlineHookUnhookResult::kNotFound);
    assert(registry.UnhookLast(0x2000, remove_backend) == InlineHookUnhookResult::kRemoved);
    return 0;
}
