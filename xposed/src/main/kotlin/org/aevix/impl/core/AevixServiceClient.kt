package org.aevix.impl.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.aevix.ipc.LoadedModule
import org.aevix.ipc.IProcessChannel
import org.aevix.ipc.IFrameworkService
import org.aevix.ipc.IAevixDaemon
import org.aevix.util.Log

/**
 * Singleton client for managing IPC communication with the injected manager service. Handles Binder
 * death gracefully and ensures safe remote execution.
 */
object AevixServiceClient : IFrameworkService {

    private const val TAG = "AevixServiceClient"

    private class Connection(val service: IFrameworkService) : IBinder.DeathRecipient {
        val binder: IBinder = checkNotNull(service.asBinder())
        var restored = false

        override fun binderDied() {
            synchronized(AevixServiceClient) {
                if (connection === this) connection = null
            }
            unlink()
        }

        fun unlink() {
            runCatching { binder.unlinkToDeath(this, 0) }
        }
    }

    @Volatile private var connection: Connection? = null
    private val service: IFrameworkService?
        get() = connection?.service

    var processName: String = ""
        private set

    @Synchronized
    fun init(appService: IFrameworkService?, niceName: String) {
        if (connection != null || appService == null) return
        if (connect(appService, niceName)) {
            connection?.let { it.restored = attachChannel(it.service, AevixProcessChannel) }
        }
    }

    @Synchronized
    private fun connect(appService: IFrameworkService, niceName: String): Boolean {
        val next = Connection(appService)
        return runCatching {
            next.binder.linkToDeath(next, 0)
            check(next.binder.isBinderAlive) { "Framework service already died" }
            val previous = connection
            connection = next
            processName = niceName
            previous?.unlink()
            true
        }.getOrElse {
            next.unlink()
            Log.e(TAG, "Cannot connect framework service in process: $niceName", it)
            false
        }
    }

    /** Rebinds already loaded system_server modules; does not run entry points or reinstall hooks. */
    @Synchronized
    fun reconnectSystemServer(appService: IFrameworkService, daemon: IAevixDaemon): Boolean {
        if (service?.asBinder() != appService.asBinder() && !connect(appService, "system")) {
            return false
        }
        val current = connection ?: return false
        if (!current.binder.isBinderAlive) return false
        if (current.restored) return true
        if (!AevixModuleManager.reconnectSystemServerServices(daemon)) return false
        current.restored = attachChannel(current.service, AevixProcessChannel)
        return current.restored
    }

    override fun attachProcessChannel(channel: IProcessChannel?) {
        service?.let { attachChannel(it, channel) }
    }

    private fun attachChannel(service: IFrameworkService, channel: IProcessChannel?): Boolean {
        return try {
            service.attachProcessChannel(channel)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach the process channel", t)
            false
        }
    }

    override fun isLogMuted(): Boolean {
        return isLogMutedOrNull() ?: false
    }

    /**
     * The daemon's answer, or null when there is no daemon to ask.
     *
     * An injected process starts silent and only a real answer may unmute it; a connection that is
     * not up yet is not an answer. Collapsing the two into `false`, as the interface method has to,
     * reads as "log everything" and is what put the framework's own lines into every host app's
     * log buffer on a release build.
     */
    fun isLogMutedOrNull(): Boolean? {
        return runCatching { service?.isLogMuted }.getOrNull()
    }

    override fun getLegacyModules(): List<LoadedModule> {
        return runCatching { service?.legacyModules }.getOrNull() ?: emptyList()
    }

    override fun getModules(): List<LoadedModule> {
        return runCatching { service?.modules }.getOrNull() ?: emptyList()
    }

    override fun openManagerApk(): ParcelFileDescriptor? {
        return runCatching { service?.openManagerApk() }.getOrNull()
    }

    override fun requestManagerService(): IBinder? {
        return runCatching { service?.requestManagerService() }.getOrNull()
    }

    override fun asBinder(): IBinder? = service?.asBinder()
}
