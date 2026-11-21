package org.aevix

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import java.lang.reflect.Field
import org.aevix.util.Utils
import org.aevix.impl.hookers.HandleSystemServerProcessHooker
import org.aevix.impl.hooks.AevixHookBuilder
import org.aevix.service.BridgeService

/**
 * Handles System-Server side logic for the Parasitic Manager.
 *
 * When a user tries to open the Aevix Manager, the system normally wouldn't know how to handle it
 * because it isn't "installed." This class intercepts the activity resolution and tells the system
 * to launch it in a special process.
 */
class ParasiticManagerSystemHooker : HandleSystemServerProcessHooker.Callback {

    companion object {
        @JvmStatic
        fun start() {
            // Register this class as the handler for system_server initialization.
            // This ensures the hook is deferred until the System Server ClassLoader is fully ready.
            HandleSystemServerProcessHooker.callback = ParasiticManagerSystemHooker()
        }
    }

    @SuppressLint("PrivateApi")
    override fun onSystemServerLoaded(classLoader: ClassLoader) {
        hookActivityResolution(classLoader)
        hookSplashScreenSuppression(classLoader)
    }

    private fun hookActivityResolution(classLoader: ClassLoader) {
        runCatching {
                // Android versions change the name of the internal class responsible for activity
                // tracking.
                // We check the most likely candidates based on API levels (9.0 through 14+).
                val supervisorClass =
                    try {
                        // Android 12.0 - 14+
                        Class.forName(
                            "com.android.server.wm.ActivityTaskSupervisor",
                            false,
                            classLoader,
                        )
                    } catch (e: ClassNotFoundException) {
                        try {
                            // Android 10 - 11
                            Class.forName(
                                "com.android.server.wm.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        } catch (e2: ClassNotFoundException) {
                            // Android 8.1 - 9
                            Class.forName(
                                "com.android.server.am.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        }
                    }

                // Every overload matters: explicit-component launches use a different path
                // from implicit intents, and reflection does not promise a stable method order.
                val resolveMethods =
                    supervisorClass.declaredMethods.filter { it.name == "resolveActivity" }
                if (resolveMethods.isEmpty()) {
                    Utils.logD("ActivityTaskSupervisor has no resolveActivity; manager redirection unavailable")
                }

                for (resolveMethod in resolveMethods) {
                    runCatching {
                        AevixHookBuilder(resolveMethod).intercept { chain ->
                            val result = chain.proceed()
                            val intent = chain.args.firstOrNull() as? Intent
                                ?: return@intercept result
                            if (!intent.hasCategory(BuildConfig.ManagerPackageName + ".LAUNCH_MANAGER")) {
                                return@intercept result
                            }

                            val originalActivityInfo = result as? ActivityInfo
                                ?: run {
                                    Utils.logD(
                                        "Redirection: result is not ActivityInfo (was ${result?.javaClass?.name})"
                                    )
                                    return@intercept result
                                }
                            if (originalActivityInfo.packageName != BuildConfig.InjectedPackageName) {
                                return@intercept result
                            }

                            val redirectedInfo = ActivityInfo(originalActivityInfo).apply {
                                processName = BuildConfig.ManagerPackageName
                                theme = android.R.style.Theme_DeviceDefault_Settings
                                flags = flags and (
                                    ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS or
                                        ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS
                                ).inv()
                            }
                            BridgeService.getService()?.preStartManager()
                            redirectedInfo
                        }
                    }.onFailure {
                        Utils.logE(
                            "Failed to hook resolveActivity(${resolveMethod.parameterCount} args)",
                            it,
                        )
                    }
                }

                Utils.logD(
                    "Installed manager redirection on ${resolveMethods.size} resolveActivity overload(s)."
                )
            }
            .onFailure { Utils.logE("Failed to hook system server activity resolution", it) }
    }

    private fun hookSplashScreenSuppression(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ---------------------------------------------------------
            // Android 12+ (API 31+) - StartingSurfaceController
            // ---------------------------------------------------------
            runCatching {
                    val controllerClass =
                        Class.forName(
                            "com.android.server.wm.StartingSurfaceController",
                            false,
                            classLoader,
                        )
                    val createMethod =
                        controllerClass.declaredMethods.first {
                            it.name == "createSplashScreenStartingSurface"
                        }

                    val activityRecordClass =
                        Class.forName("com.android.server.wm.ActivityRecord", false, classLoader)

                    // Cache the field to avoid overhead during hook execution
                    val infoField: Field =
                        activityRecordClass.getDeclaredField("info").apply { isAccessible = true }

                    AevixHookBuilder(createMethod).intercept { chain ->
                        // Both reads are guarded: this runs on the window manager's own thread for
                        // every activity start, and a throw here would be answered by the
                        // protective mode replaying the original - which is the right outcome, but
                        // only after the exception has crossed a path that measures its own
                        // latency. An unknown shape simply means "not ours".
                        val activityRecord = chain.args.firstOrNull()
                        val info =
                            if (activityRecord == null) null
                            else runCatching { infoField.get(activityRecord) as? ActivityInfo }
                                .getOrNull()

                        if (info?.processName == BuildConfig.ManagerPackageName) {
                            Utils.logD("Suppressing Android 12+ Splash Screen for Aevix Manager.")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                }
                .onFailure { Utils.logE("Failed to hook StartingSurfaceController", it) }
        } else {
            // ---------------------------------------------------------
            // Android 8.1 - 11 (API 27 - 30) - ActivityRecord
            // ---------------------------------------------------------
            runCatching {
                    val recordClassName =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            "com.android.server.wm.ActivityRecord" // API 29-30
                        } else {
                            "com.android.server.am.ActivityRecord" // API 27-28
                        }

                    val recordClass = Class.forName(recordClassName, false, classLoader)
                    val showMethod =
                        recordClass.declaredMethods.first { it.name == "showStartingWindow" }

                    // Cache the field
                    val infoField: Field =
                        recordClass.getDeclaredField("info").apply { isAccessible = true }

                    AevixHookBuilder(showMethod).intercept { chain ->
                        // Same guard as the API 31+ branch above.
                        val activityRecord = chain.thisObject
                        val info =
                            if (activityRecord == null) null
                            else runCatching { infoField.get(activityRecord) as? ActivityInfo }
                                .getOrNull()

                        if (info?.processName == BuildConfig.ManagerPackageName) {
                            Utils.logD("Suppressing Legacy Starting Window for Aevix Manager.")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                }
                .onFailure { Utils.logE("Failed to hook Legacy ActivityRecord", it) }
        }
    }
}
