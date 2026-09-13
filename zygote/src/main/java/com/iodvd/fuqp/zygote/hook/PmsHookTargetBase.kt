package com.iodvd.fuqp.zygote.hook

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Build
import android.util.ArrayMap
import com.iodvd.fuqp.common.CollectionUtils.firstOrNullWithType
import com.iodvd.fuqp.common.CollectionUtils.lastWithType
import com.iodvd.fuqp.common.Constants
import com.iodvd.fuqp.common.Constants.VENDING_PACKAGE_NAME
import com.iodvd.fuqp.common.OSUtils
import com.iodvd.fuqp.common.Utils.getPackageInfoCompat
import com.iodvd.fuqp.common.Utils.getUserFromCallingUid
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.service.FUQPServiceCache
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.Logcat.logV
import com.iodvd.fuqp.zygote.util.Logcat.logW
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import com.iodvd.fuqp.zygote.util.ZLUtils.args
import com.iodvd.fuqp.zygote.util.ZLUtils.callMethod
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZygoteConstants.COMPUTER_ENGINE_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PMS_COMPUTER_ENGINE_CLASS
import java.util.concurrent.atomic.AtomicReference

abstract class PmsHookTargetBase : IFrameworkHook {

    private companion object {
        const val INTENT_QUERY_METHOD = "queryIntentActivitiesInternal"
    }

    /**
     * One known parameter list of the widest `queryIntentActivitiesInternal`
     * overload - the one every shorter overload delegates to - together with
     * where `filterCallingUid` and `resolveForStart` sit in it. Both indices
     * count the receiver, which is argument 0.
     */
    private class IntentQueryLayout(
        val types: List<Class<*>>,
        val uidIndex: Int,
        val forStartIndex: Int,
    )

    /**
     * The layouts this hook knows how to read, and only those.
     *
     * Neither the arity nor the position of `filterCallingUid` is stable across
     * releases, and guessing either is how this hook first shipped bound to
     * nothing at all: arity 9 was assumed, the device declares 8, and the lookup
     * returns null in that case without logging, so the hook was silently absent
     * rather than visibly broken.
     *
     * Inferring the positions from the shape - "the first int is the uid" -
     * fails differently and worse: it accepts a layout nobody has read and then
     * reads arguments out of it by a rule that may not hold, so the hook binds,
     * reports itself bound, and filters against a number that is not a uid.
     * An unrecognised layout is refused instead, out loud.
     */
    private val intentQueryLayouts by lazy {
        val i = Int::class.javaPrimitiveType!!
        val j = Long::class.javaPrimitiveType!!
        val z = Boolean::class.javaPrimitiveType!!
        val intent = Intent::class.java
        val string = String::class.java

        listOf(
            // Read off the device's own services.jar on Android 17 / API 37,
            // and confirmed against the bytecode of the shorter overloads: both
            // pass Binder.getCallingUid() into declared parameter 4 and -1 into
            // parameter 5. privateResolveFlags has been folded into the high
            // bits of `flags` here, and a callingPid added.
            //
            //   (Intent, String, long flags, int filterCallingUid,
            //    int callingPid, int userId,
            //    boolean resolveForStart, boolean allowDynamicSplits)
            IntentQueryLayout(listOf(intent, string, j, i, i, i, z, z), 4, 7),

            // The layout this hook originally targeted, where privateResolveFlags
            // is still a parameter of its own.
            //
            //   (Intent, String, long flags, long privateResolveFlags,
            //    int filterCallingUid, int callingPid, int userId,
            //    boolean resolveForStart, boolean allowDynamicSplits)
            IntentQueryLayout(listOf(intent, string, j, j, i, i, i, z, z), 5, 8),
        )
    }

    private val androidPkgClazzNames = arrayOf("AndroidPackage", "PackageImpl")

    protected var lastFilteredApp: AtomicReference<String?> = AtomicReference(null)

    protected val psPackageInfo by lazy {
        val pms = service?.pms ?: return@lazy null
        try {
            pms.getPackageInfoCompat(
                VENDING_PACKAGE_NAME,
                PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                0
            )
        } catch (_: Throwable) {
            null
        }
    }

    abstract val fakeSystemPackageInstallSourceInfo: Any?
    abstract val fakeUserPackageInstallSourceInfo: Any?

