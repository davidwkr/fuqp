package com.iodvd.fuqp.service

import android.os.Build
import android.util.Log
import com.iodvd.fuqp.common.AppPresets
import com.iodvd.fuqp.common.CollectionUtils.removeIfWithCount
import com.iodvd.fuqp.common.CollectionUtils.sync
import com.iodvd.fuqp.common.JsonConfig
import com.iodvd.fuqp.common.SettingsPresets
import com.iodvd.fuqp.common.PropertyUtils
import com.iodvd.fuqp.common.settings_presets.ReplacementItem
import com.iodvd.fuqp.service.ServiceClient.log
import com.iodvd.fuqp.util.PackageHelper
import com.iodvd.fuqp.common.BuildConfig

object ConfigManager {
    /**
     * Indicates the type of preset/template.
     *
     * @see APP
     * @see SETTINGS
     */
    enum class PTType {
        /**
         * This preset/template type is used for app filtering.
         */
        APP,

        /**
         * This preset/template type is used for settings filtering.
         */
        SETTINGS,

        /**
         * Ignored apps from presets, not really a preset
         */
        IGNORED_APPS,
    }

    data class TemplateInfo(val name: String?, val type: PTType, val isWhiteList: Boolean)
    data class PresetInfo(val name: String, val type: PTType?, val translation: String)

    private const val TAG = "ConfigManager"
    private var config = JsonConfig()

    fun init() {
        try {
            val rawConfig = ServiceClient.config
            config = JsonConfig.parse(rawConfig)
        } catch (_: Throwable) {
            // ignore the issues
        }

        config.configVersion = BuildConfig.CONFIG_VERSION
    }

    fun saveConfig() {
        ServiceClient.config = config.toString()
    }

    var detailLog: Boolean
        get() = config.detailLog
        set(value) {
            config.detailLog = value
            saveConfig()
        }

    var errorOnlyLog: Boolean
        get() = config.errorOnlyLog
        set(value) {
            config.errorOnlyLog = value
            saveConfig()
        }

    var maxLogSize: Int
        get() = config.maxLogSize
        set(value) {
            config.maxLogSize = value
            saveConfig()
        }

    var forceMountData: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config.forceMountData
            else false
        set(value) {
            config.forceMountData = value
            saveConfig()
        }

    var disableActivityLaunchProtection: Boolean
        get() = config.disableActivityLaunchProtection
        set(value) {
            config.disableActivityLaunchProtection = value
            saveConfig()
        }

    var altAppDataIsolation: Boolean
        get() = !PropertyUtils.isAppDataIsolationEnabled && config.altAppDataIsolation
        set(value) {
            config.altAppDataIsolation = value
            saveConfig()
        }

    var altVoldAppDataIsolation: Boolean
        get() = !PropertyUtils.isVoldAppDataIsolationEnabled && config.altVoldAppDataIsolation
        set(value) {
            config.altVoldAppDataIsolation = value
            saveConfig()
        }

    var skipSystemAppDataIsolation: Boolean
        get() = config.skipSystemAppDataIsolation
        set(value) {
            config.skipSystemAppDataIsolation = value
            saveConfig()
        }

    var webViewProtection: Boolean
        get() = config.webViewProtection
        set(value) {
            config.webViewProtection = value
            saveConfig()
        }

    var defaultConfig: JsonConfig.AppConfig?
        get() = config.defaultConfig
        set(value) {
            config.defaultConfig = value
            saveConfig()
        }

    var disabledHooks: List<JsonConfig.HookItem>
        get() = config.disabledHooks
        set(elements) {
            config.disabledHooks.sync(elements)
            saveConfig()
        }

    var ignoredPackagesForPresets: Set<String>
        get() = config.ignoredPackagesForPresets
        set(elements) {
            config.ignoredPackagesForPresets.sync(elements)
            saveConfig()
        }

    fun importConfig(json: String) {
        config = JsonConfig.parse(json)
        config.configVersion = BuildConfig.CONFIG_VERSION
        saveConfig()
    }

    fun hasTemplate(name: String?): Boolean {
        return config.templates.containsKey(name)
    }

    fun getTemplateList(): MutableList<TemplateInfo> {
        return config.templates.mapTo(mutableListOf()) { TemplateInfo(it.key, PTType.APP, it.value.isWhitelist) }
    }

    fun getTemplateAppliedAppList(name: String): ArrayList<String> {
        return config.scope.mapNotNullTo(ArrayList()) {
            if (it.value.applyTemplates.contains(name)) it.key else null
        }
    }

