package com.iodvd.fuqp.zygote.hook

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemProperties
import androidx.annotation.RequiresApi
import com.iodvd.fuqp.common.BuildConfig
import com.iodvd.fuqp.zygote.service.BulkHooker
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.service.SystemServerHook
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logE
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.ServiceUtils.getCallingApps
import com.iodvd.fuqp.zygote.util.ZLUtils.args
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZLUtils.getBooleanField
import com.iodvd.fuqp.zygote.util.ZLUtils.getIntField
import com.iodvd.fuqp.zygote.util.ZLUtils.getObjectField
import com.iodvd.fuqp.zygote.util.ZLUtils.setBooleanField
import com.iodvd.fuqp.zygote.util.ZLUtils.thisObject
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PROCESS_LIST_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PROCESS_RECORD_INTERNAL_CLASS
import com.iodvd.fuqp.zygote.util.ZygoteConstants.STORAGE_MANAGER_SERVICE_CLASS
import java.util.Map

@SuppressLint("PrivateApi")
@RequiresApi(Build.VERSION_CODES.R)
class AppDataIsolationHook : IFrameworkHook {
    override val TAG = "AppDataIsolationHook"

    companion object {
        private const val APPDATA_ISOLATION_ENABLED = "mAppDataIsolationEnabled"
        private const val VOLD_APPDATA_ISOLATION_ENABLED = "mVoldAppDataIsolationEnabled"
        private const val FUSE_PROP = "persist.sys.fuse"
    }

    private var voldHookSkipped = false

    private val processRecordIntClass: Class<*> by lazy {
        Class.forName(
            PROCESS_RECORD_INTERNAL_CLASS,
            true,
            SystemServerHook.classLoader,
        )
    }

    private val isAltIsolationEnabled get() = config?.let {
        it.altAppDataIsolation || it.altVoldAppDataIsolation
    } ?: false

    private val config get() = service?.config

