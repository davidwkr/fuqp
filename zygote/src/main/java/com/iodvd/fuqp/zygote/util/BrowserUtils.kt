package com.iodvd.fuqp.zygote.util

import android.os.Build
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.ZLUtils.callMethodWithTypes
import com.iodvd.fuqp.zygote.util.ZLUtils.getObjectField

object BrowserUtils {
    const val TAG = "BrowserUtils"

    fun getDefaultBrowser(userId: Int): String? {
        return try {
            getDefaultBrowserPMN(userId)
        } catch (e: Throwable) {
            logD(TAG, e) { "Getting default browser failed" }
            null
        }
    }

    private fun getDefaultBrowserPMN(userId: Int): String? {
        val pms = getObjectField(
            service?.pmn ?: return null,
            "mPm",
        ) ?: return null

        return when (Build.VERSION.SDK_INT) {
            Build.VERSION_CODES.Q -> callMethodWithTypes(
                pms,
                "getDefaultBrowserPackageName",
                arrayOf(Int::class.javaPrimitiveType!!),
                arrayOf(userId)
            ) as? String
            Build.VERSION_CODES.R -> {
                val permissionManager = getObjectField(
                    pms,
                    "mPermissionManager",
                ) ?: return null

                callMethodWithTypes(
                    permissionManager,
                    "getDefaultBrowser",
                    arrayOf(Int::class.javaPrimitiveType!!),
                    arrayOf(userId)
                ) as? String
            }
            else -> {
                val defaultAppProvider = getObjectField(
                    pms,
                    "mDefaultAppProvider",
                ) ?: return null

                callMethodWithTypes(
                    defaultAppProvider,
                    "getDefaultBrowser",
                    arrayOf(Int::class.javaPrimitiveType!!),
                    arrayOf(userId)
                ) as? String
            }
        }
    }
}
