package com.iodvd.fuqp.common

import com.iodvd.fuqp.common.Utils.encoder
import com.iodvd.fuqp.common.settings_presets.ReplacementItem
import kotlinx.serialization.Serializable
import com.iodvd.fuqp.common.BuildConfig

@Serializable
data class JsonConfig(
    var configVersion: Int = BuildConfig.CONFIG_VERSION,

    /**
     * Enable/disable debug (and verbose for debug builds) logging
     */
    var detailLog: Boolean = false,

    /**
     * Enable/disable error-only logging
     *
     * It will disable all log channels except the error channel
     */
    var errorOnlyLog: Boolean = false,

    /**
     * Maximum log size in KBs.
     *
     * Increasing it much will cause Binder issues for now
     */
    var maxLogSize: Int = 512,

    /**
     * Enable/disable target SDK 30+ restrictions for all apps
     */
    var forceMountData: Boolean = true,

    /**
     * Enable/disable the activity launch protection
     *
     * The activity launch protection basically blocks starting
     * applications when they are hidden from target app, and reduces detections
     */
    var disableActivityLaunchProtection: Boolean = false,

    /**
     * Enable/disable alternative (propless) appdata isolation
     */
    var altAppDataIsolation: Boolean = false,

    /**
     * Enable/disable alternative (propless) vold appdata isolation
     *
     * Use it carefully that some of Android editions are still leaking
     * vold existence even while not using props
     */
    var altVoldAppDataIsolation: Boolean = false,

    /**
     * Skip vold appdata isolation enforcements for system apps
     *
     * This will help you to reduce bootloop issues caused by vold appdata isolation
     */
    var skipSystemAppDataIsolation: Boolean = true,

    /**
     * Enable WebView protection to prevent some crashes caused by misconfigurations
     * for WebView or Browser apps
     */
    var webViewProtection: Boolean = true,

    /**
     * Enable ignorance of brand-level protections
     * It will be used in app data isolation for now
     */
    var ignoreBrandProtections: Boolean = false,

    /**
     * This config will be applied for ALL of new apps when enabled
     *
     * null means do not apply a default config
     */
    var defaultConfig: AppConfig? = null,

    val ignoredPackagesForPresets: MutableSet<String> = mutableSetOf(),

    /**
     * Rules for the one-tap "auto-protect" action, which scans every installed app and
     * configures the sensible anti-detection bundle on the ones that need it. All fields
     * have defaults, so this is backward compatible with configs written before it existed.
     */
    var autoProtect: AutoProtectConfig = AutoProtectConfig(),

    val templates: MutableMap<String, Template> = mutableMapOf(),
    val settingsTemplates: MutableMap<String, SettingsTemplate> = mutableMapOf(),

    /**
     * A list of disabled hooks, checked while the module is loading
     */
    val disabledHooks: MutableList<HookItem> = mutableListOf(),

    /**
     * A package name and config pair to keep per-app configs
     */
    val scope: MutableMap<String, AppConfig> = mutableMapOf()
) {
    /**
     * Customizable rule set driving the one-tap auto-protect.
     *
     * The engine classifies each installed app into one of:
     *  - a **sensitive** app (bank/fintech/payment/wallet/government, or a known detector):
     *    gets the full bundle - [appPresets] to hide from it, [settingsPresets] to spoof, and
     *    installation-source masking.
     *  - a **proxy** app ([proxyPackages], e.g. Google Play / Play Services): gets [settingsPresets]
     *    only. These apps run detection on behalf of others (the "Close app to continue"
     *    accessibility prompt is Google Play reading the enabled-accessibility list), so they must
     *    be covered, but must NOT have their package visibility or install source touched or Play
     *    itself breaks.
     *
     * Classification is by exact package ([sensitivePackages]) or package-name substring
     * ([sensitiveKeywords]); Android's app category is unreliable (often unset off-Play) and is
     * deliberately not used.
     */
    @Serializable
    data class AutoProtectConfig(
        var enabled: Boolean = true,

        /** App-hide presets applied to a sensitive app (names from AppPresets). */
        val appPresets: MutableSet<String> = mutableSetOf(
            "root_apps", "xposed", "detector_apps", "sus_apps", "shizuku_dhizuku",
        ),

        /** Settings presets applied to sensitive apps and to proxies (names from SettingsPresets). */
        val settingsPresets: MutableSet<String> = mutableSetOf(
            "accessibility", "dev_options", "input_method",
        ),

        /** Mask installation source on matched sensitive apps. */
        var hideInstallationSource: Boolean = true,

        /** Exact package names always treated as sensitive. User-extensible. */
        val sensitivePackages: MutableSet<String> = mutableSetOf(
            "com.mercadolibre",
            "com.mercadopago.wallet",
            "com.macro.banco.ar",
        ),

        /** Lowercase package-name substrings that mark an app sensitive. User-extensible. */
        val sensitiveKeywords: MutableSet<String> = mutableSetOf(
            "bank", "banco", "bbva", "santander", "galicia", "macro", "brubank", "uala",
            "wallet", "billetera", "mercadopago", "paypal", "payoneer", "revolut", "wise",
            "binance", "crypto", "coinbase", "lemon", "belo", "buenbit", "ripio",
            "upi", "paytm", "phonepe", "finance", "fintech", "gov", "gob",
        ),

        /** Detection-proxy apps that must also be covered with [settingsPresets] only. */
        val proxyPackages: MutableSet<String> = mutableSetOf(
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
        ),
    ) {
        override fun toString() = encoder.encodeToString(this)
    }

    @Serializable
    data class Template(
        /**
         * Is it a blacklist or whitelist template?
         */
        val isWhitelist: Boolean,

        /**
         * Apps inside of this application template
         */
        val appList: Set<String>
    ) {
        override fun toString() = encoder.encodeToString(this)
    }

    @Serializable
    /**
     * A type of template that holds settings replacements/overrides
     *
     * Also used for override the settings presets
     */
    data class SettingsTemplate(
        /**
         * Setting replacements inside of this settings template
         */
        val settingsList: Set<ReplacementItem>
    ) {
        override fun toString() = encoder.encodeToString(this)
    }

    @Serializable
    data class AppConfig(
        /**
         * Is it a blacklist or whitelist configuration?
         */
        var useWhitelist: Boolean = false,

        /**
         * Exclude/include system apps for whitelist mode
         */
        var excludeSystemApps: Boolean = true,

        /**
         * Hide all user apps' installation sources
         */
        var hideInstallationSource: Boolean = false,

        /**
         * Hide all system apps' installation sources
         */
        var hideSystemInstallationSource: Boolean = false,

        /**
         * Exclude the target app from installation source replacements
         */
        var excludeTargetInstallationSource: Boolean = false,

        /**
         * Invert the currently active activity protection mode for this application
         *
         * How this option works:
         * - If it was enabled globally, then it will be disabled for this application
         * - If it was disabled globally, then it will be enabled for this application
         */
        var invertActivityLaunchProtection: Boolean = false,

        /**
         * Exclude this app from appdata isolation
         *
         * This option is only useful when you don't need vold appdata isolation
         * for the target app or the app assumes the vold isolation as a detection point
         */
        var excludeVoldIsolation: Boolean = false,

        /**
         * A list of removed Zygote permissions while the app was launching
         */
        var restrictedZygotePermissions: List<Int> = listOf(),

        /**
         * A list of applied application templates, it has to contain items
         * from the global application template list
         */
        var applyTemplates: MutableSet<String> = mutableSetOf(),

        /**
         * A list of applied application presets, it has to contain items
         * from the global application preset list
         */
        var applyPresets: MutableSet<String> = mutableSetOf(),

        /**
         * A list of applied settings templates, it has to contain items
         * from the global settings template list
         */
        var applySettingTemplates: MutableSet<String> = mutableSetOf(),

        /**
         * A list of applied settings presets, it has to contain items
         * from the global settings preset list
         */
        var applySettingsPresets: MutableSet<String> = mutableSetOf(),

        /**
         * Extra hidden/unhidden apps list, depends on `useWhitelist`
         */
        var extraAppList: MutableSet<String> = mutableSetOf(),

        /**
         * Extra hidden/unhidden apps list in opposite way, depends on `useWhitelist`
         *
         * - A list of extra hidden apps on whitelist mode
         * - A list of extra unhidden apps on blacklist mode
         */
        var extraOppositeAppList: MutableSet<String> = mutableSetOf(),
    ) {
        override fun toString() = encoder.encodeToString(this)

        companion object {
            fun parse(json: String) = encoder.decodeFromString<AppConfig>(json)
        }
    }

    @Serializable
    data class HookItem(
        val className: String,
        val methodName: String,
        val argumentCount: Int,
    ) {
        override fun toString() = encoder.encodeToString(this)

        companion object {
            fun parse(json: String) = encoder.decodeFromString<HookItem>(json)
        }
    }

    companion object {
        fun parse(json: String) = encoder.decodeFromString<JsonConfig>(json)

    }

    override fun toString() = encoder.encodeToString(this)
}