    override fun load() {
        BulkHooker.instance.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hookAfter(
                    COMPUTER_ENGINE_CLASS,
                    "getPackageStates",
                ) { _, _, returnValue ->
                    val callingUid = Binder.getCallingUid()
                    if (callingUid == Constants.UID_SYSTEM) return@hookAfter

                    val callingUserId = getUserFromCallingUid(callingUid)

                    val callingApps = getCallingApps(callingUid)
                    val caller = callingApps.firstOrNull { service?.isHookEnabled(it) ?: false }
                    if (caller != null) {
                        logD(TAG) { "@getPackageStates: incoming query from $caller" }

                        val result = returnValue.result as ArrayMap<*, *>
                        val markedToRemove = mutableListOf<Any>()

                        for (pair in result.entries) {
                            val packageSettings = pair.value
                            val packageName = getPackageNameFromPackageSettings(packageSettings)
                            if (service?.shouldHide(caller, packageName, callingUserId) ?: false) {
                                markedToRemove.add(pair.key)
                            }
                        }

                        if (markedToRemove.isNotEmpty()) {
                            val copyResult = ArrayMap(result)
                            copyResult.removeAll(markedToRemove)
                            logD(TAG) { "@getPackageStates: removed ${markedToRemove.size} entries from $caller" }
                            returnValue.result = copyResult
                            service?.increasePMFilterCount(caller)
                        }
                    }
                }

                // Samsung related fix
                if (OSUtils.isSamsung()) {
                    hookBefore(
                        COMPUTER_ENGINE_CLASS,
                        "generatePackageInfo",
                    ) { methodName, frame, returnValue ->
                        applyPackageHiding(
                            methodName,
                            { Binder.getCallingUid() },
                            { getPackageNameFromPackageSettings(frame.getArgument(1)) },
                            ::getCallingApps,
                            { returnValue.result = null },
                        )
                    }
                }

                // Samsung devices can fail to get this hook working,
                // but it is okay due to generatePackageInfo hook
                hookBefore(
                    COMPUTER_ENGINE_CLASS,
                    "addPackageHoldingPermissions",
                ) { methodName, frame, returnValue ->
                    applyPackageHiding(
                        methodName,
                        { Binder.getCallingUid() },
                        { getPackageNameFromPackageSettings(frame.getArgument(2)) },
                        ::getCallingApps,
                        { returnValue.result = null },
                    )
                }

                hookBefore(
                    COMPUTER_ENGINE_CLASS,
                    "getPackageInfoInternal",
                ) { methodName, frame, returnValue ->
                    applyPackageHiding(
                        methodName,
                        { frame.args.firstOrNullWithType() },
                        { frame.args.firstOrNullWithType() },
                        ::getCallingApps,
                        { returnValue.result = null },
                    )
                }

                hookBefore(
                    COMPUTER_ENGINE_CLASS,
                    "getApplicationInfoInternal",
                ) { methodName, frame, returnValue ->
                    applyPackageHiding(
                        methodName,
                        { frame.args.firstOrNullWithType() },
                        { frame.args.firstOrNullWithType() },
                        ::getCallingApps,
                        { returnValue.result = null },
                    )
                }

                hookBefore(
                    COMPUTER_ENGINE_CLASS,
                    "isCallerInstallerOfRecord",
                ) { methodName, frame, returnValue ->
                    val callingUid = frame.args.lastWithType<Int>()

                    applyInstallerHiding(
                        methodName,
                        { callingUid },
                        fta@{
                            val pkg = frame.args.lastOrNull {
                                it?.javaClass?.simpleName in androidPkgClazzNames
                            } ?: return@fta null
                            callMethod(pkg,
                                if (pkg.javaClass.simpleName == "PackageImpl") {
                                    "getManifestPackageName"
                                } else {
                                    "getPackageName"
                                }
                            ) as? String
                        }
                    ) {
                        when (it) {
                            Constants.FAKE_INSTALLATION_SOURCE_USER -> returnValue.result = callingUid == psPackageInfo?.applicationInfo?.uid
                            Constants.FAKE_INSTALLATION_SOURCE_SYSTEM -> returnValue.result = false
                        }
                    }
                }

                hookBefore(
                    COMPUTER_ENGINE_CLASS,
                    "getInstallerPackageName",
                ) { methodName, frame, returnValue ->
                    applyInstallerHiding(
                        methodName,
                        { frame.args.firstOrNullWithType() ?: Binder.getCallingUid() },
                        { frame.args.firstOrNullWithType() },
                    ) {
                        when (it) {
                            Constants.FAKE_INSTALLATION_SOURCE_USER -> returnValue.result = VENDING_PACKAGE_NAME
                            Constants.FAKE_INSTALLATION_SOURCE_SYSTEM -> returnValue.result = null
                        }
                    }
                }
            } else {
                hookBefore(
                    service!!.pms.javaClass.name,
                    "getInstallerPackageName",
                ) { methodName, frame, returnValue ->
                    applyInstallerHiding(
                        methodName,
                        { Binder.getCallingUid() },
                        { frame.getArgument(1) as? String },
                    ) {
                        when (it) {
                            Constants.FAKE_INSTALLATION_SOURCE_USER -> returnValue.result = VENDING_PACKAGE_NAME
                            Constants.FAKE_INSTALLATION_SOURCE_SYSTEM -> returnValue.result = null
                        }
                    }
                }
            }

