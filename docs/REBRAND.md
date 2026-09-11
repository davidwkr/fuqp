# Rebrand: HMA-OSS → F-U Query Package

Record of the rename from the upstream HMA-OSS fork to **F-U Query Package**, package
`com.iodvd.fuqp`, repo `davidwkr/fuqp`. Written so the next person does not have to
re-derive which of the remaining "HMA" strings are stale and which are load-bearing.

## Identifier mapping

Upstream carried **two** unrelated source roots that both belonged to this app; they were
merged into one. There were no file-name or top-level-declaration collisions.

| Old | New |
|-----|-----|
| `icu.nullptr.hidemyapplist.*` (app legacy tree + `:common`) | `com.iodvd.fuqp.*` |
| `org.frknkrc44.hma_oss.*` (app newer tree + `:zygote`) | `com.iodvd.fuqp.*` |
| `appPackageName` extra / `applicationId` | `com.iodvd.fuqp` |
| `rootProject.name` = `HMA-OSS` | `FUQP` (drives APK/ZIP artifact names) |
| Zygisk module id `hma_oss_zygisk` | `fuqp_zygisk` |
| Zygisk module name `HMA-OSS Zygisk` | `F-U Query Package Zygisk` |
| `app_name` = `HMA-OSS` | `F-U Query Package` |
| Logcat tag `HMA-OSS` | `FUQP` |
| Export filenames `HMA-OSS_config_*.json`, `HMA-OSS_logs_*.log` | `FUQP_config_*.json`, `FUQP_logs_*.log` |
| View id `list_hma_oss` | `list_fuqp` |
| `github.com/frknkrc44/HMA-OSS` | `github.com/davidwkr/fuqp` |
| Wiki anchor `About-HMA‐OSS` | `About-FUQP` |
| Service classes | `FUQPService`, `FUQPServiceCache` |
| Application singleton | `fuqpApp` |
| README logo asset | `FUQP.svg` |

Also normalized: `LogAdapter.kt` lived in `.../adapter/` while declaring
`...ui.adapter` — it now sits in the directory it declares.

Product-name text was substituted in `values*/strings.xml` (all 26 locales), the Magisk
installer scripts under `zygote/src/main/assets/`, and the fastlane listing. The bare tokens
`HMA` and `HMA/HMAL` remain only where they name upstream apps or project history.
The secondary-user warning and localized filter counters now use F-U Query Package.
README headings, logo references, issue-template branding, service classes, the app
singleton, and service log messages also use the new name.

## Deliberately not renamed

| Thing | Why |
|-------|-----|
| `com.tsng.hidemyapplist`, `com.google.android.hmal` (`common/.../Utils.kt`) | Package names of *other* apps, matched for conflict detection. |
| `/data/misc/hide_my_applist_*` (`zygote/.../FUQPService.kt`) | On-device data directory. Renaming orphans existing installs' config. |
| About-screen credits ("HMA-OSS Developer", "HMA-OSS Alt Icon Designer") and the `frknkrc44` Zygisk `author` field | Attribution to upstream authors, not branding. |
| `FUNDING.yml` | Upstream sponsorship links. |
| translators.json fallback URL in `app/build.gradle.kts` | Build-time input, not an update endpoint. It is fetched **during configuration**, so pointing it at `davidwkr/fuqp` breaks every build until a release asset named `translators.json` exists there. See follow-ups. |

## Follow-ups

The AIDL interface is now `com.iodvd.fuqp.common.IFUQPService`. Both its package and
name contribute to the Binder descriptor, so the manager and Zygisk module must be
built with the same interface.

1. **Publish the Zygisk update manifest.** `zygote/build.gradle.kts` now advertises
   `https://raw.githubusercontent.com/davidwkr/fuqp/master/update.json`. That file does not
   exist yet; module managers will show an update-check error until it does. It needs
   `version`, `versionCode`, `zipUrl`, `changelog`.
2. **Wiki pages.** Three in-app deep links point at `davidwkr/fuqp/wiki/About-FUQP`
   (`HomeFragment`, `StatsFragment` `#filter-logs-categories`, `BulkConfigWizardFragment`
   `#bulk-config-wizard`). Create those anchors or repoint the links.
3. **translators.json.** Either publish a release asset of that name and flip the URL in
   `app/build.gradle.kts:79`, or set `crowdinApiKey` in `local.properties` so the fallback is
   never hit.
4. **Crowdin project.** `Constants.TRANSLATE_URL` and `crowdin.yml` still target the upstream
   Crowdin project. Repoint once a new project exists.
5. **Migration UX.** The old package is not in `conflictedModules`, so users upgrading from
   HMA-OSS will end up with both apps installed and two Magisk modules
   (`hma_oss_zygisk` + `fuqp_zygisk`). If a clean upgrade path matters, add
   `com.iodvd.fuqp`'s predecessor to the conflict list and to the migration flow.

## Verification performed

Debug builds were verified with the existing `local.properties`, using sequential
Gradle invocations. The combined invocation can run Zygote's manager-APK check
before the app APK is assembled. Static checks also covered:

- Zero remaining `frknkrc44.hma_oss` / `nullptr.hidemyapplist` identifier references.
- Every `.kt`/`.java`/`.aidl` `package` declaration matches its directory.
- No duplicate top-level declarations introduced by merging the two source roots.
- Manifest, navigation graph, layouts, ProGuard rules and the Magisk shell scripts all
  resolve to `com.iodvd.fuqp`.

Build with:

```
./gradlew :app:assembleDebug && ./gradlew :zygote:assembleDebug
```
