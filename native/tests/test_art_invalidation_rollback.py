#!/usr/bin/env python3
"""Guard transactional rollback for ART executable-segment replacement."""

from pathlib import Path

source = Path("native/src/core/art_inline_hook_cleanup.cpp").read_text()
start = source.index("bool RestoreExecutableSegments(")
end = source.index("\nint RestoreLibArtCallback", start)
body = source[start:end]

assert "std::vector<ExecutableSegmentRollback> applied_segments;" in body
assert "rollback.bytes.assign(original_bytes, original_bytes + mapping_size);" in body
assert "applied_segments.emplace_back(std::move(rollback));" in body
assert "RollbackExecutableSegment(*it, fd)" in body
assert "for (auto it = applied_segments.rbegin();" in body
assert "MAP_PRIVATE | MAP_FIXED, fd" in source
assert "all previously replaced executable segments were rolled back" in body
assert "result.rollback_success = rollback_success;" in body
assert "if (result.rollback_success)" in source
assert "diagnostic state retained" in source
assert "success = false;\n            continue;" not in body
