# Querier-side UID/process-liveness leak

Design notes for closing the one detection vector FUQP's current architecture
*structurally* cannot reach: a querier learning that a **hidden app's process is alive**
by probing the kernel directly, independent of PackageManager.

**Status: design only. No code written.** Two approaches are documented: a near-term
system_server-side **synthetic decoy** behind a toggle (cheaper, reversible, hard ceiling),
and the complete fix that extends FUQP into querier processes to filter the kernel liveness
side channels (bigger project). The decoy is the recommended first step, gated on a device
re-scan; the querier-side plane is the fallback for detectors it cannot satisfy.

## The leak, concretely

Observed 2026-10-26 on apollo (bc0d61b6, LineageOS / Android 17) with Duck Detector
(`com.eltavine.duckdetector`, build `36f6b558cee4`). Its "Dangerous Apps" check reported:

> getpriority UID visibility: **Behavior mismatch observed**. 10000/10000 ordinary app
> UIDs queried; 34 task UID(s), 3 repeatedly lacked a visible package association. … This
> does not identify a hidden package or HMA. UIDs: 10169, 10241, 10359.

Mapping those UIDs:

| UID   | Package                 | Hidden from the detector by FUQP?            |
|-------|-------------------------|----------------------------------------------|
| 10241 | `ru.mike.updatelocker`  | **Yes** — in the `HIDE ROOT` template        |
| 10359 | `com.nll.helper`        | **Yes** — in the `HIDE MY CUSTOM APP` template |
| 10169 | `org.omnirom.omnijaws`  | No — genuine OEM/multi-user false positive   |

So 2 of the 3 are FUQP's doing, and they **cannot be un-hidden** — removing them from the
templates makes other apps in the user's setup find Xposed. The false positive (`omnijaws`)
is unrelated and needs nothing.

### Mechanism

The detector runs, in **its own process**:

1. `getpriority(PRIO_PROCESS, uid)` swept across the whole app-UID range. This is a raw
   libc syscall to the kernel; it returns a nice value for any UID that has a live process
   and `ESRCH` otherwise. **No Binder, no IPC — system_server never sees it.**
2. For each live UID, a PackageManager reverse lookup (`getPackagesForUid` /
   `getNameForUid`) to name the owner.

FUQP hooks step 2 (`PmsHookTarget33.getPackagesForUidMethod`, `ExactNameLookup`) and
correctly returns nothing for the hidden apps. But their processes are alive (an
update-locker service and an accessibility helper — both persistent), so step 1 still sees
the UID. The detector pairs the two and concludes: **live UID + no resolvable package =
something is being hidden.**

The mismatch is caused by hiding *cleanly on the framework side while the kernel still
reports the process.* It cannot be closed by hooking more of PackageManager.

## Why there is no system_server-side fix

The two facts live in two places:

- **Kernel side** (`getpriority`): truth, in the querier's process. Unreachable from
  system_server.
- **Framework side** (`getPackagesForUid`): FUQP controls it, and it's already hiding.

The only framework-layer moves both fail the threat model:

- **Reveal the UID→name** (let `getPackagesForUid` resolve) → removes the mismatch but
  un-hides the app → it finds Xposed. Rejected.
