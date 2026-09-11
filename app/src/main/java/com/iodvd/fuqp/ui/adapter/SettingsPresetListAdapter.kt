package com.iodvd.fuqp.ui.adapter

import com.iodvd.fuqp.common.SettingsPresets
import com.iodvd.fuqp.common.settings_presets.ReplacementItem

class SettingsPresetListAdapter(name: String) : BaseSettingsPTAdapter() {
    override val items by lazy {
        SettingsPresets.instance.getPresetByName(name)!!.settingsKVPairs.sortedBy { it.name }
    }

    override fun onItemClick(item: ReplacementItem) {
        // do nothing
    }
}
