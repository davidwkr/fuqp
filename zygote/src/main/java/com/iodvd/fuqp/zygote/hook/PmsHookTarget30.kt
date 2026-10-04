package com.iodvd.fuqp.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.APPS_FILTER_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

@RequiresApi(Build.VERSION_CODES.R)
class PmsHookTarget30 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget30"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookBefore(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getPackageSetting",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { Binder.getCallingUid() },
                    { frame.getArgument(1) as? String },
                    ::getCallingApps,
                    null,
                )
            }

            hookBefore(
                APPS_FILTER_CLASS,
                "shouldFilterApplication",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(1) as Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(3)) },
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
