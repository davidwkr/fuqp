package com.iodvd.fuqp.zygote.hook

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Build
import com.v7878.unsafe.Reflection.getDeclaredField
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.iodvd.fuqp.common.Constants
import com.iodvd.fuqp.common.OSUtils
import com.iodvd.fuqp.common.Utils.getPackageName
import com.iodvd.fuqp.common.Utils.getPackageUidCompat
import com.iodvd.fuqp.common.Utils.getUserFromCallingUid
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.service.ReturnValue
import com.iodvd.fuqp.zygote.service.SystemServerHook
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.Logcat.logV
import com.iodvd.fuqp.zygote.util.Logcat.logW
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ZLUtils.args
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZLUtils.getStaticIntField
import com.iodvd.fuqp.zygote.util.ZLUtils.setArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.ACTIVITY_STACK_SUPERVISOR_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.ACTIVITY_STARTER_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.ACTIVITY_TASK_SUPERVISOR_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.COMPUTER_ENGINE_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PMS_COMPUTER_ENGINE_CLASS

class ActivityHook : IFrameworkHook {
    override val TAG = "ActivityHook"

    companion object {
        private const val DIAG = false

        private val unresolvableComponent = ComponentName("", "")

        private const val APRF_FILTER_CALLING_UID = 4
        private const val APRF_RESOLVE_FOR_START = 5
        private const val APRF_USER_ID = 6
        private const val APRF_INTENT = 7

        private const val PRE_R_INTENT = 2
        private const val PRE_R_ACTIVITY_INFO = 5
        private const val PRE_R_RESOLVE_INFO = 6
        private const val PRE_R_CALLING_UID = 13
        private const val PRE_R_CALLING_PACKAGE = 14
        private const val PRE_R_REAL_CALLING_UID = 16

        private val fakeReturnCode by lazy {
            getStaticIntField(
                "android.app.ActivityManager",
                "START_CLASS_NOT_FOUND",
            )
        }
    }

    private class RequestFields(requestClass: Class<*>) {
        val callingPackage = getDeclaredField(requestClass, "callingPackage").apply { isAccessible = true }
        val intent = getDeclaredField(requestClass, "intent").apply { isAccessible = true }
        val callingUid = getDeclaredField(requestClass, "callingUid").apply { isAccessible = true }
        val realCallingUid = getDeclaredField(requestClass, "realCallingUid").apply { isAccessible = true }
        val activityInfo = getDeclaredField(requestClass, "activityInfo").apply { isAccessible = true }
        val resolveInfo = getDeclaredField(requestClass, "resolveInfo").apply { isAccessible = true }
    }

