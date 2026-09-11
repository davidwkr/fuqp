# Opus handoff: hidden-package activity launch protection

## Task and boundaries

Diagnose, then fix explicit activity launches that reveal a package hidden from the
caller by FUQP. A filtered caller Y must receive the same result for a hidden
component of installed package X as for a nonexistent component:
`START_CLASS_NOT_FOUND`, surfaced by Android's client API as
`ActivityNotFoundException`. X must not launch. Do not substitute a permission
denial, `SecurityException`, silent success, or another distinguishable result.

This guarantee applies when `shouldHideActivityLaunch` returns true, respecting
the existing configuration and exclusions. Do not expand the task into blocking
all other package-existence channels or changing the package-hiding policy.

The user's reported bypass has not been reproduced in this session. Do not treat
the proposed cause or any AOSP-path claim below as device evidence.

Current phase: **superseded**. The diagnostic-only restriction below was lifted and an
implementation was delivered; see [ACTIVITY_LAUNCH_GUARD.md](ACTIVITY_LAUNCH_GUARD.md)
for what changed, the reproduction procedure, and what remains unverified. The
requirements, constraints and acceptance matrix in the rest of this document still
apply. The original restriction read: obtain the selected device and caller/target
fixture before device operations; do not implement a behavioral fix before diagnosing
the path; if runtime evidence is unavailable, deliver diagnostic instrumentation and a
precise reproduction plan, and clearly mark the implementation decision and runtime
acceptance as pending.

## Workspace context

- Repository: `/home/blackbox/Documents/repos/alt/fuqp`, branch `master`, observed
  HEAD `d1cfcbce`. Recheck before work.
- The working tree contains a large staged rebrand and additional unstaged
  renames. Preserve both; inspect the working-tree files, not just HEAD or index.
- Current service names are `FUQPService`, `FUQPServiceCache`, and
  `com.iodvd.fuqp.common.IFUQPService`. The old names may still exist in the index
  as pending deletions. Do not restore them.
- No commit, push, reset, bulk staging, install, flash, reboot, module activation,
  or device configuration changes as part of this review. Prepare a concrete
  diagnostic artifact before requesting any required deployment authorization.
- Launch provider workers only through Agent Smart (`aclaude`, `acodex`,
  `ajcode`), preserve usage guards, and check `aagent delegate-model` before
  delegation. Prefix delegated titles with the selected model.
- No existing test files were found by the initial filename scan; recheck before
  choosing a test approach. A textual assertion about hook registration is not
  evidence that the Android launch behavior is correct.

## Verified local facts and corrections to the initial hypothesis

1. `zygote/src/main/java/com/iodvd/fuqp/zygote/hook/ActivityHook.kt` registers
   `applyPostResolutionFilter` on non-Samsung devices, then installs the
   ActivityStarter fallback only if `isHookAvailable` is false.
2. `service/BulkHooker.kt:isHookAvailable` checks the registered hook list, but
   `addHook` adds an entry only when `applyHook` returns true. Disabled or failed
   hooks are not recorded. Thus "the availability check is always false after
   registration" is too strong: the fallback is suppressed after successful
   attachment. Attachment still does not establish invocation or effectiveness.
3. `applyHook` selects the first matching executable after sorting by parameter
   count, and stops after attaching one. A same-named overload may be the wrong
   path. Record the actual executable signature and compare it to the ROM.
4. `util/ZLUtils.kt` includes the instance receiver at frame argument zero;
   argument one is the first declared parameter. `frame.args` uses `dumpArgs`,
   which builds an array. Avoid introducing that mechanism on a new hot path.
5. The resolve hook currently treats frame argument one as `List<ResolveInfo>`
   and the first `Int` as the caller UID. It returns before filtering for an
   empty list or UID 1000. These assumptions need signature verification.
6. `util/ServiceUtils.kt:getCallingApps(uid)` explicitly returns an empty array
   for UID 1000. Removing only the resolve hook's system-UID early return would
   therefore not fix caller identification.
