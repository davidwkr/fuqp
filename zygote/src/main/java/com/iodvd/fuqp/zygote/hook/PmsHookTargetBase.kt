package com.iodvd.fuqp.zygote.hook

import android.content.Intent
import android.content.pm.IPackageManager
import android.content.pm.ResolveInfo
import com.iodvd.fuqp.common.Constants
import com.iodvd.fuqp.common.Utils.getUserFromCallingUid
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.ReturnValue
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.Logcat.logV
import com.iodvd.fuqp.zygote.util.Logcat.logW
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
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

    @PublishedApi
    internal val lastFilteredApp: AtomicReference<String?> = AtomicReference(null)

    inline fun applyPackageHiding(
        methodName: String,
        returnValue: ReturnValue,
        findCallingUid: () -> Int?,
        findTargetApp: () -> String?,
        findCallingApps: (IPackageManager, Int) -> Array<String>?,
        valueForHiding: Any?,
        /**
         * True where the hooked method answers a lookup for one exact package name, as opposed
         * to producing a listing or serving both. Those sites exempt
         * [Constants.packagesVisibleOnExactName] and open an [ExactNameLookup] window around the
         * original, so the shared visibility gate nested inside the same call exempts it too;
         * every other site keeps filtering, which is what keeps the package out of listings.
         */
        exactNameLookup: Boolean = false,
    ) {
        if (returnValue.throwable != null) return

        val callingUid = findCallingUid()
        if (callingUid == null || callingUid == Constants.UID_SYSTEM) return

        val targetApp = findTargetApp() ?: return

        // Ahead of the uid cache on purpose: a listing hook may already have recorded this
        // package as hidden for this uid, and that must not stop the framework from loading
        // it into the caller's own process.
        if (targetApp in Constants.packagesVisibleOnExactName &&
            (exactNameLookup || ExactNameLookup.isActiveFor(targetApp))
        ) return

        logV(TAG) { "@$methodName incoming query: $callingUid => $targetApp" }
        if (dataHolder.shouldHideFromUid(callingUid, targetApp) == true) {
            returnValue.result = valueForHiding
            service.increasePMFilterCount(callingUid)
            logD(TAG) { "@$methodName caller cache: $callingUid, target: $targetApp" }
            return
        }
        val callingUserId = getUserFromCallingUid(callingUid)
        val callingApps = findCallingApps(pms, callingUid)
        val caller = callingApps?.firstOrNull { service.shouldHide(it, targetApp, callingUserId) }
        if (caller != null) {
            logD(TAG) { "@$methodName caller: $callingUid $caller, target: $targetApp" }
            returnValue.result = valueForHiding
            val last = lastFilteredApp.getAndSet(caller)
            if (last != caller) logI(TAG) { "@$methodName: query from $caller" }
            dataHolder.putShouldHideUidCache(callingUid, caller, targetApp)
            service.increasePMFilterCount(caller)
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
    protected fun BulkHooker.hookIntentQuery() {
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
                getCallingApps(pms, filterCallingUid)
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
                            service.shouldHideActivityLaunch(it, target, callingUserId)
                        }
                    } else {
                        // applyPackageHiding reports through a ReturnValue now; a scratch one
                        // per entry turns its verdict back into a per-entry decision.
                        val verdict = ReturnValue()
                        applyPackageHiding(
                            methodName,
                            verdict,
                            { filterCallingUid },
                            { target },
                            { _, _ -> callingApps },
                            true,
                        )
                        hidden = verdict.replace
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
}
