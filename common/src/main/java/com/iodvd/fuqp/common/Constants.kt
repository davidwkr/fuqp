package com.iodvd.fuqp.common

import com.iodvd.fuqp.common.BuildConfig

object Constants {
    const val PROVIDER_AUTHORITY = "${BuildConfig.APP_PACKAGE_NAME}.ServiceProvider"
    const val GMS_PACKAGE_NAME = "com.google.android.gms"
    const val GSF_PACKAGE_NAME = "com.google.android.gsf"
    const val VENDING_PACKAGE_NAME = "com.android.vending"
    const val TRANSLATE_URL = "https://crowdin.com/project/frknkrc44-hma-oss"

    const val UID_SYSTEM = 1000

    val gmsPackages = arrayOf(GMS_PACKAGE_NAME, GSF_PACKAGE_NAME)
    val riskyPackages = arrayOf(VENDING_PACKAGE_NAME) + gmsPackages

    const val SETTINGS_GLOBAL = "global"
    const val SETTINGS_SYSTEM = "system"
    const val SETTINGS_SECURE = "secure"

    const val FAKE_INSTALLATION_SOURCE_DISABLED = 0
    const val FAKE_INSTALLATION_SOURCE_USER = 1
    const val FAKE_INSTALLATION_SOURCE_SYSTEM = 2

    const val ENABLE_INTERNET_UNKNOWN = 0
    const val ENABLE_INTERNET_OFF = 1
    const val ENABLE_INTERNET_ON = 2

    const val MANAGER_WORK_MODE_UNKNOWN = 0
    const val MANAGER_WORK_MODE_OK = 1
    const val MANAGER_WORK_MODE_NO_HOOKS = 2
    const val MANAGER_WORK_MODE_LOADING = 3

    const val PARCEL_TYPE_LOG = 0
    const val PARCEL_TYPE_CONFIG = 1

    const val CONFIG_VERSION_NO_SETTINGS = -100

    /**
     * Defines the GID for the group that allows write access to the internal media storage.
     */
    const val SDCARD_RW_GID: Int = 1015

    /**
     * Defines the GID for the group that allows write access to the internal media storage.
     */
    const val MEDIA_RW_GID: Int = 1023

    /**
     * Access to installed package details
     */
    const val PACKAGE_INFO_GID: Int = 1032

    /**
     * GID that gives access to USB OTG (unreliable) volumes on /mnt/media_rw/<vol name>
    </vol> */
    const val EXTERNAL_STORAGE_GID: Int = 1077

    /**
     * GID that gives write access to app-private data directories on external
     * storage (used on devices without sdcardfs only).
     */
    const val EXT_DATA_RW_GID: Int = 1078

    /**
     * GID that gives write access to app-private OBB directories on external
     * storage (used on devices without sdcardfs only).
     */
    const val EXT_OBB_RW_GID: Int = 1079

    /**
     * GID that corresponds to the INTERNET permission.
     * Must match the value of AID_INET.
     */
    const val INET_GID: Int = 3003

    /**
     * Defines the gid shared by all applications running under the same profile.
     */
    const val SHARED_USER_GID: Int = 9997

    val GID_PAIRS = mapOf(
        "SDCARD_RW_GID" to SDCARD_RW_GID,
        "MEDIA_RW_GID" to MEDIA_RW_GID,
        "PACKAGE_INFO_GID" to PACKAGE_INFO_GID,
        "EXTERNAL_STORAGE_GID" to EXTERNAL_STORAGE_GID,
        "EXT_DATA_RW_GID" to EXT_DATA_RW_GID,
        "EXT_OBB_RW_GID" to EXT_OBB_RW_GID,
        "INET_GID" to INET_GID,
        "SHARED_USER_GID" to SHARED_USER_GID,
    )

    /**
     * Packages the framework loads into a calling app's own process while that process is
     * still starting up, by resolving an exact package name.
     *
     * GrapheneOS-derived ROMs (GrapheneOS, VoltageOS, ...) patch `GmsCompat.maybeEnable()`
     * into `Instrumentation.newApplication()`. It calls `GmsCompatLib.init()`, which does
     * `createPackageContext("app.grapheneos.gmscompat.lib")`. That runs before any app code,
     * under the app's own uid, so a scoped app cannot be distinguished from the framework
     * loading the shim on its behalf - they are the same query from the same caller. Hiding
     * the shim therefore kills every GMS-using app with
     * `NameNotFoundException` -> `IllegalStateException` before it can start.
     *
     * These stay resolvable when asked for by exact name, but are still stripped from package
     * listings, so enumerating installed packages does not reveal them. That defeats discovery
     * by listing; it cannot defeat a probe that already hardcodes the name, because such a
     * probe is indistinguishable from the load.
     *
     * Use [packagesShouldNotHide] instead for packages that must never be hidden at all.
     */
    val packagesVisibleOnExactName = setOf(
        "app.grapheneos.gmscompat.lib",
    )

    val packagesShouldNotHide = setOf(
        "android",
        "android.media",
        "android.uid.system",
        "android.uid.shell",
        "android.uid.systemui",
        "com.android.permissioncontroller",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.providers.settings",
        "com.google.android.providers.media.module"
    )
}