7. The resolve hook uses `shouldHideActivityLaunch`, and increments the **PM**
   counter for removed candidates. The ActivityStarter hooks increment the
   **activity-launch** counter. Preserve this distinction when defining counts;
   do not assume both currently increment the AL counter.
8. `FUQPService.kt:shouldHideActivityLaunch` already applies the global disable
   flag and per-app inversion after `shouldHide`. `shouldHide` also has existing
   caller/target, system-package, WebView/browser, and configuration exclusions.
9. `util/Logcat.kt` gates output using `errorOnlyLog`, `detailLog`, and debug-build
   checks. Normal logging formats messages and queues persistence. Missing logs
   alone do not prove the hook was not invoked.

Paths abbreviated above are relative to
`zygote/src/main/java/com/iodvd/fuqp/zygote/`.

## Runtime fixture and baseline

Multiple devices were discovered: Apollo `5f388655`, Motorola `ZY22GNT84N`, DM63
over USB `25536909276343` and TCP `192.168.100.118:5555`, and Waydroid
`192.168.240.112:5555`. This is discovery only, not device selection or evidence
that FUQP is installed, active, or compatible. Recheck and use serial-scoped ADB.

Record selected serial, ROM/build fingerprint, SDK, manufacturer, app and module
versions/hashes, loaded hook signatures, and InxLocker presence. Verify the active
module is the diagnostic build, not an older HMA-OSS/FUQP module. Coexisting module
IDs can confound the result; report them rather than silently changing them.

Choose a harmless exported test activity X that launches successfully from an
unfiltered caller, and a caller Y actually present in FUQP scope. Confirm Y's
package, UID, Android user/profile, effective hiding configuration, and normal
PackageManager query result. Record a nonexistent component control.

**Do not use plain `adb shell am start -n ...` as proof of an app-Y launch.** It
normally acts through the shell identity. `am --user` selects an Android user,
not the caller application's UID. Even a UID-switched shell command may retain
shell attribution or traverse a different API path. Prefer a minimal app-side
probe in Y that calls `startActivity` with an explicit `ComponentName`, catches
and reports the actual exception class, and has an unfiltered control caller.
If using a shell-assisted method for exploratory traces, verify the identity the
framework receives and label its limits. Avoid a permissions-related or background
launch restriction masking the result; run the fixture from a foreground activity.

## Temporary diagnosis before behavioral changes

Use bounded, opt-in diagnostic logging. Place entry diagnostics before existing
empty-list and system-UID returns. Keep them out of the production hot path.

Record only what is needed, without intent extras or unrelated app data:

- Exact attached method signature and SDK/ROM; validate parameter indices.
- `resolveForStart`, actual `filterCallingUid`, Binder calling UID, Android user,
  input candidate count, explicit component if available, and early-return reason.
- Effective caller package and whether FUQP scope/policy applies; filtered count.
- The final resolution/start result where needed to distinguish argument changes
  from what the original method actually returns.
- For relevant ActivityStarter entry points, request `callingUid`,
  `realCallingUid`, calling package, caller-token presence, explicit/resolved
  target, and the identity lifecycle across resolution. Capture only available,
  verified fields rather than assuming identical ROM layouts.

Verify the actual AOSP/ROM implementation for Q/R, S/S_V2 and T+ class placement,
resolve-for-start filtering, overloads, Binder identity clearing/restoration,
and special explicit-component branches. Cite source version and excerpts.

The original three cases are hypotheses, not an exhaustive decision tree:

- Correct UID and invocation, but launch succeeds: verify the policy returned
  true, the matching candidate was removed, the correct argument was replaced,
  and no later path restored/bypassed the result before choosing a start guard.
- UID_SYSTEM: identify the authenticated original caller from the start request
  and verified framework lifecycle. Do not simply trust a supplied package name
  or substitute Binder UID after identity has been cleared.
