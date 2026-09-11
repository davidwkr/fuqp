package com.iodvd.fuqp.zygote.hook

import android.content.Intent
import android.os.Build
import com.iodvd.fuqp.common.CollectionUtils.firstOrNullWithType
import com.iodvd.fuqp.common.CollectionUtils.lastOrNullWithType
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ZLUtils.args
import com.iodvd.fuqp.zygote.util.ZLUtils.getStaticIntField
import com.iodvd.fuqp.zygote.util.ZygoteConstants.ACTIVITY_MANAGER_SERVICE_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.BROADCAST_CONTROLLER_CLASS

class BroadcastHook : IFrameworkHook {
    override val TAG = "BroadcastHook"

    companion object {
        private val fakeReturnCode by lazy {
            getStaticIntField(
                "android.app.ActivityManager",
                "BROADCAST_SUCCESS",
            )
        }
    }

    override fun load() {
        logI(TAG) { "Load hook" }

        BulkHooker.instance.apply {
            hookBefore(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    BROADCAST_CONTROLLER_CLASS
                } else {
                    ACTIVITY_MANAGER_SERVICE_CLASS
                },
                "broadcastIntentLocked",
            ) { _, frame, returnValue ->
                val userId = frame.args.lastOrNullWithType<Int>() ?: return@hookBefore
                val caller = frame.args.firstOrNullWithType<String>() ?: return@hookBefore
                val intent = frame.args.firstOrNullWithType<Intent>() ?: return@hookBefore
                val targetApp = intent.component?.packageName

                if (service?.shouldHideActivityLaunch(caller, targetApp, userId) ?: false) {
                    logD(TAG) { "@broadcastIntent: insecure query from $caller, target: ${intent.component}" }
                    returnValue.result = fakeReturnCode
                    service?.increaseALFilterCount(caller)
                }
            }
        }
    }
}
