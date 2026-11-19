package org.aevix.impl

import android.content.SharedPreferences
import android.os.Bundle
import android.os.RemoteException
import android.util.ArraySet
import io.github.libxposed.api.error.XposedFrameworkError
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import org.aevix.ipc.IModuleService
import org.aevix.ipc.IRemotePreferenceCallback
import org.aevix.util.Log

@Suppress("DEPRECATION", "UNCHECKED_CAST")
private inline fun <reified T> Bundle.getSerializableCompat(key: String): T? {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getSerializable(key, java.io.Serializable::class.java) as? T
    } else {
        getSerializable(key) as? T
    }
}

@Suppress("UNCHECKED_CAST")
internal class AevixRemotePreferences(service: IModuleService, private val group: String) :
    SharedPreferences {

    private val map = ConcurrentHashMap<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val subscriptionLock = Any()
    private var subscription: Subscription? = null

    private inner class Subscription(val binder: android.os.IBinder) :
        IRemotePreferenceCallback.Stub() {
        var ready = false
        val pending = ArrayList<Bundle>()

        override fun onRemotePreferencesChanged(bundle: Bundle) {
            val changes =
                synchronized(subscriptionLock) {
                    if (subscription !== this) return
                    if (!ready) {
                        pending.add(Bundle(bundle))
                        return
                    }
                    applyChanges(bundle)
                }
            notifyChanges(changes)
        }
    }

    private fun applyChanges(bundle: Bundle): Set<String> {
        val changes = ArraySet<String>()
        if (bundle.getBoolean("clear", false)) {
            changes.addAll(map.keys)
            map.clear()
        }
        if (bundle.containsKey("delete")) {
            val deletes = bundle.getSerializableCompat<Set<String>>("delete") as? Set<String>
            if (deletes != null) {
                changes.addAll(deletes)
                deletes.forEach { map.remove(it) }
            }
        }
        if (bundle.containsKey("put")) {
            val puts = bundle.getSerializableCompat<Map<String, Any>>("put") as? Map<String, Any>
            if (puts != null) {
                map.putAll(puts)
                changes.addAll(puts.keys)
            }
        }
        return changes
    }

    private fun notifyChanges(changes: Set<String>) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (key in changes) {
            snapshot.forEach { listener ->
                listener.onSharedPreferenceChanged(this, key)
            }
        }
    }

    internal fun reconnect(replacement: IModuleService) {
        val next = Subscription(checkNotNull(replacement.asBinder()))
        val previous =
            synchronized(subscriptionLock) {
                val old = subscription
                if (old?.binder == next.binder && old.ready) return
                subscription = next
                old
            }
        val changes =
            try {
                val output = replacement.requestRemotePreferences(group, next)
                synchronized(subscriptionLock) {
                    if (subscription !== next) return
                    val before = HashMap(map)
                    val initial =
                        output.getSerializableCompat<Map<String, Any>>("map").orEmpty()
                    map.clear()
                    map.putAll(initial)
                    // Updates received during the initial snapshot are applied after that snapshot.
                    next.pending.forEach { applyChanges(it) }
                    next.pending.clear()
                    next.ready = true
                    (before.keys + map.keys).filterTo(mutableSetOf()) { before[it] != map[it] }
                }
            } catch (failure: Throwable) {
                synchronized(subscriptionLock) {
                    if (subscription === next) subscription = previous
                }
                throw failure
            }
        runCatching { notifyChanges(changes) }
            .onFailure {
                Log.e("AevixContext", "Remote preference listener failed for $group", it)
            }
    }

    init {
        try {
            reconnect(service)
        } catch (e: RemoteException) {
            Log.e("AevixContext", "Failed to request remote preferences for group: $group", e)
            throw XposedFrameworkError("Remote preferences IPC failure", e)
        }
    }

    override fun getAll(): Map<String, *> = TreeMap(map)

    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        map[key] as? Set<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor =
        throw UnsupportedOperationException("Read only implementation")

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(listeners) { listeners.remove(listener) }
    }
}