- No invocation: verify diagnostic visibility, attachment, overload selection,
  and actual start path. An absent callback does not by itself establish that
  unconditional fallback registration is the complete fix.
- Also check a callback with a wrong non-system UID, no scoped caller, false
  predicate, unexpected argument layout, or a swallowed hook exception. The
  wrapper logs callback exceptions and can then invoke the original method.

## Implementation constraints after diagnosis

- Choose the smallest fix supported by evidence; retain successful unfiltered
  behavior and existing policy semantics. Do not blindly remove the fallback gate.
- Preserve InxLocker method selection: `executeRequest` on R+, `startActivity`
  pre-R when InxLocker is present; `execute` otherwise. Verify pre-R hardcoded
  argument indices against the actual selected signature.
- Preserve the Samsung fallback where the resolve filter is unavailable. Do not
  infer Samsung runtime support from compiling a branch on a non-Samsung host.
- Return `START_CLASS_NOT_FOUND`, not a distinct error. Check how the framework
  maps that result to the client exception and compare against the nonexistent
  component baseline. Preserve normal ActivityStarter request cleanup/recycling
  and state accounting when returning before its original implementation.
- At `execute`, the request UID can require later framework resolution; verify
  caller-token and real UID behavior rather than using an unresolved UID as an
  Android user. Distinguish calling user from target user in cross-profile cases.
- Maintain the existing policy for shared UIDs and system-mediated starts; do not
  introduce a package-string spoofing bypass or block trusted launcher/Recents
  flows by inventing an attribution rule.
- Bail out early for absent service, unscoped caller, or no relevant explicit
  target where appropriate. Do not add array/list building, per-launch reflective
  method discovery, package-manager IPC, disk I/O, or unconditional logging to the
  ordinary start path. Assess existing predicate and counter costs separately;
  do not claim the entire inherited path is allocation-free.
- One blocked launch must not increment `increaseALFilterCount` twice. Define
  which stage owns the count if both hooks are present, and keep PM candidate
  counts distinct. Avoid persistent global deduplication state or timing heuristics.
- Remove temporary diagnostics before final delivery, or leave an explicitly
  disabled diagnostic mechanism with no ordinary hot-path work.

## Acceptance and evidence

1. Scoped caller Y hiding installed X: explicit starts of exported fixture
   components/aliases throw `ActivityNotFoundException`; compare against the
   nonexistent control. Confirm no target lifecycle launch or foreground change.
2. Unfiltered control caller: the same component succeeds.
3. Launcher icons, share sheets, deep links, and Recents retain expected behavior
   for unfiltered callers. Include same-app starts and a cross-profile case when
   available; report unavailable coverage.
4. Verify all four flag combinations for an otherwise hidden target:

   | disableActivityLaunchProtection | invertActivityLaunchProtection | Block |
   | --- | --- | --- |
   | false | false | yes |
   | false | true | no |
   | true | false | no |
   | true | true | yes |

5. Cover both InxLocker selections and supported API-specific branches using
   matching framework evidence and available runtimes. Keep host compilation,
   hook attachment, and end-to-end device outcomes separate, especially Samsung.
6. Check AL and PM counter deltas around isolated launches and ensure no duplicate
   AL count. Verify temporary logs are removed/disabled in the final build.

Build sequentially; the combined invocation previously failed because the
Zygote task checked for the manager APK before it existed:

```sh
./gradlew :app:assembleDebug && ./gradlew :zygote:assembleDebug
```

`local.properties` exists in this workspace. Configuration fetches translator
data from upstream and requires network access. Both debug builds passed after
the rebrand, with existing resource/compiler diagnostics; those results do not
validate this security behavior. Keep unrelated warnings out of this patch.

Deliver: verified root cause with trace/source evidence; focused patch; tests or
reproducible probe steps; build results; runtime acceptance matrix; known gaps.
The primary agent independently reviews the patch and verifies results before
calling the fix ready. No deployment or security acceptance claims from a worker
summary alone.
