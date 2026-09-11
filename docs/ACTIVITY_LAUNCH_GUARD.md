# Hidden-package activity launch guard

Implementation notes for the guarantee stated in
[ACTIVITY_LAUNCH_HANDOFF.md](ACTIVITY_LAUNCH_HANDOFF.md): when
`FUQPService.shouldHideActivityLaunch` returns true for caller Y and target X, an
explicit start of X by Y must fail with `START_CLASS_NOT_FOUND` — the same result Y gets
for a component that was never installed — and X must not run.

**No device acceptance has been run.** The regression suite in
[`tests/activity-launch`](../tests/activity-launch/README.md) is the verification; only
its host acceptance-logic tests have executed so far.

## Source evidence

Quoted line numbers are from the AOSP tags named; all of it was read from local copies of
`android.googlesource.com/platform/frameworks/base` at those tags.

| Fact | Source |
| --- | --- |
| `applyPostResolutionFilter(List, String, boolean, int filterCallingUid, boolean resolveForStart, int userId, Intent)` | `ComputerEngine.java` 1213-1216, android-16.0.0_r1 |
| It mutates the list it is handed (`resolveInfos.remove(i)`, `resolveInfos.set(i, …)`) and returns it | same, 1222-1299 |
| Explicit components **do** reach it: the `comp != null` branch falls through to `return skipPostResolution ? list : applyPostResolutionFilter(…)`, and `skipPostResolution` is only set in the implicit branch | same, 542-594, 602, 626-628 |
| A component matching nothing yields `list = Collections.emptyList()`, because the branch body is guarded by `if (ai != null)` on `getActivityInfo(comp, flags, userId)` | same, 540-544 |
| For an exported target with `resolveForStart`, AOSP's own visibility filter is skipped: `blockNormalResolution = (!resolveForStart \|\| resolveForStartNonExported) && …` | same, 577-586 |
| `execute()` resolves only when nothing is resolved yet: `if (mRequest.activityInfo == null) mRequest.resolveActivity(mSupervisor);` | `ActivityStarter.java` 747-749, android-15.0.0_r1 |
| `execute()` ends in `} finally { onExecutionComplete(); }` | same, 828 |
| `executeRequest` reads `aInfo = request.activityInfo` and maps a null one to `START_CLASS_NOT_FOUND` | same, 946, 1049-1051 |
| …then runs the framework's own cleanup: `resultRecord.sendResult(… RESULT_CANCELED …)`, `SafeActivityOptions.abort(options)`, `return err` | same, 1107-1114 |
| `Request.callingUid` is **-1 at `executeRequest`** for ordinary app starts: `resolveActivity` sets `callingPid = callingUid = -1` when `caller != null`; the authoritative uid is taken later from `callerApp.mInfo.uid` | same, 541-548, 968-979 |
| `callingPackage` is authenticated against the Binder uid on the normal start entry points: `assertPackageMatchesCallingUid` throws `SecurityException` unless `isSameApp(Binder.getCallingUid(), packageName)` | `ActivityTaskManagerService.java` 2300-2309, called at 1241, 1275 (`startActivityAsUser`), 1563, 1591, 1727, 1759, 1844, 2236, 2704 — android-15.0.0_r1 |
| Pre-R `startActivity` has three overloads: 26 params (wrapper, 566), 25 params (the one holding the error logic, 611), and 10 params starting with `ActivityRecord` and carrying **no Intent** (1386) | `ActivityStarter.java` 566-596, 611-619, 1386-1389, android-10.0.0_r1 |
| The 25-param overload holds the same error logic and cleanup as later `executeRequest` | same, 695-705, 751-758 |

Also independently verified in android-15.0.0_r1: `ParsedComponentUtils.java` 47-50
rejects an empty `android:name`; `Instrumentation.java` 2422-2434 maps
`START_CLASS_NOT_FOUND` to `ActivityNotFoundException`, using the caller's original
component in the message, and maps `START_PERMISSION_DENIED` to `SecurityException`.

## What changed

All in `zygote/.../hook/ActivityHook.kt` unless noted.

### 1. Blocked starts are deflected, not short-circuited

The guard no longer replaces the return value of the hooked method. On a hit it rewrites
the request so the framework's own not-found path runs:

- the intent is replaced with a copy pointing at `ComponentName("", "")`, with its
  package restriction cleared,
- `activityInfo` and `resolveInfo` are cleared.

At `execute` this happens before resolution, so `resolveActivity` runs and finds nothing.
At `executeRequest` and at the pre-R `startActivity` the resolution has already happened,
so clearing `activityInfo` is what drives the branch. Either way `err` becomes
`START_CLASS_NOT_FOUND` inside the framework, `resultRecord.sendResult(… RESULT_CANCELED …)`
and `SafeActivityOptions.abort(options)` run, metrics and `getExternalResult` behave
normally, and `execute()`'s `finally { onExecutionComplete() }` recycles the starter.

