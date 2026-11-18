#!/usr/bin/env python3
"""Source-level ABI fallback regression for the Android 8.1–17 OAT hook paths."""

from pathlib import Path

source = Path("dex2oat/src/main/cpp/oat_hook.cpp").read_text()

# Pre-Android 16 uses the KeyValueStore getter; Android 16+ moved the interception point
# to checksum calculation. Keep both exact mangled symbols available.
assert "_ZNK3art9OatHeader16GetKeyValueStoreEv" in source
assert "_ZNK3art9OatHeader15ComputeChecksumEPj" in source

# A missing/renamed import must not call through a null GOT slot. Resolve the definition first,
# and use the hook library's published prior replacement when another module hooked the slot.
assert "dlsym(RTLD_DEFAULT, symbol)" in source
assert "if (fallback == nullptr) return false;" in source
assert "*original = published != nullptr ? published : fallback;" in source

# If the getter symbol is unavailable, try the checksum path rather than silently doing nothing.
assert "if (!INSTALL_HOOK(_ZNK3art9OatHeader16GetKeyValueStoreEv))" in source
assert "INSTALL_HOOK(_ZNK3art9OatHeader15ComputeChecksumEPj);" in source
