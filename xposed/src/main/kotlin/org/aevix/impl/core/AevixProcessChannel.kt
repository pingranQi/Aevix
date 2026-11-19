package org.aevix.impl.core

import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.libxposed.service.IXposedService
import java.util.concurrent.ThreadFactory
import org.aevix.ipc.HotReloadOutcome
import org.aevix.ipc.LoadedModule
import org.aevix.ipc.IHotReloadOutcomeReceiver
import org.aevix.ipc.IProcessChannel
import org.aevix.impl.utils.closeDexHandles
import org.aevix.util.Log

private const val TAG = "AevixProcessChannel"

/** The daemon's process channel. Its worker exists only while there are reloads to perform. */
object AevixProcessChannel : IProcessChannel.Stub() {
    private val worker =
        OnDemandSerialExecutor(
            ThreadFactory { Thread(it, "aevix-hot-reload") },
            { Log.e(TAG, "Unexpected hot reload dispatch failure", it) },
        )

    override fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        module: LoadedModule?,
        receiver: IHotReloadOutcomeReceiver?,
    ) {
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != 0) {
            closeDexHandles(module?.code?.preLoadedDexes)?.let {
                Log.w(TAG, "Cannot close rejected DEX handles", it)
            }
            Log.w(TAG, "Refusing a hot reload request from uid $caller")
            return
        }

        try {
            worker.execute { performReload(modulePackageName, extras, module, receiver) }
        } catch (failure: Throwable) {
            finish(module, receiver, failed(failure, generationChanged = false))
        }
    }

    private fun performReload(
        packageName: String?,
        extras: Bundle?,
        module: LoadedModule?,
        receiver: IHotReloadOutcomeReceiver?,
    ) {
        var before: ClassLoader? = null
        var entered = false
        val outcome =
            try {
                before = packageName?.let { AevixModuleManager.getModuleClassLoader(it) }
                entered = true
                AevixModuleManager.hotReload(packageName, extras, module)
            } catch (failure: Throwable) {
                val after = packageName?.let { AevixModuleManager.getModuleClassLoader(it) }
                failed(failure, generationChanged = entered && before !== after)
            }
        finish(module, receiver, outcome)
    }

    private fun finish(
        module: LoadedModule?,
        receiver: IHotReloadOutcomeReceiver?,
        outcome: HotReloadOutcome,
    ) {
        closeDexHandles(module?.code?.preLoadedDexes)?.let { failure ->
            outcome.status = IXposedService.HOT_RELOAD_FAILED
            outcome.refused = false
            outcome.message = "DEX handle cleanup failed: ${describe(failure)}"
            Log.w(TAG, "Cannot close hot reload DEX handles", failure)
        }
        try {
            receiver?.onOutcome(outcome)
        } catch (failure: Throwable) {
            Log.w(TAG, "Cannot report the hot reload outcome", failure)
        }
    }

    private fun failed(failure: Throwable, generationChanged: Boolean) =
        HotReloadOutcome().apply {
            status = IXposedService.HOT_RELOAD_FAILED
            message = describe(failure)
            refused = false
            this.generationChanged = generationChanged
        }

    private fun describe(failure: Throwable) =
        "${failure.javaClass.name}: ${failure.message ?: "no message"}"
}