Why an empty class name: `getActivityInfo` is a lookup keyed by `ComponentName`, so the
start can only be deflected if nothing can ever carry that name. An empty `android:name`
is rejected at parse time, so no installed package — hostile or not — can declare it. A
randomly generated name would only be improbable, and a fixed readable name could be
declared by the target itself. Clearing the package as well avoids identifying the
hidden package to Android 15's archived-app recovery branch (`ActivityStarter.java`
1053-1062), which can replace a not-found error with an unarchive request. The caller
still receives the normal explicit-component not-found exception for its original intent.

The substitution cannot change what the caller sees: the server returns an `int`, and the
client builds `ActivityNotFoundException` from *its own* `Intent` instance in its own
process, which this never touches.

Field metadata is resolved at load time; unsupported field layouts cannot load this
guard. If a write or its verification unexpectedly fails after loading, the guard falls back to
replacing the return value with `START_CLASS_NOT_FOUND`, logs a warning, and thereby
re-incurs the skipped cleanup. Failing closed is preferred to failing open. The rewrite is
verified by reading the fields back, so a partial rewrite also takes the fallback.

### 2. The ActivityStarter guard is no longer conditional

It used to be installed only when the `applyPostResolutionFilter` hook had failed to
attach, which in practice meant Samsung and nothing else. Both are now installed.

They are not redundant. On android-16 the resolve filter *does* see explicit components
(sources above), so where it attaches and works it already removes the candidate and the
start guard agrees with it. But it is a filter over resolution candidates: it depends on
the ROM routing explicit components through it, on the caller consuming its return value,
and on the hook having attached to the right overload. The start guard does not depend on
any of that. `BulkHooker.isHookAvailable` only ever proved attachment, never effect, so
using it to suppress the guard traded a guarantee for an assumption.

The InxLocker-based selection is unchanged: `executeRequest` on R+ and `startActivity`
pre-R when InxLocker is installed, `execute` otherwise.

### 3. Pre-R overload selection is validated, not guessed

`BulkHooker.applyHook` attaches to the *lowest-arity* overload of a name. For
`ActivityStarter.startActivity` on Android 10 that is the 10-parameter overload at line
1386, which begins with `ActivityRecord` and has no `Intent` at all — so the old hook
attached to the wrong method, and reading "argument 13" there could never have worked.

A new `BulkHooker.findParamCount(clazz, method) { parameterTypes -> … }` picks the
lowest-arity overload whose declared parameter *types* match, and the result is passed as
the hook's `paramCount`. The guard requires `Intent`, `ActivityInfo`, `ResolveInfo`,
`int`, `String`, `int` at declared indices 1, 4, 5, 12, 13, 15 — receiver-inclusive frame
indices 2, 5, 6, 13, 14, 16 — which hold for both the 25- and the 26-parameter overload.
If nothing matches, the hook is not installed and a warning is logged, rather than
attaching to whatever happens to be shortest.

This also removes the reliance on `frame.args.firstOrNullWithType<String>()`, which
selects `resolvedType` rather than `callingPackage` whenever an explicit start carries a
MIME type.

### 4. Caller attribution and calling user

The caller is `Request.callingPackage`, and an unscoped one exits immediately on a
`HashMap.containsKey`. That string is not merely trusted: `assertPackageMatchesCallingUid`
rejects a package the Binder caller does not own on every normal start entry point
(sources above). The residual gap is shared uids — the assertion accepts any package owned
by the uid, so a scoped app sharing a uid with an unscoped one can present the latter.
That matches the pre-existing policy elsewhere in the module and is called out in the
suite's notes rather than papered over.

The previous revision of this patch resolved the caller from
`IPackageManager.getPackagesForUid` whenever the hint was not in scope. That put a package
query and an array scan on every unscoped cross-package explicit start — new work on every
non-Samsung device — and it authenticated nothing, since membership in `config.scope` is
configuration, not identity. It is gone, along with the same-package fast path it needed.

For the calling user the guard reads `callingUid`, then `realCallingUid`, then
`Binder.getCallingUid()`. All three are needed: `Request.resolveActivity` sets
`callingUid` to -1 whenever `caller != null`, so the field is unresolved at `execute`
*and* at `executeRequest`, and `getUserFromCallingUid` (`uid / 100000`) would silently turn
that into user 0 and evaluate a secondary user's or work profile's start against user 0's
policy. Entry points that clear Binder identity before `execute` — `startAssistantActivity`
at `ActivityTaskManagerService.java` 1763 is one — set `callingUid` explicitly, so the
Binder fallback is only reached where identity has not been cleared. That ordering is
argued from these two call sites, not proven exhaustively across every entry point.