            if (service?.pmn != null) {
                hookBefore(
                    service!!.pmn!!.javaClass.name,
                    "getInstallerForPackage",
                ) { methodName, frame, returnValue ->
                    applyInstallerHiding(
                        methodName,
                        { Binder.getCallingUid() },
                        { frame.getArgument(1) as? String },
                    ) {
                        when (it) {
                            Constants.FAKE_INSTALLATION_SOURCE_USER -> returnValue.result = VENDING_PACKAGE_NAME
                            Constants.FAKE_INSTALLATION_SOURCE_SYSTEM -> returnValue.result = "preload"
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hookBefore(
                    service!!.pms.javaClass.name,
                    "getInstallSourceInfo",
                ) { methodName, frame, returnValue ->
                    applyInstallerHiding(
                        methodName,
                        { Binder.getCallingUid() },
                        { frame.getArgument(1) as? String }
                    ) {
                        when (it) {
                            Constants.FAKE_INSTALLATION_SOURCE_USER -> returnValue.result = fakeUserPackageInstallSourceInfo
                            Constants.FAKE_INSTALLATION_SOURCE_SYSTEM -> returnValue.result = fakeSystemPackageInstallSourceInfo
                        }
                    }
                }
            }

            // hookAfter only reads the real return value back out of the frame
            // on T+, and ComputerEngine is a T-era class in any case.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hookIntentQuery()
            }
        }
    }

    /**
     * Intent resolution, filtered on the way out.
     *
     * shouldFilterApplication already covers most read paths, and it is hooked.
     * It does NOT close this one, measured on a device rather than reasoned
     * about: with hiding enabled for a checker, its own report showed the pm,
     * gpi and receiver vectors blocked while intent and intent-profile still
     * named the package.
     *
     *     com.kowx712.supermanager  [gpi,intent,intent-profile,receiver]  hiding off
     *     com.kowx712.supermanager  [intent,intent-profile]               hiding on
     *
     * WHY the existing hook misses it is not established. This hook does not
     * depend on the answer: it filters the returned list using the uid the
     * method is given, which is correct whether the gap is a resolution path
     * that skips AppsFilter or one that consults it under a different identity.
     *
     * queryIntentActivitiesInternal carries that uid explicitly as
     * filterCallingUid, and every shorter overload delegates to this one, so a
     * single hook covers them all. userId is a parameter too, which is what
     * makes the cross-profile case reachable at all.
     */
    private fun BulkHooker.hookIntentQuery() {
        val intentQuery = listOf(COMPUTER_ENGINE_CLASS, PMS_COMPUTER_ENGINE_CLASS)
            .firstNotNullOfOrNull { clazz ->
                findExecutable(clazz, INTENT_QUERY_METHOD) { types ->
                    intentQueryLayouts.any { it.types == types }
                }
            }

        if (intentQuery == null) {
            // Said out loud. A hook that does not bind is a protection that is
            // not running, and the failure that produced this method was
            // precisely that it looked identical to one that was.
            logW(TAG) {
                "$INTENT_QUERY_METHOD: no overload matches a known layout;" +
                        " intent queries are NOT filtered"
            }
            return
        }

        val types = intentQuery.parameterTypes.toList()
        val layout = intentQueryLayouts.first { it.types == types }

        // Bound by exact parameter list, not by arity, so what is hooked is the
        // method whose layout was matched above and whose argument positions the
        // body below reads.
        val installed = hookAfter(
            intentQuery.declaringClass.name,
            intentQuery.name,
            intentQuery.parameterCount,
            types,
        ) { methodName, frame, returnValue ->
            val results = returnValue.result as? List<*> ?: return@hookAfter
            if (results.isEmpty()) return@hookAfter

            val filterCallingUid = frame.getArgument(layout.uidIndex) as? Int
                ?: return@hookAfter

            // Resolving in order to START something is a different question from
            // resolving in order to LOOK, and this method serves both. Hiding a
            // package from a start resolution is what stops it being launched,
            // which is a feature with its own switch - disableActivityLaunchProtection,
            // plus a per-app inversion - so applying ordinary hiding here would
            // quietly override a setting the user had turned off.
            // shouldHideActivityLaunch is that policy; ask it instead.
            val resolveForStart = frame.getArgument(layout.forStartIndex) as? Boolean ?: false

            // The caller does not change between results, so it is resolved once
            // for the whole list - and lazily, because applyPackageHiding answers
            // from its cache without needing it at all on a repeat query.
            val callingApps by lazy(LazyThreadSafetyMode.NONE) {
                getCallingApps(filterCallingUid)
            }
            val callingUserId = getUserFromCallingUid(filterCallingUid)

            // Stays null until something is actually removed. Nothing is removed
            // on the overwhelming majority of queries, and those hand the
            // framework back its own list untouched.
            var kept: ArrayList<Any?>? = null

            for ((index, info) in results.withIndex()) {
                val target = resolveInfoPackageName(info)
                var hidden = false

                if (target != null) {
                    if (resolveForStart) {
                        hidden = callingApps.any {
                            service?.shouldHideActivityLaunch(it, target, callingUserId) == true
                        }
                    } else {
                        applyPackageHiding(
                            methodName,
                            { filterCallingUid },
                            { target },
                            { callingApps },
                            { hidden = true },
                        )
                    }
                }

                if (hidden) {
                    if (kept == null) {
                        kept = ArrayList<Any?>(results.size).apply {
                            addAll(results.subList(0, index))
                        }
                    }
                } else {
                    kept?.add(info)
                }
            }

            kept?.let { returnValue.result = it }
        }

        // Reported from the installation, not from the lookup: a hook can be
        // found and still not be attached - disabledHooks turns individual ones
        // off - and "bound" has to mean bound.
        if (installed) {
            logI(TAG) {
                "$INTENT_QUERY_METHOD bound: ${intentQuery.declaringClass.name}" +
                        "(${types.joinToString(",") { it.simpleName }})" +
                        " uid@${layout.uidIndex} resolveForStart@${layout.forStartIndex}"
            }
        } else {
            logW(TAG) {
                "$INTENT_QUERY_METHOD: $intentQuery not installed;" +
                        " intent queries are NOT filtered"
            }
        }
    }

