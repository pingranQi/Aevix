#!/usr/bin/env python3
"""Static guards for the production ShadowHook/Dobby dispatch contract."""

from pathlib import Path

source = Path("native/include/core/native_api.h").read_text()

assert "SHADOWHOOK_MODE_MULTI" in source
assert "SHADOWHOOK_MODE_UNIQUE" not in source
assert "falling back to Dobby" not in source
assert "InlineHookBackendKind::kShadowHook" in source
assert "InlineHookBackendKind::kDobby" in source
assert "InlineHookUnhookResult::kFailed" in source
assert "InlineHookRegistry<void *>::Record" in source
assert "g_inline_hook_registry.InstallHook" in source
assert "No recorded inline hook backend" in source