- **Return a decoy name** → removes the mismatch, and if the decoy is synthesised
  *coherently* (not just the reverse lookup) it keeps the app hidden. It does **not** make
  the kernel-side half honest, so it can only ever satisfy detectors that stop at the
  PackageManager reverse lookup — and it has an unfixable filesystem residual. This is a
  legitimate *near-term* mitigation with a hard ceiling, not a complete fix; see
  [Alternative (near-term): system_server-side synthetic decoy](#alternative-near-term-system_server-side-synthetic-decoy).

The *complete* fix keeps the app hidden *and* removes the mismatch, which requires making
the kernel-side half lie too — i.e. executing in the querier's process (below).

## Design: run in the querier process and filter the liveness side channels

FUQP's loader (`com.v7878.zygisk.ZygoteLoader`) is currently pinned to
`packages(ZygoteLoader.PACKAGE_SYSTEM_SERVER)` in `zygote/build.gradle.kts`. The same
loader targets ordinary app packages, so this is an **extension, not a rewrite**: add a
second injection plane that runs in selected app processes and intercepts the
process-liveness side channels, returning "no such process" for UIDs the caller must not
see.

### Hook points (patch the whole family, not just `getpriority`)

Patching only `getpriority` just moves the detector to the next probe. The liveness
side channels that must be filtered together:

| Surface                                   | Leak                                            | Intercept approach |
|-------------------------------------------|-------------------------------------------------|--------------------|
| `getpriority(PRIO_PROCESS/PRIO_USER, uid)`| nice value vs `ESRCH` reveals UID liveness      | native libc hook; return `ESRCH` for hidden UIDs |
| `kill(uid/pid, 0)`                        | `0` vs `ESRCH` reveals liveness                 | native libc hook |
| `/proc` readdir + `/proc/<pid>/status` (`Uid:`) | enumerate pids, map to UID                 | hook `readdir`/`openat`/`stat` on `/proc`; drop hidden-UID pids |
| `/proc/net/*`, `/proc/<pid>/cgroup`       | socket UID owners, cgroup paths                 | same `/proc` filter |
| `android.os.Process.getUidForName` / `myUid` tricks | name→uid and uid liveness             | ART-level hook in the querier |
| `ActivityManager.getRunningAppProcesses` / `getRunningServices` | process list (Binder, but evaluated per-caller) | can stay in system_server OR mirror here |

The native hooks go through the v7878 FFI stack already on the classpath (`AndroidVMTools`,
`r8annotations`); inline-hooking libc is feasible there but ABI- and arch-specific
(arm64-v8a, armeabi-v7a, x86, x86_64 — all four ship in the zip).

### Config plumbing

The per-app hidden set already lives in the service (`FUQPService`, `config.scope[caller]`
→ templates/presets → resolved hidden package list). The querier-side plane needs, for its
own package, the **set of hidden UIDs** (not names — resolve names→UIDs once at injection,
refresh on package events):

- Querier process asks the service (existing AIDL `IFUQPService`, reached via the
  ContentProvider handshake) for "the UIDs I must not see," or the service pushes it.
- Cache in-process; invalidate on `PACKAGE_ADDED/REPLACED/REMOVED` (UIDs are stable per
  install but change on reinstall).
- **Both planes must agree.** The system_server PMS filter and the querier syscall filter
  must hide the *same* set for a given caller, or you manufacture new mismatches (e.g.
  UID hidden at syscall level but name resolvable, or vice-versa).

### Trigger: tie it to prober-watch, don't inject everywhere

Injecting into every app process is a large, always-on cost and risk. Prefer:

- Inject only into **configured queriers/detectors** (the apps that already carry hide
  templates — the detector in this report has `HIDE ROOT` + `HIDE MY CUSTOM APP`), or
- Inject broadly but keep the hooks **dormant** until prober-watch observes that process
  sweeping the UID space / reading `/proc` en masse, then arm the filter.

prober-watch (see the behavioural-monitoring design discussion) is the natural source of
"this caller is scanning" — it identifies the querier so the filter is applied narrowly.

## Costs and risks (why this is a project, not a patch)

- **Untrusted process injection.** Crashes in a hooked third-party app become FUQP's
  crashes; perf overhead on every hooked process; some apps have their own integrity/anti-
  tamper that may react to an injected `.so` or patched libc.
- **Native inline hooking** of libc across four ABIs from the Java/FFI stack is fiddly and
  version-sensitive; a bad hook can hang or crash the host app.
- **Two hiding planes to keep consistent** (system_server PMS view + per-app syscall view).
  Divergence creates *new* detectable mismatches — the consistency invariant is the hard
  part, not the individual hooks.
- **Scope creep in side channels.** `getpriority` is one of many; a half-done filter that
  patches it but not `/proc` or `kill` leaves an equivalent leak and wastes the injection
  cost.

## Recommendation / phasing

This is the detector's **lowest-confidence tier** ("does not identify a hidden package or
HMA"), fires only for apps with a *live process*, and benign causes produce it too (the
`omnijaws` false positive). The real targets (banking apps) are not persistently running,
so they do not leak unless open. It is defensible to carry this as a known residual while
the querier-side plane is built carefully.

Suggested order:

1. **Prototype (throwaway)** — inject into `com.eltavine.duckdetector` only, native-hook
   `getpriority` alone, filter the two known hidden UIDs, re-scan. Confirms the injection +
   native-hook stack works end-to-end on this device/kernel (5.10) before committing to the
   full surface. (This is option (b) from the discussion; it informs, it does not ship.)
2. **Side-channel completeness** — add `kill(,0)`, `/proc` readdir/stat, `/proc/net`
   filtering so a pivot to the next probe doesn't reopen the leak.
3. **Config + consistency** — wire the per-caller hidden-UID set from the service, enforce
   the two-plane consistency invariant, invalidate on package events.
4. **Trigger** — gate injection/arming on prober-watch instead of blanket injection.

### Interim, no-code lever

The leak needs a **live** process. If `updatelocker` / `nll.helper` do not need to run
during a scan, freezing **and killing** them (not just hiding) removes their UID from
`getpriority` entirely. Usage workaround, not a FUQP change, and only viable if they can be
idle during scans.

## Alternative (near-term): system_server-side synthetic decoy

Instead of making the kernel-side half lie (which forces querier-process injection), make
the **framework-side half stop being empty**: for a configured caller, map each hidden UID
to a plausible, Play-sourced **decoy package** so the reverse lookup resolves to a normal
app and the "live UID, no package" mismatch disappears — while the real app stays hidden.

