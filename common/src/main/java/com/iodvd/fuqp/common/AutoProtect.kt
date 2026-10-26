package com.iodvd.fuqp.common

/**
 * The single source of truth for the Auto-protect rule engine, shared by the manager
 * ([com.iodvd.fuqp.service.ConfigManager], one-tap and settings) and the zygote service
 * (automatic apply on install/update). Pure and additive: it mutates [JsonConfig.scope] in
 * place, only ever adding entries/presets, and never persists — the caller owns loading and
 * saving, and must apply on a private copy if other threads may read the config concurrently.
 */
object AutoProtect {
    data class Result(
        /** Packages that matched a sensitive rule this run (whether or not already configured). */
        val sensitive: Int,
        /** Packages that matched a proxy rule this run. */
        val proxy: Int,
        /** Packages that had no scope entry before this run (newly detected and configured). */
        val newlyProtected: List<String>,
        /** True only if the config was actually modified, so callers can skip a redundant save. */
        val mutated: Boolean,
    ) {
        val total get() = sensitive + proxy
    }

    /** False when the preset catalog has not initialised, so callers can refuse to half-apply. */
    val presetCatalogLoaded: Boolean
        get() = AppPresets.instance.presetNames.isNotEmpty() ||
            SettingsPresets.instance.presetNames.isNotEmpty()

    /**
     * Applies [config]'s [JsonConfig.AutoProtectConfig] rules to [packages], mutating
     * [config].scope. [isSystem] limits keyword matches to user apps (explicit sensitive/proxy
     * package lists still apply to system apps). Existing entries only ever gain presets;
     * install-source masking is set only on newly-created entries, so a user's per-app choices
     * are preserved. A package whose resolved preset set is empty is skipped rather than given an
     * empty (hook-on, nothing-hidden) entry.
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
        var mutated = false

        for (pkg in packages) {
            when {
                pkg in rules.proxyPackages -> {
                    // Detection proxies (Google Play / Play Services): spoof settings only.
                    // Never touch their package visibility or install source, or Play breaks.
                    if (settingsPresets.isEmpty()) continue
                    val isNew = pkg !in scopeBefore
                    val appConfig = config.scope.getOrPut(pkg) { mutated = true; JsonConfig.AppConfig() }
                    val added = settingsPresets.filterNot { it in appConfig.applySettingsPresets }
                    if (added.isNotEmpty()) {
                        appConfig.applySettingsPresets.addAll(added)
                        mutated = true
                    }
                    if (isNew) newlyProtected += pkg
                    proxy++
                }

                isSensitive(pkg, rules, isSystem) -> {
                    if (appPresets.isEmpty() && settingsPresets.isEmpty() && !rules.hideInstallationSource) continue
                    val isNew = pkg !in scopeBefore
                    val appConfig = config.scope.getOrPut(pkg) { mutated = true; JsonConfig.AppConfig() }

                    val addedApp = appPresets.filterNot { it in appConfig.applyPresets }
                    if (addedApp.isNotEmpty()) {
                        appConfig.applyPresets.addAll(addedApp)
                        mutated = true
                    }
                    val addedSet = settingsPresets.filterNot { it in appConfig.applySettingsPresets }
                    if (addedSet.isNotEmpty()) {
                        appConfig.applySettingsPresets.addAll(addedSet)
                        mutated = true
                    }
                    // Only on new entries, so an existing app's explicit install-source choice is kept.
                    if (rules.hideInstallationSource && isNew) {
                        appConfig.hideInstallationSource = true
                        appConfig.excludeTargetInstallationSource = true
                    }
                    if (isNew) newlyProtected += pkg
                    sensitive++
                }
            }
        }

        return Result(sensitive, proxy, newlyProtected, mutated)
    }

    fun isSensitive(
        pkg: String,
        rules: JsonConfig.AutoProtectConfig,
        isSystem: (String) -> Boolean,
    ): Boolean {
        // Explicit sensitive wins over everything; explicit exclusion then wins over heuristics.
        if (pkg in rules.sensitivePackages) return true
        if (pkg in rules.excludedPackages) return false
        if (isSystem(pkg)) return false
        // Blanket mode: every remaining (non-system, non-excluded) user app counts.
        if (rules.protectAllApps) return true
        val lower = pkg.lowercase()
        return rules.sensitiveKeywords.any { it in lower }
    }
}
