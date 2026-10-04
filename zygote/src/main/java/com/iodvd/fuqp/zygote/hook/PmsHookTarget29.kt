package com.iodvd.fuqp.zygote.hook

import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

class PmsHookTarget29 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget29"

    @Suppress("UNCHECKED_CAST")
    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookBefore(
                service.pms::class.java.name,
                "filterAppAccessLPr",
                argumentCount = 5,
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(2) as? Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(1)) },
                    ::getCallingApps,
                    true,
                )
            }

            hookAfter(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getPackageInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(4) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getCallingApps,
                    null,
                )
            }

            // The single exact-name opener on this API level. createPackageContext resolves
            // through here, and the shared gate it reaches only exempts packagesVisibleOnExactName
            // while this call's window is open. See ExactNameLookup.
            hookAround(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getApplicationInfoInternal",
                before = { _, frame ->
                    ExactNameLookup.beginIfVisibleOnExactName(frame.getArgument(1) as? String)
                },
                after = { methodName, frame, returnValue ->
                    ExactNameLookup.end()
                    applyPackageHiding(
                        methodName,
                        returnValue,
                        { frame.getArgument(3) as? Int },
                        { frame.getArgument(1) as? String },
                        ::getCallingApps,
                        null,
                        exactNameLookup = true,
                    )
                },
            )
        }
    }
}
