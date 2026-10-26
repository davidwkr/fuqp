package com.iodvd.fuqp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.iodvd.fuqp.service.ConfigManager
import com.iodvd.fuqp.ui.util.AutoProtectNotifier

/**
 * The notification + fallback half of automatic Auto-protect; the always-on zygote service is
 * the primary engine. On install/update this re-applies the rules for the one package — the
 * shared engine is idempotent, so it's harmless when the service already did it — and, when the
 * app matched a rule, posts the result notification (which the system_server service can't do).
 *
 * Honors the user's enabled switch (onlyIfEnabled) and notify toggle. Does nothing if the
 * service connection isn't available from this process; the service still applies on its own.
 */
class PackageEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val result = ConfigManager.applyAutoProtect(
                    listOf(pkg),
                    onlyIfEnabled = true,
                ) { isSystemApp(appContext.packageManager, it) } ?: return@Thread

                // Only surface apps that actually matched a rule, and only if the user wants it.
                if (result.total > 0 && ConfigManager.autoProtect.notify) {
                    AutoProtectNotifier.notifyResult(appContext, result)
                }
            } catch (t: Throwable) {
                // A package broadcast must never crash the manager, but a swallowed invariant
                // failure (e.g. the scope-superset check) is exactly what we want to see.
                Log.w("FUQP-AutoProtect", "receiver apply failed for $pkg", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean = try {
        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
