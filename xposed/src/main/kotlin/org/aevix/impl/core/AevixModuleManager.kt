package org.aevix.impl.core

import android.os.Build
import android.os.Bundle
import android.os.Process
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.service.IXposedService
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap
import org.aevix.ipc.HotReloadOutcome
import org.aevix.ipc.LoadedModule
import org.aevix.util.Log
import org.aevix.impl.AevixContext
import org.aevix.impl.AevixLifecycleManager
import org.aevix.impl.hooks.AevixHookBuilder
import org.aevix.impl.utils.AevixModuleClassLoader
import org.aevix.nativebridge.NativeAPI

/**
 * Responsible for loading modules into the target process. Handles ClassLoader isolation and
 * injects the framework context into the module instances.
 */
object AevixModuleManager {

    private const val TAG = "AevixModuleManager"

    // Entries are weak on purpose: activeModules owns the only strong reference, and detach()
    // removes it. A reload holds a local strong list for the cycle instead.
    private class Generation(
        val classLoader: ClassLoader,
        val context: AevixContext,
        entries: List<XposedModule>,
        val isSystemServer: Boolean,
        val processName: String,
    ) {
        private val entryRefs = entries.map { WeakReference(it) }

        fun liveEntries(): List<XposedModule> =
            entryRefs.mapNotNull { it.get() }.filter { AevixLifecycleManager.isActive(it) }
    }

    private val generations = ConcurrentHashMap<String, Generation>()

    // Reloads are serialized per module within this process; different modules may reload in
    // parallel. The lease owns unlock so every exit path releases exactly once.
    private val reloadGate = KeyedReloadGate<String>()

    /** The class loader a loaded module runs in, or null if no generation is loaded for it. */
    fun getModuleClassLoader(packageName: String): ClassLoader? = generations[packageName]?.classLoader

    /** Packages of the modules currently loaded in this process. */
    fun loadedModulePackages(): Set<String> = generations.keys.toSet()

    /**
     * Loads a module APK, instantiates its entry classes, and binds them to the Aevix framework.
     */
    fun loadModule(module: LoadedModule, isSystemServer: Boolean, processName: String): Boolean {
        val (generation, entries) =
            buildGeneration(module, isSystemServer, processName) ?: return false

        entries.forEach { AevixLifecycleManager.activeModules.add(it) }
        generations[module.packageName] = generation

        val param =
            object : ModuleLoadedParam {
                override fun isSystemServer(): Boolean = isSystemServer

                override fun getProcessName(): String = processName
            }
        entries.forEach { entry ->
            runCatching { entry.onModuleLoaded(param) }
                .onFailure { e ->
                    Log.e(TAG, "Error in onModuleLoaded for ${entry.javaClass.name}", e)
                }
        }

        // Native entry points are recorded by buildGeneration, which has to do it before the entry
        // classes run. Recording them again here would put every library name in the list the dlopen
        // hook walks twice over, and that list never shrinks.

        Log.d(TAG, "Loaded package ${module.packageName} successfully.")
        return true
    }

    private fun buildGeneration(module: LoadedModule, isSystemServer: Boolean, processName: String): Pair<Generation, List<XposedModule>>? =
        stageGeneration(module, isSystemServer, processName)?.instantiate()

    private class StagedGeneration(private val module: LoadedModule, private val isSystemServer: Boolean, private val processName: String, private val classLoader: ClassLoader, private val context: AevixContext, private val constructors: List<Constructor<*>>) {
        fun instantiate(): Pair<Generation, List<XposedModule>>? {
            module.code.moduleLibraryNames.forEach { NativeAPI.recordNativeEntrypoint(it) }
            val entries = mutableListOf<XposedModule>()
            for (constructor in constructors) {
                try {
                    constructor.isAccessible = true
                    val instance = constructor.newInstance() as XposedModule
                    instance.attachFramework(context) { AevixLifecycleManager.detach(instance) }
                    entries.add(instance)
                } catch (t: Throwable) { Log.e(TAG, "Failed to instantiate class ${constructor.declaringClass.name}", t) }
            }
            if (entries.isEmpty()) { Log.e(TAG, "No entry class could be instantiated for " + module.packageName); return null }
            return Generation(classLoader, context, entries, isSystemServer, processName) to entries
        }
    }

