# Syncing with upstream HMA-OSS

How to bring upstream HMA-OSS changes into F-U Query Package without losing our own work, and
the checklist of everything that is ours and must survive each sync.

Read [REBRAND.md](REBRAND.md) first for what the rename covers.

## Why a plain `git merge` does not work

We renamed every source path, every package, and several classes (`HMAService` →
`FUQPService`, `IHMAService` → `IFUQPService`, …). To git, each of those files is "deleted and
re-added with different content" on our side, so an ordinary merge reports a conflict in nearly
every file upstream touched — hundreds of them.

The fix is to merge against a **synthetic base**: the last upstream commit we synced, *with our
rename applied to it*. Against that base the rename cancels out completely, and git only sees
real changes — ours and upstream's. In the 2026-10-04 sync this turned 220 changed upstream files
into 15 genuine conflicts.

## One-time setup

```sh
git remote add upstream https://github.com/frknkrc44/HMA-OSS.git
```

Upstream builds AndroidVMTools from a git submodule (`external/AndroidVMTools`). JitPack cannot
build recent revisions of it, so do not try to replace the submodule with a Maven/JitPack pin:

```sh
git submodule update --init --recursive
```

## Procedure

Scripts live in `tools/upstream-sync/`. Run everything from the repo root.

### 1. Fetch and pick the target

```sh
git fetch upstream --no-tags
git log --oneline <LAST_SYNCED>..upstream/master     # stable line
git log --oneline upstream/master..upstream/future   # upstream's pre-release work (QPR fixes land here first)
```

`<LAST_SYNCED>` is the upstream commit recorded in the **Sync log** at the bottom of this file.

### 2. Build the two renamed trees

```sh
git worktree add --detach /tmp/wt-base <LAST_SYNCED>
git worktree add --detach /tmp/wt-up   <NEW_UPSTREAM>
tools/upstream-sync/rebrand.sh /tmp/wt-base
tools/upstream-sync/rebrand.sh /tmp/wt-up
R0=$(cd /tmp/wt-base && git add -A && git commit-tree "$(git write-tree)" -p <LAST_SYNCED>   -m "renamed base (synthetic)")
U=$( cd /tmp/wt-up   && git add -A && git commit-tree "$(git write-tree)" -p <NEW_UPSTREAM> -m "renamed upstream (synthetic)")
```

### 3. Validate the rename script before trusting it

```sh
git diff --stat -M $R0 HEAD
```

Every file listed must be explainable by the **Features to port** checklist below or by branding.
If you see files whose only change is package names, imports or `HMA*` class names, upstream has
introduced an identifier `rebrand.sh` does not know about: extend the script and redo step 2.
Also check the renamed upstream for leftovers:

```sh
git grep -n -E 'frknkrc44\.hma_oss|nullptr\.hidemyapplist|\bHMAService' $U -- . ':!*.md'
```

### 4. Dry-run, then merge on a new branch

```sh
git merge-tree --write-tree --merge-base=$R0 --name-only HEAD $U   # lists conflicts, touches nothing
git switch -c sync/upstream-<name>
git read-tree -m -u $R0 HEAD $U
git merge-index -o -q git-merge-one-file -a
git diff --name-only --diff-filter=U                               # what is left to resolve
```

Never do the rework on the feature branch; it stays untouched until the sync branch is verified.

### 5. Resolve conflicts — rules

| Situation | Rule |
|---|---|
| A line upstream changed that we had only rebranded (log tags, script messages, `OSUtils` header) | Take upstream, re-apply our name: `tools/upstream-sync/resolve_theirs.py FILE 'OLD=NEW' …` |
| Translations (`values-*/strings.xml`) | Take upstream's newer Crowdin text, rename (`'HMA-OSS=F-U Query Package' ' HMA = F-U Query Package '`), then run `positional_args.py`. Keep `HMA/HMAL` — those name the upstream closed-source apps. |
| Upstream deleted a file we had changed | First check it is not a **rename**: `git diff -M --stat <LAST_SYNCED> <NEW_UPSTREAM> -- <dir>`. A merge against the synthetic base reports a rename-on-their-side as "deleted by them", and accepting that silently keeps upstream's unfixed copy under the new name. Only if it is truly gone: `git rm` it, after confirming nothing references it. |
| Code where both sides changed logic | Port **our feature onto upstream's new design**. Never revert an upstream refactor to make our old code fit. |

