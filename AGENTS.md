# F-U Query Package (FUQP)

Android app + Zygisk module that hides the installed-application list from querying apps.
Fork of HMA-OSS, rebranded and repackaged — see [docs/REBRAND.md](docs/REBRAND.md) for the
old→new name mapping and the deliberate exceptions.

## Modules

| Module    | Type        | Namespace              | Role |
|-----------|-------------|------------------------|------|
| `:app`    | application | `com.iodvd.fuqp`        | Manager UI. Talks to the service over AIDL via a `ContentProvider` handshake. |
| `:common` | library     | `com.iodvd.fuqp.common` | `IFUQPService.aidl`, JSON config model, app/settings presets. Shared by `:app` and `:zygote`. |
| `:zygote` | application | `com.iodvd.fuqp.zygote` | Zygisk module injected into `system_server`; hooks PMS and friends. Packs the manager APK as an asset. |

All three share the single source root `com.iodvd.fuqp`. Module namespaces are derived from
the `appPackageName` extra in the root `build.gradle.kts` — change it there, nowhere else.

## Building

`local.properties` is **required**; the root build reads it unconditionally and fails
configuration without it:

```
sdk.dir=$ANDROID_HOME
```

Optional keys: `fileDir`/`storePassword`/`keyAlias`/`keyPassword` (release signing),
`crowdinProjectId`/`crowdinApiKey`, `officialBuild`, `localBuild`.

Two more configuration-time requirements that bite on fresh clones:

- **`refs/remotes/origin/master` must exist.** `appVerCode` is `git rev-list ... --count`.
  Run `git fetch origin master` first on a shallow or fork clone.
- **Network access.** `:app` fetches the translator list during configuration — from Crowdin
  when `crowdinApiKey` is set, otherwise from a GitHub release asset (see REBRAND.md;
  this one URL still points upstream on purpose).

Build order matters: `:zygote` embeds the manager APK, so build `:app` first.

```
./gradlew :app:assembleRelease && ./gradlew :zygote:assembleRelease
```

Artifacts are named from `rootProject.name` (`FUQP`): `FUQP-<ver>-<variant>.apk` and
`FUQP-ZYGISK-<ver>-<variant>.zip`. CI globs `*.zip`, so renaming the project does not
require workflow edits.

## Naming landmines

These look like stale references to the old name but are **not** — do not "fix" them:

- `com.tsng.hidemyapplist` and `com.google.android.hmal` in `common/.../Utils.kt` are the
  *upstream closed-source apps*, matched for conflict detection. Renaming them breaks
  conflict/migration detection.
- `/data/misc/hide_my_applist_*` in `zygote/.../FUQPService.kt` is the on-device service data
  directory. Renaming it orphans every existing installation's config.
- The AIDL interface is `com.iodvd.fuqp.common.IFUQPService`. Its package and name
  determine the Binder descriptor; the manager and Zygisk module must use the same interface.
- The About screen credits upstream authors as "HMA-OSS Developer" / "HMA-OSS Alt Icon
  Designer". That is attribution, not branding.

## Strings

`app/src/main/res/values/strings.xml` is the Crowdin **source**. Every `values-<locale>/`
file is Crowdin output (`crowdin.yml`) and will be overwritten by the Crowdin Action. Hand-edit
translations only for mechanical substitutions such as a product rename; real wording changes
belong in Crowdin.

`app_name` is `translatable="false"`, so it lives only in the source file.

`settings_data_isolation_summary` takes three arguments and uses **positional** specifiers
(`%1$s`, `%2$s`, `%3$s`) in every locale. They were converted by hand across all 26 files;
Crowdin still has the old bare `%s` in its translation memory, so the next Crowdin sync will
revert them unless the source string is updated in Crowdin first.

## Resource size limit

aapt2 cannot encode a string longer than 32767 bytes into the resource string pool — it
silently substitutes the literal `STRING_TOO_LARGE`, which turns the affected resource into
garbage at inflate time rather than failing the build.

`app/src/main/res/drawable/ic_launcher_alt_5_foreground.xml` is a traced illustration whose
largest `pathData` sits at ~31.7 KB, about 1 KB under that ceiling. It was brought under the
limit losslessly (dropping zero-length `v0` no-ops and redundant leading zeros — no coordinate
was changed). **If this asset is ever re-exported from the source art it will very likely blow
the limit again.** Check with:

```
python3 - <<'EOF'
import re,glob
for f in glob.glob('app/src/main/res/**/*.xml', recursive=True):
    for m in re.finditer(r'="([^"]*)"', open(f, encoding='utf-8').read()):
        if len(m.group(1).encode()) > 32767: print(f, len(m.group(1)))
EOF
```
