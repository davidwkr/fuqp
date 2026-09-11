package com.iodvd.fuqp.zygote.service

import android.content.AttributionSource
import android.content.pm.IPackageManager
import android.os.Build
import android.os.Bundle
import com.iodvd.fuqp.common.Constants
import com.iodvd.fuqp.common.Utils.getUserFromCallingUid
import com.iodvd.fuqp.common.BuildConfig
import com.iodvd.fuqp.zygote.ZygoteEntry
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logE
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ServiceUtils.isConflictingModuleInstalled
import com.iodvd.fuqp.zygote.util.ServiceUtils.waitForService
import com.iodvd.fuqp.zygote.util.ZLUtils.getStaticIntField
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.adapter.UidObserverAdapter

object UserService {

    private const val TAG = "FUQP-UserService"

    private val managerAppUid get() = service?.appUid ?: -1

    private val uidObserver = object : UidObserverAdapter() {
        override fun onUidActive(uid: Int) {
            if (managerAppUid < 0 || uid != managerAppUid) {
                return
            }

            try {
                val userId = getUserFromCallingUid(uid)

                logD(TAG) { "Calculated user id: $userId" }

                val provider = ActivityManagerApis.getContentProviderExternal(Constants.PROVIDER_AUTHORITY, userId, null, null)
                assert (provider != null) {
                    "Failed to get provider"
                }
                val extras = Bundle()
                extras.putBinder("binder", service)
                val reply = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val attr = AttributionSource.Builder(1000).setPackageName("android").build()
                    provider?.call(attr, Constants.PROVIDER_AUTHORITY, "", null, extras)
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    provider?.call("android", null, Constants.PROVIDER_AUTHORITY, "", null, extras)
                } else {
                    provider?.call("android", Constants.PROVIDER_AUTHORITY, "", null, extras)
                }
                if (reply == null) {
                    logE(TAG) { "Failed to send binder to app" }
                    return
                }
                logI(TAG) { "Send binder to app" }
            } catch (e: Throwable) {
                logE(TAG, e) { "onUidActive" }
            }
        }
    }

    fun register(pms: IPackageManager, pmn: Any?) {
        logI(TAG) { "Initialize FUQPService - Version ${BuildConfig.APP_VERSION_NAME}" }

        val managerWorkMode = if (pms.isConflictingModuleInstalled()) {
            logE(ZygoteEntry.TAG) { "Conflicting module detected, skipping hook" }
            Constants.MANAGER_WORK_MODE_NO_HOOKS
        } else {
            Constants.MANAGER_WORK_MODE_LOADING
        }

        waitForService("activity")
        ActivityManagerApis.registerUidObserver(
            uidObserver,
            getActMgrField("UID_OBSERVER_ACTIVE"),
            getActMgrField("PROCESS_STATE_TOP"),
            null
        )

        logI(TAG) { "Registered observer" }

        // no need to put in a variable
        FUQPService(pms, pmn, managerWorkMode)
    }

    private fun getActMgrField(name: String) = getStaticIntField(
        "android.app.ActivityManager",
        name,
    )
}