### 6. Post-merge sweep — clean merges can still be wrong

Files without textual conflicts can still call APIs the other side removed, or carry upstream's
new hardcoded names. Check:

```sh
# APIs upstream has replaced; fix any of OUR code still using them
rg -n 'BulkHooker\.instance|FUQPService\.Companion\.service|FUQPServiceCache\b' zygote/src
# upstream text and paths that escaped the rename
rg -n -i 'HMA-OSS|hma_oss|HideMyAss|"HMA-[A-Za-z]' --glob '!*.md' --glob '!dist/**' --glob '!external/**' .
```

Pay particular attention to anything written into a **directory shared by every module**:
`/data/adb/post-fs-data.d/`, `post-mount.d/`, `boot-completed.d/`, `service.d/`. Upstream names its
files there `hmaoss.sh`; ours must have a different name (`fuqp.sh`) or installing FUQP overwrites
HMA-OSS's copy and uninstalling FUQP deletes it. Likewise any hardcoded `/data/adb/modules/<id>`
must use `fuqp_zygisk`.

Re-run the resource fixers; upstream ships both problems and every sync brings them back:

```sh
tools/upstream-sync/positional_args.py      # bare %s in multi-argument strings
tools/upstream-sync/shrink_vector_paths.py  # vector pathData over aapt2's 32767-byte limit
```

Expected leftovers that are correct and must stay: the About-screen credits ("HMA-OSS Developer",
…), `Constants.TRANSLATE_URL`, and the `translators.json` fallback URL in `app/build.gradle.kts`.
See REBRAND.md for why.

### 7. Build

```sh
git submodule update --init --recursive
./gradlew :app:assembleDebug && ./gradlew :zygote:assembleDebug     # two invocations, :app first
```

`:zygote` embeds the manager APK and checks for it at configuration time, so a single combined
invocation fails.

### 8. Verify on a device

Go through the checklist below; every feature has its own check. A clean build proves nothing
about hooks that bind at runtime.

### 9. Commit as a real merge

Record upstream as a parent, so the next sync can find it and `git log upstream/<branch>..HEAD`
shows only our work:

```sh
git commit-tree "$(git write-tree)" -p HEAD -p <NEW_UPSTREAM> -F msg.txt   # then fast-forward the sync branch to it
```

Then add a line to the **Sync log**.

## Features to port

Everything below is ours. After a sync, each item must still be present and working.

### 1. Identity and rebrand

Mechanical (handled by `rebrand.sh`): source paths and packages → `com.iodvd.fuqp`;
`HMAService*` → `FUQPService*`; `IHMAService` → `IFUQPService`; `hmaApp` → `fuqpApp`;
`LogAdapter.kt` moved into `ui/adapter/`; `HideMyAss-OSS.svg` → `FUQP.svg`.

