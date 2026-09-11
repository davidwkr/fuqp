package com.iodvd.fuqp.zygote.hook

import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

class PmsHookTarget29 : PmsHookTargetBase() {

    override val TAG = "PmsHookTarget29"

    // not required until SDK 30
    override val fakeSystemPackageInstallSourceInfo = null
    override val fakeUserPackageInstallSourceInfo = null

    @Suppress("UNCHECKED_CAST")
    override fun load() {
        logI(TAG) { "Load hook" }

        BulkHooker.instance.apply {
            hookBefore(
                service!!.pms::class.java.name,
                "filterAppAccessLPr",
                paramCount = 5,
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    { frame.getArgument(2) as Int? },
                    { getPackageNameFromPackageSettings(frame.getArgument(1)) },
                    ::getCallingApps,
                    { returnValue.result = true },
                )
            }

            hookBefore(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getPackageInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    { frame.getArgument(4) as Int? },
                    { frame.getArgument(1) as String? },
                    ::getCallingApps,
                    { returnValue.result = null },
                )
            }

            hookBefore(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getApplicationInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    { frame.getArgument(3) as Int? },
                    { frame.getArgument(1) as String? },
                    ::getCallingApps,
                    { returnValue.result = null },
                )
            }
        }

        super.load()
    }
}
