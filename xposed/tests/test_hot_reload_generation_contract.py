#!/usr/bin/env python3
"""Guard the staged hot-reload handoff and its pre-commit failure path."""
from pathlib import Path

source = Path("xposed/src/main/kotlin/org/aevix/impl/core/AevixModuleManager.kt").read_text()
start = source.index("private fun runHotReload(")
end = source.index("\n    /**", start)
reload_body = source[start:end]
stage_start = source.index("private fun stageGeneration(")
stage_end = source.index("\n    fun hotReload(", stage_start)
stage_body = source[stage_start:stage_end]

assert "if (newModule == null)" in reload_body
assert "val old =" in reload_body
assert "stageGeneration(newModule, old.isSystemServer, old.processName)" in reload_body
assert "constructor.newInstance()" not in stage_body
assert "getDeclaredConstructor()" in stage_body
assert "val built = staged.instantiate()" in reload_body

stage = reload_body.index("stageGeneration(newModule")
freeze = reload_body.index("synchronized(AevixHookBuilder.lockOf(packageName))")
accepted = reload_body.index("if (!accepted)")
snapshot = reload_body.index("val oldHandles =")
instantiate = reload_body.index("val built = staged.instantiate()")
failed_build = reload_body.index("if (built == null)")
rollback = reload_body.index("old.context.unfreeze()", failed_build)
old_removed = reload_body.index("oldEntries.forEach { AevixLifecycleManager.activeModules.remove(it) }")
new_active = reload_body.index("newEntries.forEach { AevixLifecycleManager.activeModules.add(it) }")
publish = reload_body.index("generations[packageName] = newGeneration")

assert stage < freeze < accepted < snapshot < instantiate < failed_build < rollback < old_removed
assert old_removed < publish and new_active < publish
assert accepted < publish