Manual (arrives through the merge as our side's change, but check it survived):

- `build.gradle.kts`: `appPackageName = "com.iodvd.fuqp"`
- `settings.gradle.kts`: `rootProject.name = "FUQP"`
- `zygote/build.gradle.kts` `zygisk { }`: `id = "fuqp_zygisk"`, `name`, `description`,
  `updateJson` → `davidwkr/fuqp`
- `app_name` and "HMA-OSS" text in all `strings.xml`; installer scripts; fastlane; READMEs
- Log tags `FUQP`, `FUQP-Service`, `FUQP-UserService`; export file names `FUQP_config_*`,
  `FUQP_logs_*`; view id `list_fuqp`
- GitHub/wiki URLs → `davidwkr/fuqp`
- Shared-directory script `zygote/src/main/assets/fuqp.sh` (upstream: `hmaoss.sh`) and its
  references in `customize.d/40-setup-module-status.sh` and `uninstall.sh`

Not renamed on purpose: `/data/misc/hide_my_applist_*` (on-device data dir),
`com.tsng.hidemyapplist` / `com.google.android.hmal` (other apps, conflict detection).

### 2. Activity-launch protection rework

`zygote/.../hook/ActivityHook.kt`, docs `ACTIVITY_LAUNCH_GUARD.md` / `ACTIVITY_LAUNCH_HANDOFF.md`,
harness `tests/activity-launch/`.

- The start-path guard is **always installed**, not only as a fallback when
  `applyPostResolutionFilter` fails to bind. (Upstream wraps it in
  `if (!isHookAvailable(aPRFClazz, …))`; we removed that wrapper.)
- A blocked start is **deflected**: `guardRequest` / `guardFrame` rewrite the request's intent to
  an unresolvable component and null `activityInfo` / `resolveInfo`, so the launch fails as
  "not found" naturally. Only if that cannot be applied does it fall back to `fakeReturnCode`.
- `RequestFields` reads the request through cached reflective fields; pre-R `startActivity` is
  bound by verified parameter layout (`findParamCount`), not by guessing.
- `applyPostResolutionFilter` reads the caller from the explicit index
  `APRF_FILTER_CALLING_UID`, never `frame.args.firstWithType<Int>()` (upstream still uses the
  latter). Since the 2026-10-04 sync it filters **after** the original (upstream's change, which
  also covers Samsung and trims One UI's input list).
- `DIAG` switch with `resolveEntryDiag`.

Verify: `tests/activity-launch/run.py` (see its README) — protected launches fail with
`ActivityNotFoundException`/`START_CLASS_NOT_FOUND`, never `SecurityException`, and disabled /
inverted protection still allows the launch.

### 3. Intent-query hiding

`PmsHookTargetBase.hookIntentQuery()`, registered from `PmsHookTarget33.load()` (API 33+).
Upstream has nothing equivalent.

- Hooks `queryIntentActivitiesInternal` and filters the returned list by `filterCallingUid`.
- Binds only to an overload whose **exact parameter list** is in `intentQueryLayouts`. Each
  Android release may reshuffle this signature; if none matches, it logs
  "intent queries are NOT filtered" instead of binding to the wrong method. **On every new
  Android version, re-read the signature from the device's `services.jar` and add a layout.**
- `resolveForStart` results go through `shouldHideActivityLaunch`, so the launch-protection
  switches stay authoritative.

Verify: logcat shows `queryIntentActivitiesInternal bound: …`; a scoped detector's `intent`
vector no longer lists hidden packages.

### 4. GmsCompat shim: loadable but not listed

`Constants.packagesVisibleOnExactName`, `hook/ExactNameLookup.kt`, `applyPackageHiding`'s
`exactNameLookup` parameter, and the `getApplicationInfoInternal` hook in `PmsHookTarget29/30/31/33`.

GrapheneOS-derived ROMs load `app.grapheneos.gmscompat.lib` into every GMS-using app by exact
name at startup; hiding it kills those apps. The fix keeps it resolvable by name while still
removing it from listings:

- `getApplicationInfoInternal` is hooked with `hookAround`: `before` opens a thread-local window
  for the package, `after` closes it and then runs normal hiding with `exactNameLookup = true`.
- The shared gate (`shouldFilterApplication` / `filterAppAccessLPr`) exempts the package **only
  while the window is open**. It must never carry `exactNameLookup = true` itself, or the package
  leaks into `getInstalledPackages` / `getPackagesForUid`.
- **Exactly one opener per API level.** An opener reachable from inside another opener closes the
  outer window on its way out. Check the class hierarchy too: `PmsHookTarget34` extends `33`.

Verify (scoped caller holding `QUERY_ALL_PACKAGES`, e.g. uid of `io.liankong.riskdetector`):

```sh
su <uid> -c "pm list packages" | grep -c gmscompat.lib              # expect 0
su <uid> -c "pm list packages --uid <shim uid>" | grep -c gmscompat  # expect 0
su <uid> -c "pm list packages" | grep -c app.grapheneos.logviewer    # control, expect 1
```

…and a scoped GMS app (Word) still starts with the shim in its hide list.

### 5. Hooking infrastructure (`BulkHooker`, `BulkHookerData`)

- `hookAround(before, after)` — brackets one invocation; `after` always runs, on the same thread.
- `paramTypes` on `hookBefore` / `hookAfter` / `hookAround` and `HookElement.paramTypes`; exact-type
  matching in `resolveExecutable`.
- `findExecutable` / `findParamCount` — select an overload by parameter shape.
- `addHook` returns whether the hook actually bound (used to log "bound" honestly).
- `putIfAbsent` instead of `computeIfAbsent` in `addHook`. Upstream still has
  `computeIfAbsent`; a system_server tombstone on `bc0d61b6` aborted inside it (see commit
  `f0a2be2d`). Re-check this line after every sync.

### 6. Resources and housekeeping

- Positional format arguments (`positional_args.py`) — Crowdin reverts them; rerun every sync.
- No string-pool value over 32767 bytes, or aapt2 silently writes `STRING_TOO_LARGE` and the
  drawable renders as garbage. Upstream's alt launcher icon (`ic_launcher_alt_4`, formerly
  `alt_5`) has a 33,835-byte path; `shrink_vector_paths.py` fixes it losslessly. Verify with
  `./gradlew :app:processDebugResources --rerun-tasks` and grep the output for `STRING_TOO_LARGE`.
- `.gitignore` entries for agent state; docs `AGENTS.md`, `REBRAND.md`, `TODO.md`, this file.

## Known divergences to decide on

Behaviour inherited from upstream in the 2026-10-04 sync that differs from what we had before:

- **Upstream's QPR3 `ZygoteHook` modern path** (`hookIntoZygoteProcessModern`) forces
  `bindMountAppsData` for pre-R top apps without checking the `forceMountData` setting, unlike its
  own legacy path. We took upstream's `ZygoteHook` unchanged in the 2026-10-04 sync.
- **Upstream dropped the `getPackageStates` hook.** Enumeration is now filtered by
  `shouldFilterApplication` alone. Feature 4 does not depend on it.
- **By-name lookups now run the original, then blank the result** (`hookAfter`), instead of
  skipping the original (`hookBefore`).
- **`applyPostResolutionFilter` filtering applies on Samsung too**, and trims One UI's input list.
- **A KernelSU manager/driver version mismatch only warns** during install; it no longer aborts.
- **Hook-install circuit breaker.** `BulkHooker.hooksWasCrashed`: once one hook crashes while
  installing, every later hook is skipped and the module keeps running partly protected. Our
  intent-query hook registers last on API 33+, so it is the first casualty. Look for
  `queryIntentActivitiesInternal bound` and `Invalid hook removed` in logcat after every flash.
- **Service version 102 → 105 and a changed `IFUQPService`** (`writeConfig`, `getLogs`,
  `readConfig` removed; `getUserProfiles` added). Flashing updates the manager app at once but the
  old module keeps running until reboot, so the manager misbehaves in that window. Judge a build
  only after rebooting.
- **We track upstream's pre-release branch** (`future`), whose own changelog promises "new bugs".
- The `stub` module's namespace is still `org.frknkrc44.stub` (compile-only, never ships).

Pre-existing, but worth fixing:

- **Every build has the same `versionCode` (7304649).** It counts commits on
  `refs/remotes/origin/master`, which we never update, so module managers and the `updateJson`
  checker cannot tell our builds apart.
- **Two long-lived branches.** Fixes made on `feature/fuqp-activity-launch-protection` after the
  sync do not reach `sync/upstream-future` on their own. Pick one as the main line.

## Sync log

| Date | Upstream commit | Branch | Notes |
|---|---|---|---|
| 2026-10-04 | `16e7276b` (upstream/future) | `sync/upstream-future` | First sync. Base `d1cfcbce`. Brings Android 17 QPR2 support, QPR3 Beta 1 fixes (AndroidVMTools `56dff5b6` via submodule, `ZygoteHook` `ProcessParams` path), installer hooks split into `InstallerHookTarget*`, rikka removal, AGP 9.4.1. 15 conflicts. The merge commit's claim that upstream deleted `ic_launcher_alt_5` is wrong: it was renamed to `alt_4` and the oversized path came back; fixed in the follow-up commit. |