    private fun stageGeneration(module: LoadedModule, isSystemServer: Boolean, processName: String): StagedGeneration? {
        try {
            Log.d(TAG, "Staging package " + module.packageName)
            val librarySearchPath = buildString {
                if (isSystemServer) module.code.nativeLibraryDir?.let { append(it).append(File.pathSeparator) }
                val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
                for (abi in abis) append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator)
            }
            val initLoader = XposedModule::class.java.classLoader
            val loader = AevixModuleClassLoader.loadApk(module.apkPath, module.code.preLoadedDexes, librarySearchPath, initLoader, blockLegacyApi = module.code.targetApiVersion >= 102)
            if (loader.loadClass(XposedModule::class.java.name).classLoader !== initLoader) { Log.e(TAG, "The Xposed API classes are compiled into " + module.packageName); return null }
            val context = AevixContext(packageName = module.packageName, applicationInfo = module.applicationInfo, service = module.service, defaultExceptionMode = if (module.code.exceptionPassthrough) ExceptionMode.PASSTHROUGH else ExceptionMode.PROTECTIVE)
            val constructors = mutableListOf<Constructor<*>>()
            for (className in module.code.moduleClassNames) {
                try {
                    val clazz = loader.loadClass(className)
                    Log.v(TAG, "Resolving entry $clazz")
                    if (!XposedModule::class.java.isAssignableFrom(clazz)) { Log.e(TAG, "Class does not extend XposedModule: $className"); continue }
                    constructors += clazz.getDeclaredConstructor()
                } catch (t: Throwable) { Log.e(TAG, "Failed to stage entry class $className", t) }
            }
            if (constructors.isEmpty()) { Log.e(TAG, "No entry class could be staged for " + module.packageName); return null }
            return StagedGeneration(module, isSystemServer, processName, loader, context, constructors)
        } catch (t: Throwable) { Log.e(TAG, "Fatal error staging module " + module.packageName, t); return null }
    }

    fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        newModule: LoadedModule?,
    ): HotReloadOutcome {
        val packageName =
            modulePackageName ?: return unsupported("Hot reload was requested without a module")
        val lease = reloadGate.tryAcquire(packageName)
            ?: return outcome(
                IXposedService.HOT_RELOAD_IN_PROGRESS,
                "A reload of $packageName is already running in this process",
            )
        return try {
            runHotReload(packageName, extras, newModule)
        } catch (t: Throwable) {
            Log.e(TAG, "Hot reload of $packageName failed", t)
            failed(describe(t))
        } finally {
            lease.close()
        }
    }

    private fun runHotReload(
        packageName: String,
        extras: Bundle?,
        newModule: LoadedModule?,
    ): HotReloadOutcome {
        if (newModule == null) {
            return unsupported("No new generation of $packageName was supplied")
        }
        val old =
            generations[packageName]
                ?: return unsupported(
                    "$packageName is not loaded in ${AevixServiceClient.processName}"
                )
        if (newModule.code.moduleClassNames.size != 1) {
            return unsupported("$packageName does not declare exactly one Java entry class")
        }

        // Keeps the old generation reachable until onHotReloaded has finished.
        val oldEntries = old.liveEntries()
        if (oldEntries.isEmpty()) {
            // Not a refusal: a null message means onHotReloading returned false, and nothing ran.
            Log.w(TAG, "No attached entry of $packageName can accept a hot reload")
            return unsupported("Every entry of $packageName has detached in this process")
        }

        val staged =
            stageGeneration(newModule, old.isSystemServer, old.processName)
                ?: return unsupported("Cannot stage a new generation of $packageName")

        // Before the callback, so registrations from inside it fail while unhook and replace work.
        // Under the hook registry's lock for this module, because a registration on another thread
        // that has already passed its own check must either finish before the freeze - and so be in
        // the list the successor is handed - or see the freeze and fail. Held for the flag write
        // only: module code never runs inside it.
        synchronized(AevixHookBuilder.lockOf(packageName)) { old.context.freeze() }

        val retiredLoader = WeakReference(old.classLoader)
        val callbackIsSystemServer = old.isSystemServer
        val callbackProcessName = old.processName
        var savedState: Any? = null
        val reloadingParam =
            object : HotReloadingParam {
                override fun getExtras(): Bundle? = extras

                override fun setSavedInstanceState(outState: Any?) {
                    retiredLoader.get()?.let { rejectOldGenerationState(outState, it) }
                    savedState = outState
                }
            }

        val accepted =
            try {
                // One refusal cancels the reload for the whole module.
                oldEntries.all { it.onHotReloading(reloadingParam) }
            } catch (t: Throwable) {
                old.context.unfreeze()
                Log.e(TAG, "onHotReloading of $packageName threw", t)
                return failed(describe(t))
            }
        if (!accepted) {
            old.context.unfreeze()
            Log.d(TAG, "$packageName refused the hot reload")
            return refusal()
        }

        // Captured after the freeze and after old code had its chance to unhook.
        val oldHandles = AevixHookBuilder.snapshotHandles(packageName)

        // Entry constructors execute module code, so defer them until retirement is accepted.
        val built = staged.instantiate()
        if (built == null) {
            old.context.unfreeze()
            return failed("Cannot instantiate the new generation of $packageName")
        }
        val (newGeneration, newEntries) = built

        oldEntries.forEach { AevixLifecycleManager.activeModules.remove(it) }
        // Active before the callback, so an entry detaching from inside it is honoured.
        newEntries.forEach { AevixLifecycleManager.activeModules.add(it) }

        val reloadedParam =
            object : HotReloadedParam {
                override fun isSystemServer(): Boolean = callbackIsSystemServer

                override fun getProcessName(): String = callbackProcessName

                override fun getExtras(): Bundle? = extras

                override fun getSavedInstanceState(): Any? = savedState

                override fun getOldHookHandles(): List<XposedInterface.HookHandle> = oldHandles
            }

        // Committed before the callback runs, because the interface releases the old generation
        // "after this callback returns or throws" - there is no rollback. A throw here leaves the
        // process running new code that has migrated some of its hooks and not others, and says so
        // through the result; rolling back could not undo the replaceHook calls the new code had
        // already made anyway, and would restore old entries whose hooks now run new hookers.
        generations[packageName] = newGeneration

        var failure: Throwable? = null
        // The default onHotReloaded already unhooks these; doing both would double-unhook.
        // Retain the retired generation only for the duration of callbacks. Parameters above
        // capture scalar values/weak references, so a module retaining them cannot pin its loader.
        synchronized(old) {
            newEntries
                .filter { AevixLifecycleManager.isActive(it) }
                .forEach {
                    if (failure != null) return@forEach
                    runCatching { it.onHotReloaded(reloadedParam) }.onFailure { t -> failure = t }
                }
        }

        // The last framework-owned reference to the old generation goes with this frame: its map
        // entry is gone, its entries are out of activeModules, and oldEntries dies on return.
        failure?.let {
            Log.e(TAG, "onHotReloaded of $packageName threw", it)
            return failed(describe(it), generationChanged = true)
        }

        Log.d(TAG, "Hot reloaded $packageName")
        return outcome(IXposedService.HOT_RELOAD_SUCCEEDED, null, generationChanged = true)
    }

    /**
     * Rejects saved state that the old generation created, which would otherwise keep the retired
     * classloader reachable through the new one. A shallow scan, as the API describes it: a
     * diagnostic aid rather than an object graph verifier.
     */
    private fun rejectOldGenerationState(state: Any?, oldClassLoader: ClassLoader) {
        if (state == null) return
        reject(state, oldClassLoader)
        when (state) {
            is Array<*> -> state.forEach { it?.let { e -> reject(e, oldClassLoader) } }
            is Collection<*> -> state.forEach { it?.let { e -> reject(e, oldClassLoader) } }
            is Map<*, *> ->
                state.forEach { (k, v) ->
                    k?.let { reject(it, oldClassLoader) }
                    v?.let { reject(it, oldClassLoader) }
                }
        }
    }

    private fun reject(value: Any, oldClassLoader: ClassLoader) {
        if (definedBy(value.javaClass, oldClassLoader)) {
            throw IllegalArgumentException(
                "Saved instance state contains ${value.javaClass.name}, which was created under " +
                    "the old module classloader"
            )
        }
    }

    private fun definedBy(clazz: Class<*>, classLoader: ClassLoader): Boolean {
        var loader: ClassLoader? =
            (if (clazz.isArray) clazz.componentType else clazz)?.classLoader
        while (loader != null) {
            if (loader === classLoader) return true
            loader = loader.parent
        }
        return false
    }

    private fun outcome(
        status: Int,
        message: String?,
        refused: Boolean = false,
        generationChanged: Boolean = false,
    ) =
        HotReloadOutcome().apply {
            this.status = status
            this.message = message
            this.refused = refused
            this.generationChanged = generationChanged
        }

    private fun unsupported(message: String) =
        outcome(IXposedService.HOT_RELOAD_UNSUPPORTED, message)

    private fun failed(message: String, generationChanged: Boolean = false) =
        outcome(IXposedService.HOT_RELOAD_FAILED, message, generationChanged = generationChanged)

    private fun refusal() = outcome(IXposedService.HOT_RELOAD_FAILED, null, refused = true)

    private fun describe(t: Throwable) = "${t.javaClass.name}: ${t.message ?: "no message"}"
}