This stays entirely in system_server (no untrusted-process injection, no native hooks,
reversible), so it is far cheaper and lower-risk than the querier-side plane. Its ceiling is
real and must be understood before building it.

### Why it is not just "return a decoy name"

FUQP hides by forcing Android's own `AppsFilter.shouldFilterApplication` gate to filter the
package — which hides it across *every* PackageManager surface at once (that is why all 14
visibility probes read 0). That gate can only **hide**; a decoy must **synthesise**, which
is new machinery. A decoy is only safe if it is coherent across every surface a detector can
cross-check, or it trades a harmless signal for a worse one:

1. **Reverse lookup** — `getPackagesForUid(uid)` / `getNameForUid(uid)` → decoy. This alone
   removes the specific `getpriority` warning. Easy — a `hookAfter` rewrite of the (now
   empty) result for that caller.
2. **Forward lookup must agree.** If the detector then calls `getPackageInfo(decoy)`:
   - decoy not installed → `NameNotFoundException` → **new, stronger** "UID names a package
     that doesn't exist" mismatch.
   - decoy actually installed → it has its own real UID → **one package under two UIDs**
     contradiction.

   So `getPackageInfo` / `getApplicationInfo` / `getInstalledPackages` /
   `getInstallerPackageName` (→ `com.android.vending`) / signatures must all be **synthesised
   coherently** for the decoy, for that caller. A partial job is worse than nothing.
3. **Filesystem residual — unfixable from here.** The detector also does `Open APK FD`,
   `Android/data listing`, `native /data/data stat` — `open()`/`stat()` in the *querier's*
   process against the real disk. system_server cannot intercept them. A detector that
   escalates from the decoy name to the filesystem finds no `/data/data/<decoy>`, or an APK
   whose manifest names the real hidden package → contradiction. This is the same wall that
   pushed the complete fix into the querier's process.

### Net

- **Naive UID-liveness detector** (pairs `getpriority` with the reverse lookup only): decoy
  defeats it cleanly. Many detectors are this naive.
- **Thorough detector** (name↔UID uniqueness + `getPackageInfo` + filesystem, e.g. Duck
  Detector): risks swapping a low-confidence signal it won't act on for a higher-confidence
  contradiction it might. **Net value is detector-specific and only measurable by re-scan.**

### Design if built

- **Default-off, per-app (per-querier) toggle**, same shape as `spoofAndroidId`
  (`JsonConfig.AppConfig` flag + auto-protect rule + UI).
- **Coherent synthetic decoy**, not just the reverse lookup — fake `getPackagesForUid`,
  `getNameForUid`, `getPackageInfo`, `getApplicationInfo`, `getInstalledPackages` membership,
  installer → `com.android.vending`, and a present (fake) signature, so there is no
  forward/reverse or name↔UID mismatch.
- **Decoy selection:** plausible, Play-sourced, **not actually installed** (avoids the
  double-UID collision) and **not in common detector bad-lists** (reduces the chance of a
  filesystem escalation). Stable per hidden-UID (salted, like the android_id mapping) so it
  does not flicker across queries/reboots. Never collide two hidden UIDs onto one decoy.
- **Validate per-detector by device re-scan** as an explicit go/no-go: does the `getpriority`
  warning drop, or does an APK/datadir probe now bite? If it bites, fall back to the
  querier-side plane above.

### Decoy vs querier-side injection

| | Synthetic decoy (this section) | Querier-side injection (above) |
|---|---|---|
| Layer | system_server only | also the querier's process |
| New risk | low — reversible PMS rewrite | high — untrusted-process injection, native hooks |
| Effort | moderate (synthesise PMS surfaces) | large (injection + libc/`/proc` hooks + 2-plane consistency) |
| Defeats naive detectors | yes | yes |
| Defeats thorough detectors | no (filesystem residual) | yes |
| Verdict | good near-term, hard ceiling | complete fix, bigger project |

Recommended path: ship the decoy behind a default-off toggle as the near-term mitigation,
gate acceptance on the Duck Detector re-scan, and keep the querier-side plane as the
complete fix for detectors the decoy cannot satisfy.

## References

- `zygote/.../hook/PmsHookTarget33.kt` — `getPackagesForUidMethod`, the framework-side
  reverse-lookup filter that is working as intended here.
- `zygote/.../hook/ExactNameLookup.kt` — by-name lookup gate.
- `zygote/build.gradle.kts` — `zygisk { packages(...) }`, the loader target list to extend.
- `zygote/.../ZygoteEntry.java` — current (system_server-only) entrypoint.
- Duck Detector report `duck_detector_report_M2007J3SY_20261026_223203.txt` — the source
  observation (full 14-probe package-visibility sweep returned 0 hits; this UID liveness
  mismatch is the lone residual).
