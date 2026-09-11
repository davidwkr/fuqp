package com.iodvd.fuqp.ui.adapter

import android.os.Bundle
import com.iodvd.fuqp.common.settings_presets.ReplacementItem
import com.iodvd.fuqp.ui.util.targetSettingListToBundle
import com.iodvd.fuqp.ui.util.toTargetSettingList

class SettingsTemplateListAdapter(
    items: Bundle,
    private val onItemClickListener: (SettingsTemplateListAdapter, ReplacementItem) -> Unit
) : BaseSettingsPTAdapter() {
    override val items by lazy {
        val list = mutableListOf<ReplacementItem>()
        list.addAll(items.toTargetSettingList())
        return@lazy list
    }

    fun targetSettingListToBundle() = items.targetSettingListToBundle()

    override fun onItemClick(item: ReplacementItem) = onItemClickListener(this, item)
}