    override fun load() {
        logI(TAG) { "Load hook" }

        BulkHooker.instance.apply {
            hookBefore(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ACTIVITY_TASK_SUPERVISOR_CLASS
                } else {
                    ACTIVITY_STACK_SUPERVISOR_CLASS
                },
                "checkStartAnyActivityPermission",
            ) { methodName, frame, _ ->
                if (DIAG) logV(TAG) { "$methodName: ${frame.args.contentToString()}" }

                // just an empty hook that does nothing
            }

            val aPRFClazz = when(Build.VERSION.SDK_INT) {
                Build.VERSION_CODES.Q, Build.VERSION_CODES.R -> PACKAGE_MANAGER_SERVICE_CLASS
                Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2 -> PMS_COMPUTER_ENGINE_CLASS
                else -> COMPUTER_ENGINE_CLASS
            }

            if (!OSUtils.isSamsung()) {
                hookBefore(
                    aPRFClazz,
                    "applyPostResolutionFilter",
                ) { methodName, frame, _ ->
                    @Suppress("UNCHECKED_CAST") // I know what I do
                    val list = frame.args[1] as List<ResolveInfo>?

                    if (DIAG) logD(TAG) { resolveEntryDiag(methodName, frame, list) }

                    if (list.isNullOrEmpty()) {
                        if (DIAG) logD(TAG) { "@$methodName: exit, no candidates" }
                        return@hookBefore
                    }

                    val callingUid = frame.getArgument(APRF_FILTER_CALLING_UID) as? Int ?: run {
                        if (DIAG) logD(TAG) { "@$methodName: exit, unexpected signature" }
                        return@hookBefore
                    }
                    if (callingUid == Constants.UID_SYSTEM) {
                        if (DIAG) logD(TAG) { "@$methodName: exit, system uid" }
                        return@hookBefore
                    }

                    val callingUserId = getUserFromCallingUid(callingUid)
                    val callingApps = getCallingApps(callingUid)
                    val caller = callingApps.firstOrNull { service?.isHookEnabled(it) ?: false }
                    if (caller == null) {
                        if (DIAG) logD(TAG) { "@$methodName: exit, no scoped caller for $callingUid" }
                        return@hookBefore
                    }

                    logV(TAG) { "@$methodName: $caller requested a resolve info" }

                    val filteredList = list.filter { resolveInfo ->
                        val targetApp = resolveInfo.getPackageName()

                        logV(TAG) { "@$methodName: Checking $targetApp for $caller" }

                        (!(service?.shouldHideActivityLaunch(caller, targetApp, callingUserId) ?: false)).apply {
                            if (!this) {
                                logD(TAG) { "@$methodName: insecure query from $caller, target: $targetApp" }
                            }
                        }
                    }

                    if (filteredList.size != list.size) {
                        frame.setArgument(1, filteredList)

                        service?.increasePMFilterCount(caller, list.size - filteredList.size)
                    }
                }
            }

            val isInxLockerAvailable = service != null && service!!.pms.getPackageUidCompat(
                "io.github.chimio.inxlocker", 0, 0
            ) >= 0

            if (isInxLockerAvailable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val fields = RequestFields(Class.forName(
                        "$ACTIVITY_STARTER_CLASS\$Request", false, SystemServerHook.classLoader,
                    ))
                    hookBefore(
                        ACTIVITY_STARTER_CLASS,
                        "executeRequest",
                    ) { methodName, frame, returnValue ->
                        guardRequest(methodName, frame.getArgument(1), fields, returnValue)
                    }
                } else {
                    val paramCount = findParamCount(ACTIVITY_STARTER_CLASS, "startActivity") {
                        it.size >= PRE_R_REAL_CALLING_UID &&
                                it[PRE_R_INTENT - 1] == Intent::class.java &&
                                it[PRE_R_ACTIVITY_INFO - 1] == ActivityInfo::class.java &&
                                it[PRE_R_RESOLVE_INFO - 1] == ResolveInfo::class.java &&
                                it[PRE_R_CALLING_UID - 1] == Int::class.javaPrimitiveType &&
                                it[PRE_R_CALLING_PACKAGE - 1] == String::class.java &&
                                it[PRE_R_REAL_CALLING_UID - 1] == Int::class.javaPrimitiveType
                    }

                    if (paramCount == BulkHooker.PARAMETER_COUNT_UNKNOWN) {
                        logW(TAG) { "No startActivity overload matches the expected layout" }
                    } else {
                        hookBefore(
                            ACTIVITY_STARTER_CLASS,
                            "startActivity",
                            paramCount,
                        ) { methodName, frame, returnValue ->
                            guardFrame(methodName, frame, returnValue)
                        }
                    }
                }
            } else {
                val requestField = getDeclaredField(Class.forName(
                    ACTIVITY_STARTER_CLASS, false, SystemServerHook.classLoader,
                ), "mRequest").apply { isAccessible = true }
                val fields = RequestFields(requestField.type)
                hookBefore(
                    ACTIVITY_STARTER_CLASS,
                    "execute",
                ) { methodName, frame, returnValue ->
                    if (service == null) return@hookBefore
                    val request = requestField.get(frame.getArgument(0)) ?: return@hookBefore

                    guardRequest(methodName, request, fields, returnValue)
                }
            }
        }
    }

    private fun guardRequest(
        methodName: String,
        request: Any,
        fields: RequestFields,
        returnValue: ReturnValue,
    ) {
        val svc = service ?: return

        val caller = fields.callingPackage.get(request) as? String ?: return
        if (!svc.isHookEnabled(caller)) return

        val intent = fields.intent.get(request) as? Intent ?: return
        val targetApp = intent.component?.packageName ?: return
        val callingUid = requestUid(request, fields)

        if (DIAG) logD(TAG) {
            "@$methodName: caller=$caller uid=$callingUid binder=${Binder.getCallingUid()} " +
                    "target=${intent.component}"
        }

        if (!svc.shouldHideActivityLaunch(caller, targetApp, getUserFromCallingUid(callingUid))) return

        logD(TAG) { "@$methodName: insecure start from $caller, target: ${intent.component}" }

        val deflected = runCatching {
            fields.intent.set(request, unresolvable(intent))
            fields.activityInfo.set(request, null)
            fields.resolveInfo.set(request, null)

            fields.activityInfo.get(request) == null &&
                    isUnresolvable(fields.intent.get(request))
        }.getOrDefault(false)

        if (!deflected) {
            logW(TAG) { "@$methodName: cannot deflect the request, replacing the result" }
            returnValue.result = fakeReturnCode
        }

        svc.increaseALFilterCount(caller)
    }

    private fun guardFrame(methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) {
        val svc = service ?: return

        val caller = frame.getArgument(PRE_R_CALLING_PACKAGE) as? String ?: return
        if (!svc.isHookEnabled(caller)) return

        val intent = frame.getArgument(PRE_R_INTENT) as? Intent ?: return
        val targetApp = intent.component?.packageName ?: return
        val callingUid = frameUid(frame)

        if (DIAG) logD(TAG) {
            "@$methodName: caller=$caller uid=$callingUid binder=${Binder.getCallingUid()} " +
                    "target=${intent.component}"
        }

        if (!svc.shouldHideActivityLaunch(caller, targetApp, getUserFromCallingUid(callingUid))) return

        logD(TAG) { "@$methodName: insecure start from $caller, target: ${intent.component}" }

        val deflected = runCatching {
            frame.setArgument(PRE_R_INTENT, unresolvable(intent))
            frame.setArgument(PRE_R_ACTIVITY_INFO, null)
            frame.setArgument(PRE_R_RESOLVE_INFO, null)

            frame.getArgument(PRE_R_ACTIVITY_INFO) !is ActivityInfo &&
                    isUnresolvable(frame.getArgument(PRE_R_INTENT))
        }.getOrDefault(false)

        if (!deflected) {
            logW(TAG) { "@$methodName: cannot deflect the request, replacing the result" }
            returnValue.result = fakeReturnCode
        }

        svc.increaseALFilterCount(caller)
    }

    private fun unresolvable(intent: Intent) =
        Intent(intent).setPackage(null).setComponent(unresolvableComponent)

    private fun isUnresolvable(intent: Any?) =
        (intent as? Intent)?.component == unresolvableComponent

    private fun requestUid(request: Any, fields: RequestFields): Int {
        val callingUid = fields.callingUid.getInt(request)
        if (callingUid >= 0) return callingUid

        val realCallingUid = fields.realCallingUid.getInt(request)
        if (realCallingUid >= 0) return realCallingUid

        return Binder.getCallingUid()
    }

    private fun frameUid(frame: EmulatedStackFrame): Int {
        val callingUid = frame.getArgument(PRE_R_CALLING_UID) as? Int ?: -1
        if (callingUid >= 0) return callingUid

        val realCallingUid = frame.getArgument(PRE_R_REAL_CALLING_UID) as? Int ?: -1
        if (realCallingUid >= 0) return realCallingUid

        return Binder.getCallingUid()
    }

    private fun resolveEntryDiag(
        methodName: String,
        frame: EmulatedStackFrame,
        list: List<ResolveInfo>?,
    ) = runCatching {
        "@$methodName: candidates=${list?.size} " +
                "filterCallingUid=${frame.getArgument(APRF_FILTER_CALLING_UID)} " +
                "resolveForStart=${frame.getArgument(APRF_RESOLVE_FOR_START)} " +
                "userId=${frame.getArgument(APRF_USER_ID)} " +
                "binder=${Binder.getCallingUid()} " +
                "component=${(frame.getArgument(APRF_INTENT) as? Intent)?.component}"
    }.getOrElse {
        "@$methodName: unexpected signature, ${frame.type().parameterCount()} args"
    }
}
