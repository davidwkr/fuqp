package com.iodvd.fuqp.common.app_presets

import android.content.pm.ApplicationInfo
import com.iodvd.fuqp.common.Utils.checkSplitPackages
import com.iodvd.fuqp.common.BuildConfig

class XposedModulesPreset : BasePreset(NAME) {
    companion object {
        const val NAME = "xposed"
    }

    override val exactPackageNames = setOf(
        BuildConfig.APP_PACKAGE_NAME,
    )

    override fun canBeAddedIntoPreset(appInfo: ApplicationInfo): Boolean {
        return checkSplitPackages(appInfo) { _, zipFile ->
            // Legacy Xposed method
            if (zipFile.getEntry("assets/xposed_init") != null) {
                return@checkSplitPackages true
            }

            // New LSPosed method
            if (zipFile.getEntry("META-INF/xposed/module.prop") != null) {
                return@checkSplitPackages true
            }

            return@checkSplitPackages false
        }
    }
}
