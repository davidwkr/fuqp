package com.iodvd.fuqp.common

/**
 * The single source of truth for the Auto-protect rule engine, shared by the manager
 * ([com.iodvd.fuqp.service.ConfigManager], one-tap and settings) and the zygote service
 * (automatic apply on install/update). Pure and additive: it mutates [JsonConfig.scope] in
 * place, only ever adding entries/presets, and never persists — the caller owns loading and
 * saving (and must not call it on a stale/empty config).
 */
object AutoProtect {
    data class Result(
        val sensitive: Int,
        val proxy: Int,
        val newlyProtected: List<String>,
    ) {
        val total get() = sensitive + proxy
        val changed get() = total > 0
    }

    /** False when the preset catalog has not initialised, so callers can refuse to half-apply. */
    val presetCatalogLoaded: Boolean
        get() = AppPresets.instance.presetNames.isNotEmpty() ||
            SettingsPresets.instance.presetNames.isNotEmpty()

    /**
     * Applies [config]'s [JsonConfig.AutoProtectConfig] rules to [packages], mutating
     * [config].scope. [isSystem] limits keyword matches to user apps (explicit sensitive/proxy
     * package lists still apply to system apps).
     */
    fun apply(
        config: JsonConfig,
        packages: List<String>,
        isSystem: (String) -> Boolean,
    ): Result {
        val rules = config.autoProtect

        // Keep only names the current build actually knows, so a stale/edited rule set cannot
        // inject preset names that silently do nothing.
        val validAppPresets = AppPresets.instance.presetNames.toSet()
        val validSettingsPresets = SettingsPresets.instance.presetNames.toSet()
        val appPresets = rules.appPresets.filter { it in validAppPresets }
        val settingsPresets = rules.settingsPresets.filter { it in validSettingsPresets }

        val scopeBefore = config.scope.keys.toSet()
        val newlyProtected = mutableListOf<String>()
        var sensitive = 0
        var proxy = 0
        for (pkg in packages) {
            when {
                pkg in rules.proxyPackages -> {
                    // Detection proxies (Google Play / Play Services): spoof settings only.
                    // Never touch their package visibility or install source, or Play breaks.
                    if (pkg !in scopeBefore) newlyProtected += pkg
                    val appConfig = config.scope.getOrPut(pkg) { JsonConfig.AppConfig() }
                    appConfig.applySettingsPresets.addAll(
                        settingsPresets.filterNot { it in appConfig.applySettingsPresets },
                    )
                    proxy++
                }

                isSensitive(pkg, rules, isSystem) -> {
                    if (pkg !in scopeBefore) newlyProtected += pkg
                    val appConfig = config.scope.getOrPut(pkg) { JsonConfig.AppConfig() }
                    appConfig.applyPresets.addAll(
                        appPresets.filterNot { it in appConfig.applyPresets },
                    )
                    appConfig.applySettingsPresets.addAll(
                        settingsPresets.filterNot { it in appConfig.applySettingsPresets },
                    )
                    if (rules.hideInstallationSource) {
                        appConfig.hideInstallationSource = true
                        appConfig.excludeTargetInstallationSource = true
                    }
                    sensitive++
                }
            }
        }

        return Result(sensitive, proxy, newlyProtected)
    }

    fun isSensitive(
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
