package com.iodvd.fuqp.common.settings_presets

import android.provider.Settings
import com.iodvd.fuqp.common.Constants

class AccessibilityPreset : BasePreset(NAME) {
    companion object {
        const val NAME = "accessibility"
    }

    override val settingsKVPairs = listOf(
        ReplacementItem(
            name = Settings.Secure.ACCESSIBILITY_ENABLED,
            value = "0",
            Constants.SETTINGS_SECURE,
        ),
        ReplacementItem(
            name = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            value = "",
            Constants.SETTINGS_SECURE,
        ),
    )
}