    fun getTemplateTargetAppList(name: String): ArrayList<String> {
        return ArrayList(config.templates[name]?.appList ?: emptyList())
    }

    fun deleteTemplate(name: String) {
        config.scope.forEach { (_, appInfo) ->
            appInfo.applyTemplates.remove(name)
        }
        config.templates.remove(name)
        saveConfig()
    }

    fun renameTemplate(oldName: String, newName: String) {
        if (oldName == newName) return
        config.scope.forEach { (_, appInfo) ->
            if (appInfo.applyTemplates.contains(oldName)) {
                appInfo.applyTemplates.remove(oldName)
                appInfo.applyTemplates.add(newName)
            }
        }
        config.templates[newName] = config.templates[oldName]!!
        config.templates.remove(oldName)
        saveConfig()
    }

    fun updateTemplate(name: String, template: JsonConfig.Template) {
        log(Log.DEBUG, TAG, "updateTemplate: $name list = ${template.appList}")
        config.templates[name] = template
        saveConfig()
    }

    fun updateTemplateAppliedApps(name: String, appliedList: List<String>) {
        log(Log.DEBUG, TAG, "updateTemplateAppliedApps: $name list = $appliedList")
        config.scope.forEach { (app, appInfo) ->
            if (appliedList.contains(app)) appInfo.applyTemplates.add(name)
            else appInfo.applyTemplates.remove(name)
        }
        saveConfig()
    }

    fun getSettingTemplateList(): MutableList<TemplateInfo> {
        return config.settingsTemplates.mapTo(mutableListOf()) { TemplateInfo(it.key, PTType.SETTINGS, false) }
    }

    fun getSettingTemplateAppliedAppList(name: String): ArrayList<String> {
        return config.scope.mapNotNullTo(ArrayList()) {
            if (it.value.applySettingTemplates.contains(name)) it.key else null
        }
    }

    fun getSettingTemplateTargetSettingList(name: String): ArrayList<ReplacementItem> {
        return ArrayList(config.settingsTemplates[name]?.settingsList ?: emptyList())
    }

    fun deleteSettingTemplate(name: String) {
        config.scope.forEach { (_, appInfo) ->
            appInfo.applySettingTemplates.remove(name)
        }
        config.settingsTemplates.remove(name)
        saveConfig()
    }

    fun renameSettingTemplate(oldName: String, newName: String) {
        if (oldName == newName) return
        config.scope.forEach { (_, appInfo) ->
            if (appInfo.applySettingTemplates.contains(oldName)) {
                appInfo.applySettingTemplates.remove(oldName)
                appInfo.applySettingTemplates.add(newName)
            }
        }
        config.settingsTemplates[newName] = config.settingsTemplates[oldName]!!
        config.settingsTemplates.remove(oldName)
        saveConfig()
    }

    fun updateSettingTemplate(name: String, template: JsonConfig.SettingsTemplate) {
        log(Log.DEBUG, TAG, "updateSettingTemplate: $name list = ${template.settingsList}")
        config.settingsTemplates[name] = template
        saveConfig()
    }

    fun updateSettingTemplateAppliedApps(name: String, appliedList: List<String>) {
        log(Log.DEBUG, TAG, "updateSettingTemplateAppliedApps: $name list = $appliedList")
        config.scope.forEach { (app, appInfo) ->
            if (appliedList.contains(app)) appInfo.applySettingTemplates.add(name)
            else appInfo.applySettingTemplates.remove(name)
        }
        saveConfig()
    }

    fun isHideEnabled(packageName: String): Boolean {
        return config.scope.containsKey(packageName)
    }

    fun getAppConfig(packageName: String): JsonConfig.AppConfig? {
        return config.scope[packageName]
    }

    fun setAppConfig(packageName: String, appConfig: JsonConfig.AppConfig?) {
        if (appConfig == null) config.scope.remove(packageName)
        else config.scope[packageName] = appConfig
        saveConfig()
    }

