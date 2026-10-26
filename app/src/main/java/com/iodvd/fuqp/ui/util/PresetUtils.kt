package com.iodvd.fuqp.ui.util

import com.iodvd.fuqp.common.app_presets.AccessibilityAppsPreset
import com.iodvd.fuqp.common.app_presets.CustomROMPreset
import com.iodvd.fuqp.common.app_presets.DetectorAppsPreset
import com.iodvd.fuqp.common.app_presets.RootAppsPreset
import com.iodvd.fuqp.common.app_presets.SDhizukuAppsPreset
import com.iodvd.fuqp.common.app_presets.SuspiciousAppsPreset
import com.iodvd.fuqp.common.app_presets.XposedModulesPreset
import com.iodvd.fuqp.common.settings_presets.AccessibilityPreset
import com.iodvd.fuqp.common.settings_presets.DeveloperOptionsPreset
import com.iodvd.fuqp.common.settings_presets.InputMethodPreset
import com.iodvd.fuqp.R

object PresetUtils {
    val presetMap = mapOf(
        AccessibilityAppsPreset.NAME to R.string.preset_accessibility_apps,
        CustomROMPreset.NAME to R.string.preset_custom_rom,
        DetectorAppsPreset.NAME to R.string.preset_detector_apps,
        RootAppsPreset.NAME to R.string.preset_root_apps,
        SDhizukuAppsPreset.NAME to R.string.preset_shizuku_dhizuku,
        SuspiciousAppsPreset.NAME to R.string.preset_sus_apps,
        XposedModulesPreset.NAME to R.string.preset_xposed,
    )

    val settingsPresetMap = mapOf(
        AccessibilityPreset.NAME to R.string.settings_preset_accessibility,
        DeveloperOptionsPreset.NAME to R.string.settings_preset_dev_options,
        InputMethodPreset.NAME to R.string.settings_preset_input_method,
    )
}
