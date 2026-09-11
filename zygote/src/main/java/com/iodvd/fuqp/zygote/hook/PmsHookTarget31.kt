package com.iodvd.fuqp.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import com.iodvd.fuqp.common.Constants.VENDING_PACKAGE_NAME
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import com.iodvd.fuqp.zygote.util.ZLUtils.findConstructor
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.APPS_FILTER_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PMS_COMPUTER_TRACKER_CLASS

@RequiresApi(Build.VERSION_CODES.S)
class PmsHookTarget31 : PmsHookTargetBase() {

    override val TAG = "PmsHookTarget31"

    override val fakeSystemPackageInstallSourceInfo: Any by lazy {
        findConstructor(
            "android.content.pm.InstallSourceInfo",
            4,
        )!!.newInstance(
            null,
            null,
            null,
            null,
        )
    }

    override val fakeUserPackageInstallSourceInfo: Any by lazy {
        findConstructor(
            "android.content.pm.InstallSourceInfo",
            4,
        )!!.newInstance(
            VENDING_PACKAGE_NAME,
            psPackageInfo?.signingInfo,
            VENDING_PACKAGE_NAME,
            VENDING_PACKAGE_NAME,
        )
    }

    override fun load() {
        logI(TAG) { "Load hook" }

        BulkHooker.instance.apply {
            hookBefore(
                PMS_COMPUTER_TRACKER_CLASS,
                "getPackageSetting",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    { Binder.getCallingUid() },
                    { frame.getArgument(1) as String? },
                    ::getCallingApps,
                    { returnValue.result = null },
                )
            }

            hookBefore(
                PMS_COMPUTER_TRACKER_CLASS,
                "getPackageSettingInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    { frame.getArgument(2) as Int? },
                    { frame.getArgument(1) as String? },
                    ::getCallingApps,
                    { returnValue.result = null },
                )
            }

            hookBefore(
                PMS_COMPUTER_TRACKER_CLASS,
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
                PMS_COMPUTER_TRACKER_CLASS,
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

            hookBefore(
                APPS_FILTER_CLASS,
                "shouldFilterApplication",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    { frame.getArgument(1) as Int? },
                    { getPackageNameFromPackageSettings(frame.getArgument(3)) },
                    ::getCallingApps,
                    { returnValue.result = true },
                )
            }
        }

        super.load()
    }
}
