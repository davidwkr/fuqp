package com.iodvd.fuqp.zygote.hook

import android.content.Intent
import android.content.pm.IPackageManager
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Process
import com.iodvd.fuqp.common.Constants
import com.iodvd.fuqp.common.Utils.binderLocalScope
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
     * Reverse-lookup decoy. App-hiding makes a hidden package invisible across every
     * PackageManager surface, including `getPackagesForUid`/`getNameForUid`. A caller that
     * enumerates process UIDs out of band (e.g. with `getpriority`) and then asks who owns each
     * UID therefore sees "this UID has a live process but resolves to no package" — a mismatch
     * that reveals hiding is active (see docs/UID_PROCESS_LEAK.md).
     *
     * For a caller that opts in (`decoyHiddenUidOwners`), a UID whose only owner is hidden *from
     * that caller* resolves to a stable, plausible decoy package instead of nothing, so there is
     * no mismatch. Reverse and forward lookups (uid→name, name→uid, getPackageUid) are kept
     * consistent for the same caller so a one-round-trip cross-check does not catch the decoy;
     * the on-disk APK/datadir is still real, so a detector that opens them can still tell.
     *
     * Off by default; gated per-caller O(1) by [FUQPService.isDecoyCallerAppId] so it costs
     * nothing for callers that have not opted in, even when some other app has.
     */
    protected fun BulkHooker.hookUidDecoy() {
        val intType = Int::class.javaPrimitiveType!!
        val intArrayType = IntArray::class.java
        val stringType = String::class.java
        val longType = Long::class.javaPrimitiveType!!

        // getPackagesForUid(int) -> String[]
        hookAfter(COMPUTER_ENGINE_CLASS, "getPackagesForUid", 1, listOf(intType)) { methodName, frame, returnValue ->
            if (returnValue.throwable != null) return@hookAfter
            val existing = returnValue.result as? Array<*>
            if (existing != null && existing.isNotEmpty()) return@hookAfter
            val uid = frame.getArgument(1) as? Int ?: return@hookAfter
            withDecoyGuard {
                val decoy = resolveDecoyForQuery(uid) ?: return@withDecoyGuard
                returnValue.result = arrayOf(decoy)
                logD(TAG) { "@$methodName decoy for uid $uid -> $decoy" }
            }
        }

        // getNameForUid(int) -> String
        hookAfter(COMPUTER_ENGINE_CLASS, "getNameForUid", 1, listOf(intType)) { methodName, frame, returnValue ->
            if (returnValue.throwable != null) return@hookAfter
            val existing = returnValue.result as? String
            if (!existing.isNullOrEmpty()) return@hookAfter
            val uid = frame.getArgument(1) as? Int ?: return@hookAfter
            withDecoyGuard {
                val decoy = resolveDecoyForQuery(uid) ?: return@withDecoyGuard
                returnValue.result = decoy
                logD(TAG) { "@$methodName decoy for uid $uid -> $decoy" }
            }
        }

        // getNamesForUids(int[]) -> String[]: array of nullable names, one per queried uid.
        // Fill in decoy slots that would otherwise be null.
        hookAfter(COMPUTER_ENGINE_CLASS, "getNamesForUids", 1, listOf(intArrayType)) { methodName, frame, returnValue ->
            if (returnValue.throwable != null) return@hookAfter
            val uids = frame.getArgument(1) as? IntArray ?: return@hookAfter
            @Suppress("UNCHECKED_CAST")
            val names = (returnValue.result as? Array<String?>)?.copyOf() ?: return@hookAfter
            if (names.size != uids.size) return@hookAfter
            withDecoyGuard {
                var changed = false
                for (i in uids.indices) {
                    if (!names[i].isNullOrEmpty()) continue
                    val decoy = resolveDecoyForQuery(uids[i]) ?: continue
                    names[i] = decoy
                    changed = true
                }
                if (changed) {
                    returnValue.result = names
                    logD(TAG) { "@$methodName decoyed ${uids.size} uid(s)" }
                }
            }
        }

        // getPackageUid(String, long, int) -> int: forward direction. If the queried name is a
        // decoy this caller has already received, return the hidden app's uid so the caller sees
        // name <-> uid consistency. Without this, the detector's "I saw uid X -> name N, now
        // give me the uid of N" cross-check fails with NameNotFound and is a stronger signal.
        hookAfter(
            COMPUTER_ENGINE_CLASS, "getPackageUid", 3,
            listOf(stringType, longType, intType),
        ) { methodName, frame, returnValue ->
            if (returnValue.throwable != null) return@hookAfter
            val existing = returnValue.result as? Int ?: return@hookAfter
            if (existing >= 0) return@hookAfter   // the name resolved for real; don't touch
            val name = frame.getArgument(1) as? String ?: return@hookAfter
            withDecoyGuard {
                val callingUid = Binder.getCallingUid()
                if (callingUid == Constants.UID_SYSTEM) return@withDecoyGuard
                if (!service.isDecoyCallerAppId((callingUid % 100000))) return@withDecoyGuard
                val hiddenUid = service.hiddenUidForDecoy(name) ?: return@withDecoyGuard
                returnValue.result = hiddenUid
                logD(TAG) { "@$methodName decoy forward $name -> $hiddenUid" }
            }
        }
    }

    /**
     * Per-thread reentrancy guard. The decoy helpers call back into PMS via `binderLocalScope`,
     * which routes through the very hooks they live in. The UID-is-SYSTEM check short-circuits
     * the nested call under normal conditions, but relying on that alone is fragile (review C2):
     * if someone reorders the guards, or `binderLocalScope` ever changes the identity semantics,
     * the result is unbounded recursion on a binder thread. This makes the guarantee explicit.
     */
    private val decoyReentry = ThreadLocal.withInitial { false }

    private inline fun withDecoyGuard(block: () -> Unit) {
        if (decoyReentry.get() == true) return
        decoyReentry.set(true)
        try {
            runCatching { block() }.onFailure {
                logW(TAG) { "decoy hook threw, suppressing: ${it.message}" }
            }
        } finally {
            decoyReentry.set(false)
        }
    }

    /**
     * The decoy name to hand this caller for [queriedUid], or null to leave the real (empty)
     * result alone. Returns a decoy only when:
     *  - the caller opted in (fast O(1) check by caller app-id, no PM IPC on the miss path),
     *  - [queriedUid] is a plain application uid (not isolated / sandbox / shared-GID / system),
     *  - the uid is a single-package uid (shared-user uids use a different name format, so
     *    returning a bare package name would itself be a signal — H2),
     *  - the one owner is actually hidden *from this caller*, evaluated in the uid's user.
     */
    private fun resolveDecoyForQuery(queriedUid: Int): String? {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Constants.UID_SYSTEM) return null

        // Fast opt-in check: no PM IPC unless the caller actually has decoy on (review M1).
        if (!service.isDecoyCallerAppId((callingUid % 100000))) return null

        // Only ordinary application uids (review H3). Isolated/sandbox/shared-GID uids never
        // belong to a top-level consumer app so decoying them is itself a tell.
        val appId = queriedUid % 100000
        if (appId !in Process.FIRST_APPLICATION_UID..Process.LAST_APPLICATION_UID) return null

        val caller = getCallingApps(pms, callingUid).firstOrNull { service.isDecoyCaller(it) }
            ?: return null

        val realOwners = binderLocalScope { pms.getPackagesForUid(queriedUid) } ?: return null
        if (realOwners.isEmpty()) return null
        // Shared-user uids: more than one owner, and the real name format for them is
        // "sharedName:uid", not a bare package. Skip rather than invent an incompatible shape (H2).
        if (realOwners.size > 1) return null

        // Evaluate hiding in the uid's user, not the caller's (H3): a caller sweeping cross-user
        // uids would otherwise get a decision based on the wrong user's state.
        val queriedUserId = getUserFromCallingUid(queriedUid)
        val hiddenFromCaller = realOwners.any { service.shouldHide(caller, it, queriedUserId) }
        if (!hiddenFromCaller) return null

        return service.decoyPackageForUid(queriedUid)
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