    /**
     * The package a ResolveInfo names.
     *
     * Activities and activity-aliases are what this method returns; the service
     * and provider fallbacks are only defensive, for entries that arrive without
     * activityInfo. They do NOT extend this hook to service or provider queries -
     * those resolve through different methods and are not hooked here.
     *
     * A cross-profile forwarding entry names its intermediary - the resolver
     * activity that hands the intent to the other profile - and that is the
     * package this returns for it, not the package on the far side. Hiding is
     * therefore never applied to the real target through such an entry: a known
     * gap, and the likely shape of the detector's intent-profile vector.
     */
    private fun resolveInfoPackageName(info: Any?): String? {
        val resolveInfo = info as? ResolveInfo ?: return null

        return resolveInfo.activityInfo?.packageName
            ?: resolveInfo.serviceInfo?.packageName
            ?: resolveInfo.providerInfo?.packageName
    }

    fun applyPackageHiding(
        methodName: String,
        findCallingUid: () -> Int?,
        findTargetApp: () -> String?,
        findCallingApps: (Int) -> Array<String>?,
        applyReturnValue: () -> Unit,
    ) {
        val callingUid = findCallingUid()
        if (callingUid == null || callingUid == Constants.UID_SYSTEM) return
        val targetApp = findTargetApp() ?: return
        logV(TAG) { "@$methodName incoming query: $callingUid => $targetApp" }
        if (FUQPServiceCache.instance.shouldHideFromUid(callingUid, targetApp) == true) {
            applyReturnValue()
            service?.increasePMFilterCount(callingUid)
            logD(TAG) { "@$methodName caller cache: $callingUid, target: $targetApp" }
            return
        }
        val callingUserId = getUserFromCallingUid(callingUid)
        val callingApps = findCallingApps(callingUid)
        val caller = callingApps?.firstOrNull { service?.shouldHide(it, targetApp, callingUserId) ?: false }
        if (caller != null) {
            logD(TAG) { "@$methodName caller: $callingUid $caller, target: $targetApp" }
            applyReturnValue()
            val last = lastFilteredApp.getAndSet(caller)
            if (last != caller) logI(TAG) { "@$methodName: query from $caller" }
            FUQPServiceCache.instance.putShouldHideUidCache(callingUid, caller, targetApp)
            service?.increasePMFilterCount(caller)
        }
    }

    fun applyInstallerHiding(
        methodName: String,
        findCallingUid: () -> Int?,
        findTargetApp: () -> String?,
        applyReturnValue: (Int) -> Unit,
    ) {
        val callingUid = findCallingUid() ?: return
        if (callingUid == Constants.UID_SYSTEM) return

        val callingApps = getCallingApps(callingUid)
        val callingUser = getUserFromCallingUid(callingUid)

        val query = findTargetApp() ?: return

        for (caller in callingApps) {
            val isHide = service?.shouldHideInstallationSource(caller, query, callingUser)
                ?: Constants.FAKE_INSTALLATION_SOURCE_DISABLED
            if (isHide == Constants.FAKE_INSTALLATION_SOURCE_DISABLED) continue

            logD(TAG) { "@$methodName: Applied installer hiding for $caller - $callingUid => $isHide" }

            applyReturnValue(isHide)

            service?.increaseInstallerFilterCount(caller)
            break
        }
    }
}
