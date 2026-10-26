package com.iodvd.fuqp.service

import android.os.Build
import android.util.Log
import com.iodvd.fuqp.common.AutoProtect
import com.iodvd.fuqp.common.CollectionUtils.removeIfWithCount
import com.iodvd.fuqp.common.CollectionUtils.sync
import com.iodvd.fuqp.common.JsonConfig
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

    // `config` is mutated from IO (applyAutoProtect) and from the main thread (property
    // setters, UI reads). `lock` serializes load/save/merge against each other; `@Volatile`
    // gives readers that snapshot the reference (without the lock) a safe, consistent object.
    private val lock = Any()

    @Volatile
    private var config = JsonConfig()

    // saveConfig() rewrites the whole on-disk file, so it must never run against a config we
    // failed to load — that would overwrite every existing entry with defaults. Stays false
    // until a load actually succeeds, and any save is refused until then.
    @Volatile
    private var loadedOk = false

    /** Reads and parses the live config from the service, stamping the current version. Throws on failure. */
    private fun loadConfigStrict(): JsonConfig =
        JsonConfig.parse(ServiceClient.config).also { it.configVersion = BuildConfig.CONFIG_VERSION }

    /** Returns true if the live config was successfully read from the service. */
    fun init(): Boolean = synchronized(lock) {
        try {
            config = loadConfigStrict()
            loadedOk = true
        } catch (_: Throwable) {
            // Keep the previous config and loadedOk flag: a transient read failure must not
            // flip a previously-good state to "safe to overwrite with defaults".
        }
        loadedOk
    }

    fun saveConfig(): Unit = synchronized(lock) {
        if (!loadedOk) {
            log(Log.ERROR, TAG, "saveConfig refused: config was never loaded; not overwriting on-disk config")
            return@synchronized
        }
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

    /**
     * Outcome of a one-tap [applyAutoProtect] run. [newlyProtected] lists the packages that had
     * no scope entry before this run (i.e. newly detected and configured), so the caller can
     * report what actually changed rather than just totals.
     */
    data class AutoProtectResult(
        val sensitive: Int,
        val proxy: Int,
        val newlyProtected: List<String> = emptyList(),
    ) {
        val total get() = sensitive + proxy
    }

    var autoProtect: JsonConfig.AutoProtectConfig
        get() = config.autoProtect
        set(value) = synchronized(lock) {
            config.autoProtect = value
            saveConfig()
        }

    /**
     * Scans [allPackages] and configures the anti-detection bundle on the apps the rules match,
     * then saves once. Purely **additive**: entries for apps this run doesn't match are left
     * untouched, and matched apps only ever gain presets — nothing is removed. Note this means
     * a preset the user manually *removed* from a matched app is re-added on the next run.
     * Loads the live config fresh and aborts (returns null) if it cannot be read, so a failed
     * load can never cause the whole-file [saveConfig] to overwrite existing entries.
     *
     * @param isSystem tells a system app from a user app (keyword matches are limited to user
     *   apps to avoid false positives; explicit [JsonConfig.AutoProtectConfig.sensitivePackages]
     *   and [JsonConfig.AutoProtectConfig.proxyPackages] still apply to system apps).
     */
    fun applyAutoProtect(
        allPackages: List<String>,
        onlyIfEnabled: Boolean = false,
        isSystem: (String) -> Boolean,
    ): AutoProtectResult? = synchronized(lock) {
        // Merge onto a FRESHLY loaded config, not the shared `config`. If the read fails we
        // abort before touching anything — saveConfig() rewrites the whole file, so merging
        // into an empty/stale config would wipe every existing entry. Building privately and
        // publishing the reference at the end also means concurrent readers never observe a
        // half-merged scope.
        val merged = try {
            loadConfigStrict()
        } catch (_: Throwable) {
            log(Log.ERROR, TAG, "applyAutoProtect aborted: live config could not be loaded")
            return@synchronized null
        }
        // Automatic callers (install/update) pass onlyIfEnabled: respect the user's switch.
        // The manual one-tap leaves it false so it always runs.
        if (onlyIfEnabled && !merged.autoProtect.enabled) return@synchronized null

        if (!AutoProtect.presetCatalogLoaded) {
            // Proceeding would create empty scope entries that enable hooking with no hide
            // rules. Abort rather than half-apply.
            log(Log.ERROR, TAG, "applyAutoProtect aborted: preset catalog not loaded")
            return@synchronized null
        }

        val scopeBefore = merged.scope.keys.toSet()
        // Never auto-protect the manager itself — it must see the real app list (matters once
        // blanket "protect all apps" mode is on).
        val result = AutoProtect.apply(
            merged,
            allPackages.filterNot { it == com.iodvd.fuqp.BuildConfig.APPLICATION_ID },
            isSystem,
        )

        // Backstop invariant: the merge only ever adds entries, so the key set must never
        // shrink. If it somehow did, refuse to persist rather than drop a user's config.
        check(merged.scope.keys.containsAll(scopeBefore)) {
            "applyAutoProtect would drop existing scope entries; refusing to save"
        }

        config = merged
        loadedOk = true
        if (result.mutated) saveConfig()
        log(Log.INFO, TAG, "applyAutoProtect: ${result.sensitive} sensitive, ${result.proxy} proxy, ${result.newlyProtected.size} new")
        AutoProtectResult(result.sensitive, result.proxy, result.newlyProtected)
    }
}