    fun clearUninstalledAppConfigs(inConfig: JsonConfig = config, onFinish: (success: Boolean) -> Unit) {
        PackageHelper.invalidateCache { throwable ->
            if (throwable == null) {
                // --- STEP 1: Clear uninstalled app configs ---
                val scopeRemoveCount = inConfig.scope.removeIfWithCount { packageName, _ ->
                    !PackageHelper.exists(packageName)
                }

                // --- STEP 2: Clear uninstalled apps from extra app lists ---
                var cleanedAppCount = 0
                inConfig.scope.values.forEach { config ->
                    cleanedAppCount += config.extraAppList.removeIfWithCount { packageName ->
                        !PackageHelper.exists(packageName)
                    }

                    cleanedAppCount += config.extraOppositeAppList.removeIfWithCount { packageName ->
                        !PackageHelper.exists(packageName)
                    }
                }

                // --- STEP 3: Clear uninstalled apps from templates ---
                inConfig.templates.forEach { (key, value) ->
                    val newList = value.appList.filter { PackageHelper.exists(it) }
                    val count = value.appList.size - newList.size

                    if (count > 0) {
                        cleanedAppCount += count
                        inConfig.templates[key] = JsonConfig.Template(
                            isWhitelist = value.isWhitelist,
                            appList = newList.toSet(),
                        )
                    }
                }

                if ((scopeRemoveCount > 0 || cleanedAppCount > 0) && inConfig == config) {
                    log(Log.INFO, TAG, "Pruned $scopeRemoveCount app configs and $cleanedAppCount app entries")
                    saveConfig()
                }

                onFinish(true)
            } else {
                onFinish(false)
            }
        }
    }

    fun getRawConfig(deepCopy: Boolean): JsonConfig {
        if (deepCopy) {
            val scopeCopy = config.scope.toMutableMap()
            val templateCopy = config.templates.toMutableMap()
            val settingsTemplateCopy = config.settingsTemplates.toMutableMap()

            return config.copy(
                scope = scopeCopy,
                templates = templateCopy,
                settingsTemplates = settingsTemplateCopy,
            )
        }

        return config
    }

    fun resetConfig() {
        config = JsonConfig()
        saveConfig()
    }

    /** Outcome of a one-tap [applyAutoProtect] run. */
    data class AutoProtectResult(val sensitive: Int, val proxy: Int) {
        val total get() = sensitive + proxy
    }

    var autoProtect: JsonConfig.AutoProtectConfig
        get() = config.autoProtect
        set(value) {
            config.autoProtect = value
            saveConfig()
        }

    /**
     * Scans [allPackages] and configures the anti-detection bundle on the apps the rules match,
     * then saves once. Purely **additive**: existing per-app choices are merged into, never
     * cleared, so re-running is safe and manual tweaks survive.
     *
     * @param isSystem tells a system app from a user app (keyword matches are limited to user
     *   apps to avoid false positives; explicit [JsonConfig.AutoProtectConfig.sensitivePackages]
     *   and [JsonConfig.AutoProtectConfig.proxyPackages] still apply to system apps).
     */
    fun applyAutoProtect(
        allPackages: List<String>,
        isSystem: (String) -> Boolean,
    ): AutoProtectResult {
        val rules = config.autoProtect

        // Keep only names the current build actually knows, so a stale/edited rule set cannot
        // inject preset names that silently do nothing.
        val validAppPresets = AppPresets.instance.presetNames.toSet()
        val validSettingsPresets = SettingsPresets.instance.presetNames.toSet()
        val appPresets = rules.appPresets.filter { it in validAppPresets }
        val settingsPresets = rules.settingsPresets.filter { it in validSettingsPresets }

        var sensitive = 0
        var proxy = 0
        for (pkg in allPackages) {
            when {
                pkg in rules.proxyPackages -> {
                    // Detection proxies (Google Play / Play Services): spoof settings only.
                    // Never touch their package visibility or install source, or Play breaks.
                    val appConfig = config.scope.getOrPut(pkg) { JsonConfig.AppConfig() }
                    appConfig.applySettingsPresets.addAll(settingsPresets)
                    proxy++
                }

                isSensitive(pkg, rules, isSystem) -> {
                    val appConfig = config.scope.getOrPut(pkg) { JsonConfig.AppConfig() }
                    appConfig.applyPresets.addAll(appPresets)
                    appConfig.applySettingsPresets.addAll(settingsPresets)
                    if (rules.hideInstallationSource) {
                        appConfig.hideInstallationSource = true
                        appConfig.excludeTargetInstallationSource = true
                    }
                    sensitive++
                }
            }
        }

        saveConfig()
        log(Log.INFO, TAG, "applyAutoProtect: $sensitive sensitive, $proxy proxy")
        return AutoProtectResult(sensitive, proxy)
    }

    private fun isSensitive(
        pkg: String,
        rules: JsonConfig.AutoProtectConfig,
        isSystem: (String) -> Boolean,
    ): Boolean {
        if (pkg in rules.sensitivePackages) return true
        if (isSystem(pkg)) return false
        val lower = pkg.lowercase()
        return rules.sensitiveKeywords.any { it in lower }
    }
}
