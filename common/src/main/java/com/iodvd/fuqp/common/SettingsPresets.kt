package com.iodvd.fuqp.common

import com.iodvd.fuqp.common.settings_presets.AccessibilityPreset
import com.iodvd.fuqp.common.settings_presets.BasePreset
import com.iodvd.fuqp.common.settings_presets.DeveloperOptionsPreset
import com.iodvd.fuqp.common.settings_presets.InputMethodPreset

class SettingsPresets private constructor() {
    private val presetList = mutableListOf<BasePreset>()

    companion object {
        val instance by lazy { SettingsPresets() }
    }

    val presetNames by lazy { presetList.map { it.name }.toTypedArray() }
    fun getPresetByName(name: String) = presetList.firstOrNull { it.name == name }

    init {
        presetList.add(DeveloperOptionsPreset())
        presetList.add(AccessibilityPreset())
        presetList.add(InputMethodPreset())
    }
}
