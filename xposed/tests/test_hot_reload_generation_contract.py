#!/usr/bin/env python3
"""Guard the hot-reload two-phase generation handoff."""

from pathlib import Path

source = Path("xposed/src/main/kotlin/org/aevix/impl/core/AevixModuleManager.kt").read_text()
start = source.index("private fun runHotReload(")
end = source.index("\n    /**", start)
reload_body = source[start:end]

# A missing module or failed candidate build must leave the currently published generation intact.
assert "if (newModule == null)" in reload_body
assert "val old =" in reload_body
assert "generations[packageName]" in reload_body
assert "stageGeneration(newModule, old.isSystemServer, old.processName)" in reload_body
assert "?: return unsupported(\"Cannot stage a new generation" in reload_body

# The new generation is published only after the old generation accepted the handoff and the
# replacement entries were made active. This ordering is the commit point.
publish = reload_body.index("generations[packageName] = newGeneration")
accepted = reload_body.index("if (!accepted)")
old_removed = reload_body.index("oldEntries.forEach { AevixLifecycleManager.activeModules.remove(it) }")
new_active = reload_body.index("newEntries.forEach { AevixLifecycleManager.activeModules.add(it) }")
assert accepted < publish
assert old_removed < publish
assert new_active < publish
