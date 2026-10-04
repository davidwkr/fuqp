package com.iodvd.fuqp.zygote.hook

import com.iodvd.fuqp.common.Constants

/**
 * Marks the window during which PMS is resolving one exact package name on this thread.
 *
 * Some PMS entry points answer a lookup for a single named package; others build a listing.
 * Both funnel through the same visibility gate - `shouldFilterApplication` on API 30+,
 * `filterAppAccessLPr` on 29 - which cannot tell which of the two it is serving. Exempting a
 * package there unconditionally would let it escape `getInstalledPackages` and
 * `getPackagesForUid`, so the by-name entry points open this window instead and the shared gate
 * honours [Constants.packagesVisibleOnExactName] only while it is open.
 *
 * Opened from the `before` step of `BulkHooker.hookAround` and closed in its `after` step, which
 * runs even when the original throws. That pairing matters: binder threads are pooled, so a
 * permit that outlived its call would be read by an unrelated later query on the same thread.
 *
 * Only one method per API level may open a window. An opener reachable from inside another
 * opener would close the outer window on its way out, and the outer call's own gate check -
 * which comes after - would then filter the package after all.
 */
@PublishedApi
internal object ExactNameLookup {

    private val resolving = ThreadLocal<String?>()

    /** True while this thread is inside an exact-name resolution for [packageName]. */
    fun isActiveFor(packageName: String?) =
        packageName != null && resolving.get() == packageName

    /** Opens the window for [packageName] if it is one that must stay resolvable by name. */
    fun beginIfVisibleOnExactName(packageName: String?) {
        if (packageName != null && packageName in Constants.packagesVisibleOnExactName) {
            resolving.set(packageName)
        }
    }

    fun end() = resolving.remove()
}
