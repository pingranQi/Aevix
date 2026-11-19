package org.aevix.impl.hooks

import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap
import org.aevix.nativebridge.HookBridge

/**
 * What the process remembers about the hooks a module has installed.
 *
 * Keyed by module package name rather than by generation, because both things kept here have to
 * outlive one: an id exists so that new code can name a hook old code installed, and the handle list
 * handed to `onHotReloaded` is by definition the previous generation's.
 */
internal object AevixHookRegistry {

    private data class IdKey(val moduleId: String, val executable: Executable, val id: String)

    private val ids = ConcurrentHashMap<IdKey, AevixHookHandle>()
    private val byModule = ConcurrentHashMap<String, MutableSet<AevixHookHandle>>()
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Serialises everything that decides which handle owns a registration, and is also what a hot
     * reload takes to freeze old code without racing a registration already under way.
     */
    fun lockOf(moduleId: String): Any = locks.computeIfAbsent(moduleId) { Any() }

    fun findId(moduleId: String, origin: Executable, id: String): AevixHookHandle? =
        ids[IdKey(moduleId, origin, id)]

    fun claimId(moduleId: String, origin: Executable, id: String, handle: AevixHookHandle) {
        ids[IdKey(moduleId, origin, id)] = handle
    }

    fun releaseId(moduleId: String, origin: Executable, id: String, handle: AevixHookHandle) {
        ids.remove(IdKey(moduleId, origin, id), handle)
    }

    fun track(moduleId: String, handle: AevixHookHandle) {
        byModule.computeIfAbsent(moduleId) { ConcurrentHashMap.newKeySet() }.add(handle)
    }

    fun forget(moduleId: String, handle: AevixHookHandle) {
        byModule[moduleId]?.remove(handle)
    }

    /**
     * The hooks of [moduleId] that are still installed, for `HotReloadedParam#getOldHookHandles`.
     */
    fun liveHandles(moduleId: String): List<HookHandle> =
        byModule[moduleId]?.filter { it.isLive } ?: emptyList()
}

/**
 * The handle a module holds onto a hook it installed.
 *
 * A handle owns exactly one native record at a time, and a replacement mints a new handle and kills
 * this one - the interface is explicit that "after a successful replacement, this handle is no
 * longer valid", so a superseded handle must not be able to act on the record that replaced it,
 * `unhook()` included.
 *
 * [moduleId] is null for the framework's own hooks, which have no module to scope an id to and
 * nothing that could replace them.
 */
class AevixHookHandle
internal constructor(
    private val origin: Executable,
    private val moduleId: String?,
    initialRecord: AevixHookRecord,
) : HookHandle {

    private val id = initialRecord.id
    @Volatile internal var record: AevixHookRecord? = initialRecord
        private set

    internal val isLive: Boolean
        get() = record != null

    override fun getExecutable(): Executable = origin

    override fun getId(): String? = id

    override fun unhook() {
        if (moduleId == null) {
            synchronized(this) {
                val current = record ?: return
                HookBridge.unhookMethod(true, origin, current)
                record = null
            }
            return
        }
        synchronized(AevixHookRegistry.lockOf(moduleId)) {
            // Idempotent, as the interface requires - and a handle that has already been superseded
            // has to stay quiet here rather than tear down its own replacement.
            val current = record ?: return
            HookBridge.unhookMethod(true, origin, current)
            record = null
            id?.let { AevixHookRegistry.releaseId(moduleId, origin, it, this) }
            AevixHookRegistry.forget(moduleId, this)
        }
    }

    override fun replaceHook(hooker: Hooker): HookHandle {
        @Suppress("SENSELESS_COMPARISON")
        if (hooker == null) throw IllegalArgumentException("hooker is null")

        val moduleId =
            this.moduleId ?: throw IllegalStateException("This hook does not belong to a module")

        synchronized(AevixHookRegistry.lockOf(moduleId)) {
            val current =
                record ?: throw IllegalStateException("This hook handle is no longer valid")
            // Everything but the hooker is inherited, which is what distinguishes this from
            // registering a new hook that happens to carry the same id.
            return swapLocked(
                AevixHookRecord(hooker, current.priority, current.exceptionMode, current.id)
            )
        }
    }

    /**
     * Puts [replacement] where this handle's record is and hands the registration to a fresh handle.
     * Callers hold this module's lock, and [replacement] must carry this record's id.
     */
    internal fun swapLocked(replacement: AevixHookRecord): AevixHookHandle {
        val moduleId = checkNotNull(moduleId)
        val current = record ?: throw IllegalStateException("This hook handle is no longer valid")
        val handle = AevixHookHandle(origin, moduleId, replacement)
        if (!HookBridge.replaceCallback(true, origin, current, replacement, replacement.priority)) {
            // The record was not where we left it, and nothing was changed, so whatever hook is
            // installed now stays installed.
            throw HookFailedError("Cannot replace the hook on $origin")
        }

        record = null
        AevixHookRegistry.forget(moduleId, this)

        AevixHookRegistry.track(moduleId, handle)
        replacement.id?.let { AevixHookRegistry.claimId(moduleId, origin, it, handle) }
        return handle
    }
}
