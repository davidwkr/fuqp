package com.iodvd.fuqp.zygote.hook

import android.os.Binder
import com.iodvd.fuqp.common.Constants
import com.iodvd.fuqp.common.Constants.VENDING_PACKAGE_NAME
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument

open class InstallerHookTarget29 : InstallerHookTargetBase() {
    override val TAG = "InstallerHookTarget29"

    // not required until SDK 30
    override val fakeSystemPackageInstallSourceInfo: Any? = null
    override val fakeUserPackageInstallSourceInfo: Any? = null

    override fun load() {
        super.load()

        hooker.apply {
            hookBefore(
                service.pms.javaClass.name,
                "getInstallerPackageName",
            ) { methodName, frame, returnValue ->
                applyInstallerHiding(
                    methodName,
                    Binder.getCallingUid(),
                    { frame.getArgument(1) as? String },
                ) {
                    when (it) {
                        Constants.FAKE_INSTALLATION_SOURCE_USER -> returnValue.result = VENDING_PACKAGE_NAME
                        Constants.FAKE_INSTALLATION_SOURCE_SYSTEM -> returnValue.result = null
                    }
                }
            }
        }
    }
}
