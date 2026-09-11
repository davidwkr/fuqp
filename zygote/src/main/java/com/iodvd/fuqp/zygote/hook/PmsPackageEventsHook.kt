package com.iodvd.fuqp.zygote.hook

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.BROADCAST_HELPER_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PACKAGE_MONITOR_CLASS

class PmsPackageEventsHook : IFrameworkHook {
    override val TAG = "PmsPackageEventsHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        BulkHooker.instance.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hookedMethodName = "sendPackageBroadcastAndNotify"

                hookBefore(
                    BROADCAST_HELPER_CLASS,
                    hookedMethodName,
                ) { _, frame, _ ->
                    service?.handlePackageEvent(
                        frame.getArgument(1) as? String,
                        frame.getArgument(2) as? String,
                        frame.getArgument(3) as? Bundle,
                    )
                }

                if (!isHookAvailable(BROADCAST_HELPER_CLASS, hookedMethodName)) {
                    hookBefore(
                        PACKAGE_MONITOR_CLASS,
                        "onReceive",
                    ) { _, frame, _ ->
                        val intent = frame.getArgument(2) as? Intent ?: return@hookBefore

                        service?.handlePackageEvent(
                            intent.action,
                            intent.data?.encodedSchemeSpecificPart,
                            intent.extras,
                        )
                    }
                }
            } else {
                hookBefore(
                    PACKAGE_MANAGER_SERVICE_CLASS,
                    "sendPackageBroadcast",
                ) { _, frame, _ ->
                    service?.handlePackageEvent(
                        frame.getArgument(1) as? String,
                        frame.getArgument(2) as? String,
                        frame.getArgument(3) as? Bundle,
                    )
                }
            }
        }
    }
}
