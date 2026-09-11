package com.iodvd.fuqp.zygote.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ParceledListSlice
import com.iodvd.fuqp.common.settings_presets.AccessibilityPreset
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.ServiceUtils
import com.iodvd.fuqp.zygote.util.ZLUtils.returnType
import com.iodvd.fuqp.zygote.util.ZygoteConstants.ACCESSIBILITY_SERVICE_CLASS

class AccessibilityHook : IFrameworkHook {
    override val TAG = "AccessibilityHook"

    override fun load() {
        BulkHooker.instance.apply {
            hookBefore(
                ACCESSIBILITY_SERVICE_CLASS,
                "getEnabledAccessibilityServiceList",
            ) { methodName, frame, returnValue ->
                val callingApps = ServiceUtils.getCallingApps()
                if (callingApps.isEmpty()) return@hookBefore

                val caller = callingApps.firstOrNull { callerIsSpoofed(it) }
                if (caller != null) {
                    logD(TAG) { "@$methodName returning empty list for ${callingApps.contentToString()}" }

                    val returnedList = java.util.ArrayList<AccessibilityServiceInfo>()
                    returnValue.result = if ("Parcel" in frame.returnType.simpleName) {
                        ParceledListSlice(returnedList)
                    } else {
                        returnedList
                    }
                }
            }

            hookBefore(
                ACCESSIBILITY_SERVICE_CLASS,
                "addClient",
            ) { _, _, returnValue ->
                val callingApps = ServiceUtils.getCallingApps()
                if (callingApps.isEmpty()) return@hookBefore

                val caller = callingApps.firstOrNull { callerIsSpoofed(it) }
                if (caller != null) {
                    returnValue.result = 0L
                }
            }
        }
    }

    private fun callerIsSpoofed(caller: String) =
        service?.getEnabledSettingsPresets(caller)?.contains(AccessibilityPreset.NAME) ?: false
}