### 5. The replacement resolve list stays mutable

`frame.setArgument(1, filteredList.toList())` → `frame.setArgument(1, filteredList)`.
`applyPostResolutionFilter` removes and replaces entries in the list it is handed, and
Kotlin's `toList()` returns an immutable `Collections.singletonList` for exactly one
survivor, which would have thrown `UnsupportedOperationException` out of the original
method and into the resolution path. `filter` already returns an `ArrayList`.

### 6. Diagnostics, compiled out

`private const val DIAG = false`. Being a compile-time constant, every `if (DIAG)` branch
is dropped, so nothing is paid while it is false — not even the lambda allocation a
runtime-gated `logD` still costs. The pre-existing unconditional `logV` in the
`checkStartAnyActivityPermission` no-op hook is now behind the same flag; it called
`frame.args`, and `ZLUtils.args` is a `lazyWithReceiver`, i.e. a globally synchronised
`WeakHashMap` lookup on every activity start in a debug build.

Set it to true and rebuild to get, per resolution: candidate count, `filterCallingUid`,
`resolveForStart`, `userId`, the Binder uid, the explicit component, and the reason for
each early return. Per start: caller, resolved uid, Binder uid and target component.
Argument extraction is wrapped, so an unexpected signature prints the frame's parameter
count instead of throwing. No intent extras and no unrelated app data are logged.

### 7. Helpers

- `ActivityHook.RequestFields` caches reflective fields at load time for subsequent
  reads and blocked-request writes.
- `ZLUtils.setArgument` now takes `Any?`, so a reference argument can be cleared.
- `BulkHooker.findParamCount` — new; type-matched overload selection at load time.

## Cost on the ordinary start path

1. `service` null check.
2. Read `mRequest` for the `execute` variant, then `callingPackage`, using cached fields.
3. `isHookEnabled` — one `HashMap.containsKey`; unscoped callers stop here, which is
   every start on a device with no configuration and nearly all of them otherwise.
4. Only then the intent read, the component check and the uid read.

Nothing is added that queries PackageManager, touches disk, discovers methods per launch
or logs unconditionally. Reflective field reads still have a cost, but field discovery
and overload selection happen once at load time. Unscoped starts perform no UID lookup
or intent copy. A blocked start copies its intent; the impossible component is reused.

Inherited costs this patch does not touch: `frame.args` in the resolve filter (a
`dumpArgs` array plus the synchronised map lookup per resolution), the non-inline `logV`
/`logD` lambdas, and `increaseFilterCount` writing `filter_count.json` every 100 counts.

## Counter ownership

- **PM counter** — the resolve filter, once per removed candidate. Unchanged.
- **AL counter** — the start guard, once per blocked start.

Exactly one starter hook is ever installed, so a blocked launch increments AL once. A
launch the resolve filter already neutralised will now also be counted by the guard, so
one event can appear in both counters; they measure different stages and the handoff asks
that they stay distinct. No deduplication state, no timing heuristic.

## Verification

`tests/activity-launch` (owned by the primary) is the suite: two caller APKs with
different real UIDs plus a target APK, instrumentation running inside each caller's
process, and a host runner that checks the reported UID against PackageManager and reads
the target's lifecycle markers through `run-as` rather than trusting FUQP's own logs. It
covers three exported activities (including an explicit MIME-type intent), an alias,
a private activity, a missing class and an absent package, from the filtered and the
control caller — 14 cases for each of the four
flag states. `adb shell am start` is not used as a caller identity.

Two expectations bear directly on this patch: the private target must yield
`ActivityNotFoundException` rather than the ordinary `SecurityException` when hidden, and
a protected start must never record the target's `onCreate` or resume it.

Host acceptance-logic tests pass. Nothing has been run on a device.

## Open items

1. **Root cause still unconfirmed.** The reported bypass was never reproduced here and no
   device was selected. Since android-16 does route explicit components through
   `applyPostResolutionFilter`, the most likely explanations are attachment to the wrong
   overload, a vendor tree that differs, or the filter running without its result being
   what the start path consumes. The fix does not depend on which.
2. **Client behavior still needs device confirmation**, including the original component
   in the exception and a message matching the missing-component control. The parser and
   exception mapping were independently verified in source, as recorded above.
3. **Deflection is verified by read-back, not by a device.** That the framework then takes
   the `aInfo == null` branch is read from android-10 and android-15 sources; a vendor
   tree could differ, in which case the fallback path applies.
4. **Overload selection should be confirmed on the ROM under test** — record the
   `Selected overload:`/`Hooked:` lines and the output of `getLoadedHooks`.
5. **Samsung** keeps the start guard only; the resolve filter is skipped by design there.
   Host compilation, hook attachment and device outcome are three separate results and
   should be reported separately.
