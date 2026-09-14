package com.iodvd.fuqp.zygote.hook

/**
 * Marks the window during which PMS is resolving one exact package name on this thread.
 *
 * Some PMS entry points answer a lookup for a single named package; others build a listing.
 * Both funnel through the same visibility gate - `shouldFilterApplication` on API 30+,
 * `filterAppAccessLPr` on 29 - which cannot tell which of the two it is serving. Exempting a
 * package there unconditionally would let it escape `getInstalledPackages` and
 * `getPackagesForUid`, so the by-name entry points open this window instead and the shared gate
 * honours [com.iodvd.fuqp.common.Constants.packagesVisibleOnExactName] only while it is open.
 *
 * Opened by `applyPackageHiding` and closed by `BulkHooker.hookAround`, which guarantees
 * teardown even when the original throws. That pairing matters: binder threads are pooled, so a
 * permit that outlived its call would be read by an unrelated later query on the same thread.
 */
internal object ExactNameLookup {

    private val resolving = ThreadLocal<String?>()

    /** True while this thread is inside an exact-name resolution for [packageName]. */
    fun isActiveFor(packageName: String?) =
        packageName != null && resolving.get() == packageName

    fun begin(packageName: String) = resolving.set(packageName)

    fun end() = resolving.remove()
}
