# TODO

- [ ] **Default profile for newly installed apps.** Add an opt-in setting that
  automatically adds newly installed apps to FUQP's filtering scope and applies a
  user-selected default profile, including which packages they cannot see and the
  activity-launch protection settings. Allow editing the default profile and
  overriding it per app. Do not overwrite existing app configurations or reapply
  defaults on app updates. Cover enabled/disabled behavior, fresh installs,
  updates, and per-app overrides with tests.

## Intent-query filtering: unverified fix

Review scope: the uncommitted `PmsHookTargetBase.kt` change on
`feature/fuqp-activity-launch-protection`. The new `hookAfter` filters the results
of the nine-parameter `ComputerEngine.queryIntentActivitiesInternal` overload
using `filterCallingUid` and the existing `applyPackageHiding` policy.

### Reported device evidence

Device: Xiaomi apollo, Android 17 / API 37. Detector: `com.chunqiunativecheck`.
The following A/B observations were supplied by the user, not independently
reproduced during review:

| Package | Hiding OFF | Hiding ON |
|---|---|---|
| `com.kowx712.supermanager` | `gpi,intent,intent-profile,receiver` | `intent,intent-profile` |
| Termux | `pm` | Not reported |
| xlua | `pm` | Not reported |
| `io.github.chsbuffer.revancedxposed` | `gpi,intent` | `intent` |

The user reported a `do_filp_open` + `filename_lookup` kprobe trace containing
47,403 events and 35,391 distinct paths, with no filesystem references to either
leaking package and `/proc` access limited to self. This supports absence of
discovery through those observed filesystem probes; it does not prove that
Binder/PackageManager is the only possible discovery channel.

The candidate was reportedly built and flashed as `+33-release`, but the phone
had not rebooted afterward and detector hiding remained OFF from the A/B test.
Those observations therefore do not establish whether the candidate works.

### Findings and required changes

- [ ] **Preserve launch-protection controls.** The new hook uses ordinary package
  hiding even when `resolveForStart` is true, bypassing
  `disableActivityLaunchProtection` and per-app `invertActivityLaunchProtection`.
  Preserve the existing launch policy on start-resolution calls while continuing
  to filter ordinary queries. Test all four global/inversion combinations and
  retain the original not-found behavior for protected launches.
- [ ] **Replace the asserted UID diagnosis with measured evidence.** The existing
  `PmsHookTarget34` AppsFilter hook already reads the explicit `callingUid`
  argument, not `Binder.getCallingUid()`. Execution inside `system_server` does
  not establish that this argument is 1000. Examined AOSP code also skips
  AppsFilter on some paths, including `resolveForStart`; this is an alternative
  hypothesis, not a demonstrated cause on this ROM. Correct the comments that
  currently present the system-UID explanation as fact.
- [ ] **Verify the live API 37 signature.** Frame index 5 correctly selects
  `filterCallingUid` for the documented instance-method signature
  `(Intent, String, long, long, int, int, int, boolean, boolean)`, with the receiver
  at index 0. That signature was checked against AOSP Android 15, not the phone's
  framework. Capture the actual bound method and validate parameter types and
  meanings, not just arity. Android 14 uses eight parameters, so the current
  nine-parameter restriction misses it; that compatibility gap does not explain
  this API 37 device's behavior.
- [ ] **Inspect intermediary results.** Ordinary activity and activity-alias
  results are covered by `activityInfo.packageName`. The service/provider
  fallbacks do not make this hook intercept service or receiver queries.
  Installer and cross-profile forwarding results can name an intermediary
  instead of the hidden package. Inspect actual returned components and relevant
  metadata before claiming that `intent-profile` is covered.
- [ ] **Keep the common path inexpensive.** The current implementation allocates
  a list for every nonempty result even when no entry is hidden, including system
  callers. Bail out early where policy permits and avoid unnecessary copies,
  repeated caller lookup, and diagnostic I/O on ordinary queries/start paths.

### Static checks completed and limits

- This registration binds one matching nine-parameter method; it does not also
  bind shorter overloads. No other `queryIntentActivitiesInternal` registration
  was found in the checkout. Other filters still run, but entries already
  removed cannot be removed again. Live duplicate registration/re-entry and
  counter behavior still need checking.
- The replacement is a non-null, mutable `ArrayList` preserving retained entries
  and their order. No nullability or mutability defect was found in this change.
- Existing pm/gpi/receiver hooks are unchanged. This is not runtime proof that
  those vectors remain filtered or that normal intent resolution is unaffected.
- `./gradlew :zygote:compileDebugKotlin --offline` succeeded during review with
  all tasks up-to-date. This verifies the existing build state, not a fresh
  compilation, successful live hook attachment, or the flashed artifact.
- Five host tests in `tests/activity-launch` passed during the preceding review.
  They check the test runner's acceptance logic, not this query hook or the
  detector's discovery paths.
- No ADB device was connected during review. The live API 37 method signature,
  loaded module version, and candidate's detector results remain unverified.

### Device verification checklist

- [ ] With explicit device-operation approval, identify the phone/ROM and confirm
  the candidate artifact, reboot to load it, and verify the running module
  version and successful hook attachment. Scope ADB commands to that device.
- [ ] Capture temporary diagnostics during the detector run: exact bound method,
  invocation count, `filterCallingUid`, Binder UID for comparison,
  `resolveForStart`, target user/profile, and candidate/retained component
  packages. Correlate with the existing AppsFilter hook to distinguish skipped
  calls from calls discarded for UID 1000. Remove/disable diagnostics afterward.
- [ ] Enable hiding for `com.chunqiunativecheck`, force-stop and relaunch it, then
  expand the Risk apps card. Assert that `intent` and `intent-profile` no longer
  expose `com.kowx712.supermanager` or `io.github.chsbuffer.revancedxposed`.
- [ ] Repeat the full hiding OFF/ON comparison, including pm/gpi/receiver,
  clearing stale detector results by relaunching between states. Verify a
  non-filtered control caller still sees the expected packages/results.
- [ ] Run explicit-launch acceptance tests under the actual caller app UID, not
  merely `adb shell am start`. Protected launches must fail with
  `ActivityNotFoundException`/`START_CLASS_NOT_FOUND`, never a distinct
  `SecurityException`, and must not launch the target. Verify disabled and
  inverted protection states still permit the appropriate launches.
- [ ] Check launcher icons, share sheets, deep links, Recents, cross-profile
  results, and filter counters. Confirm no duplicate activity-launch counting
  and retain Samsung/InxLocker compatibility. Do not treat a passing detector
  card or a successful build as coverage of these separate cases.
