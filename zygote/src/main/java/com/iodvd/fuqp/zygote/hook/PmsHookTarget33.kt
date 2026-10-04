package com.iodvd.fuqp.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import com.iodvd.fuqp.common.CollectionUtils.firstOrNullWithType
import com.iodvd.fuqp.common.OSUtils
import com.iodvd.fuqp.common.Utils
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import com.iodvd.fuqp.zygote.util.ZLUtils.args
import com.iodvd.fuqp.zygote.util.ZLUtils.findMethod
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.APPS_FILTER_IMPL_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.COMPUTER_ENGINE_CLASS

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
open class PmsHookTarget33 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget33"

    protected open val getPackagesForUidMethod by lazy {
        findMethod(
            "com.android.server.pm.Computer",
            "getPackagesForUid",
            isDeclared = false,
            systemClassLoader = true,
            Int::class.java,
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            // Samsung related fix
            if (OSUtils.isSamsung()) {
                hookAfter(
                    COMPUTER_ENGINE_CLASS,
                    "generatePackageInfo",
                ) { methodName, frame, returnValue ->
                    applyPackageHiding(
                        methodName,
                        returnValue,
                        { Binder.getCallingUid() },
                        { getPackageNameFromPackageSettings(frame.getArgument(1)) },
                        ::getCallingApps,
                        null,
                    )
                }
            } else {
                hookBefore(
                    COMPUTER_ENGINE_CLASS,
                    "addPackageHoldingPermissions",
                ) { methodName, frame, returnValue ->
                    applyPackageHiding(
                        methodName,
                        returnValue,
                        { Binder.getCallingUid() },
                        { getPackageNameFromPackageSettings(frame.getArgument(2)) },
                        ::getCallingApps,
                        null,
                    )
                }
            }

            hookAfter(
                COMPUTER_ENGINE_CLASS,
                "getPackageInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.args.firstOrNullWithType() },
                    { frame.args.firstOrNullWithType() },
                    ::getCallingApps,
                    null,
                )
            }

            // The single exact-name opener on T+. createPackageContext resolves through here,
            // and the shared gate it reaches - shouldFilterApplication below - only exempts
            // packagesVisibleOnExactName while this call's window is open. See ExactNameLookup.
            hookAround(
                COMPUTER_ENGINE_CLASS,
                "getApplicationInfoInternal",
                before = { _, frame ->
                    ExactNameLookup.beginIfVisibleOnExactName(frame.args.firstOrNullWithType<String>())
                },
                after = { methodName, frame, returnValue ->
                    ExactNameLookup.end()
                    applyPackageHiding(
                        methodName,
                        returnValue,
                        { frame.args.firstOrNullWithType() },
                        { frame.args.firstOrNullWithType() },
                        ::getCallingApps,
                        null,
                        exactNameLookup = true,
                    )
                },
            )

            hookBefore(
                APPS_FILTER_IMPL_CLASS,
                "shouldFilterApplication",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(2) as? Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(4)) },
                    { _, it ->
                        Utils.binderLocalScope {
                            getPackagesForUidMethod.invoke(frame.getArgument(1), it) as? Array<String>
                        }
                    },
                    true,
                )
            }

            // hookAfter only reads the real return value back out of the frame on T+, and
            // ComputerEngine is a T-era class in any case - so this lives on the T+ target.
            hookIntentQuery()
        }
    }
}
