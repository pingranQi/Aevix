package org.aevix.daemon.system

import android.app.IActivityManager
import android.content.Context
import android.content.pm.IPackageManager
import android.os.IBinder
import android.os.IPowerManager
import android.os.IServiceManager
import android.os.IUserManager
import android.os.RemoteException
import android.os.ServiceManager
import com.android.internal.os.BinderInternal
import hidden.HiddenApiBridge
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A thread-safe, lazy property delegate that fetches an Android system service Binder.
 * Automatically links a DeathRecipient to clear the cache if the service dies.
 */
class SystemService<T>(private val name: String, private val asInterface: (IBinder) -> T) :
    ReadOnlyProperty<Any?, T?> {
  private val connection = BinderConnection<T, IBinder>()

  override fun getValue(thisRef: Any?, property: KProperty<*>): T? {
    connection.value?.let { return it }
    return synchronized(this) {
      connection.value?.let { return it }
      val binder = ServiceManager.getService(name) ?: return null
      try {
        // Each death recipient captures the Binder it belongs to. A delayed death callback from a
        // previous system_server instance must not clear a newer connection.
        binder.linkToDeath(IBinder.DeathRecipient { connection.clearIfCurrent(binder) }, 0)
        val service = asInterface(binder)
        connection.publish(binder, service)
        service
      } catch (e: RemoteException) {
        connection.clearIfCurrent(binder)
        null
      }
    }
  }
}

// --- Top-level System Binders ---
val activityManager: IActivityManager? by
    SystemService(Context.ACTIVITY_SERVICE, IActivityManager.Stub::asInterface)
val packageManager: IPackageManager? by SystemService("package", IPackageManager.Stub::asInterface)
val userManager: IUserManager? by
    SystemService(Context.USER_SERVICE, IUserManager.Stub::asInterface)
val powerManager: IPowerManager? by
    SystemService(Context.POWER_SERVICE, IPowerManager.Stub::asInterface)

/**
 * Holds global state received from system_server during the late injection phase. Used for forging
 * calls to ActivityManager that require a valid caller context.
 */
object SystemContext {
  @Volatile var appThread: android.app.IApplicationThread? = null
  @Volatile var token: IBinder? = null
}

fun getSystemServiceManager(): IServiceManager {
  return IServiceManager.Stub.asInterface(
      HiddenApiBridge.Binder_allowBlocking(BinderInternal.getContextObject()))
}