    @SuppressLint("PrivateApi")
    override fun load() {
        if (!isAltIsolationEnabled) return
        logI(TAG) { "Load hook" }

        BulkHooker.instance.apply {
            hookBefore(
                PROCESS_LIST_CLASS,
                "startProcess",
            ) { _, frame, _ ->
                val processListClazz = runCatching {
                    Class.forName(PROCESS_LIST_CLASS, true, SystemServerHook.classLoader)
                }.getOrNull()

                if (config?.altAppDataIsolation ?: false) {
                    val isEnabled = getBooleanField(
                        frame.thisObject,
                        APPDATA_ISOLATION_ENABLED,
                        processListClazz,
                    )

                    if (!isEnabled) {
                        setBooleanField(
                            frame.thisObject,
                            APPDATA_ISOLATION_ENABLED,
                            true,
                            processListClazz,
                        )

                        logI(TAG) { "ProcessList - App data isolation is forced" }
                    }
                }

                if (config?.altVoldAppDataIsolation ?: false && !voldHookSkipped) {
                    val fuseEnabled = SystemProperties.getBoolean(FUSE_PROP, false)

                    if (!fuseEnabled) {
                        voldHookSkipped = true
                        logE(TAG) { "ProcessList - FUSE storage is not enabled, skip vold hook" }
                    } else {
                        val isolationEnabled = getBooleanField(
                            frame.thisObject,
                            VOLD_APPDATA_ISOLATION_ENABLED,
                            processListClazz,
                        )

                        if (!isolationEnabled) {
                            setBooleanField(
                                frame.thisObject,
                                VOLD_APPDATA_ISOLATION_ENABLED,
                                true,
                                processListClazz,
                            )

                            logI(TAG) { "ProcessList - Vold app data isolation is forced" }
                        }
                    }
                }
            }

            hookAfter(
                PROCESS_LIST_CLASS,
                "needsStorageDataIsolation",
            ) { _, frame, returnValue ->
                if (config?.altVoldAppDataIsolation ?: false) {
                    val app = frame.args.find { it?.javaClass?.simpleName == "ProcessRecord" }!!
                    val uid = runCatching {
                        getIntField(app, "uid")
                    }.getOrElse {
                        getIntField(app, "uid", processRecordIntClass)
                    }

                    val apps = getCallingApps(uid)

                    if (config?.detailLog ?: false) {
                        val processName = runCatching {
                            getObjectField(app, "processName")
                        }.getOrElse {
                            getObjectField(app, "processName", processRecordIntClass)
                        }
                        val mountNode = runCatching {
                            getIntField(app, "mMountMode")
                        }.getOrDefault(0)
                        val isolated = runCatching {
                            getBooleanField(app, "isolated")
                        }.getOrElse {
                            getBooleanField(app, "isolated", processRecordIntClass)
                        }
                        val appZygote = runCatching {
                            getBooleanField(app, "appZygote")
                        }.getOrElse {
                            getBooleanField(app, "appZygote", processRecordIntClass)
                        }

                        logD(TAG) { "@needsStorageDataIsolation $uid and ${apps.contentToString()} - $processName value without override: ${returnValue.result}, mount node: $mountNode, isolated: $isolated, appZygote: $appZygote" }
                    }

                    // Do not isolate this module for safety
                    if (apps.contains(BuildConfig.APP_PACKAGE_NAME)) {
                        returnValue.result = false
                        return@hookAfter
                    }

                    if (apps.any { service?.isAppDataIsolationExcluded(it) ?: false }) {
                        returnValue.result = false
                        return@hookAfter
                    }

                    if (config?.skipSystemAppDataIsolation ?: false) {
                        val isSystemApp = service?.systemApps?.any { apps.contains(it) } ?: false
                        logD(TAG) { "@needsStorageDataIsolation $uid and ${apps.contentToString()} - isSystemApp: $isSystemApp" }

                        if (isSystemApp) {
                            returnValue.result = false
                            return@hookAfter
                        }
                    }
                }
            }

            hookBefore(
                STORAGE_MANAGER_SERVICE_CLASS,
                "onVolumeStateChangedLocked",
            ) { _, frame, _ ->
                if (config?.altVoldAppDataIsolation ?: false && !voldHookSkipped) {
                    val fuseEnabled = SystemProperties.getBoolean(FUSE_PROP, false)

                    if (!fuseEnabled) {
                        logE(TAG) { "StorageManagerService - FUSE storage is not enabled, skip vold hook" }
                        voldHookSkipped = true
                        return@hookBefore
                    }

                    val isolationEnabled = getBooleanField(
                        frame.thisObject,
                        VOLD_APPDATA_ISOLATION_ENABLED,
                    )

                    if (!isolationEnabled) {
                        setBooleanField(
                            frame.thisObject,
                            VOLD_APPDATA_ISOLATION_ENABLED,
                            true,
                        )

                        logI(TAG) { "StorageManagerService - Vold app data isolation is forced" }
                    }
                }
            }

            hookBefore(
                STORAGE_MANAGER_SERVICE_CLASS,
                "remountAppStorageDirs",
            ) { _, frame, _ ->
                if (!voldHookSkipped && config?.altVoldAppDataIsolation ?: false && config?.skipSystemAppDataIsolation ?: false) {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    val pidPkgMap = frame.getArgument(1) as Map<*, *>
                    val keysToRemove = mutableSetOf<Any>()

                    for (entry in pidPkgMap.entrySet()) {
                        val pid = entry.key
                        val packageName = entry.value as String

                        if (packageName in service!!.systemApps || packageName == BuildConfig.APP_PACKAGE_NAME) {
                            logD(TAG) { "@remountAppStorageDirs SYSTEM $pid - $packageName is marked to remove" }
                            keysToRemove += pid
                            break
                        }
                    }

                    keysToRemove.forEach { pidPkgMap.remove(it) }
                }
            }
        }
    }
}
