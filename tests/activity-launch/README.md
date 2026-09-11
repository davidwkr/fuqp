# Activity-launch regression probes

This standalone Gradle build produces three debug APKs: two otherwise identical
caller apps with different UIDs (`com.iodvd.fuqp.probe.filtered` and
`com.iodvd.fuqp.probe.control`) and `com.iodvd.fuqp.probe.target`.
It does not change the production Gradle settings or require test libraries.

The instrumentation runs **inside each caller's process**. It first opens a
foreground activity, confirms window focus, queries target visibility, and calls
`Activity.startActivity` with an explicit component. It reports its actual UID
and exception class. The host checks these against PackageManager's UID and
reads target-private lifecycle markers using `run-as`, independently of FUQP's
logs/counters. A successful return without a target launch fails the test.

## Build and host checks

From the repository root, with `ANDROID_HOME` pointing to the installed SDK:

```sh
./gradlew -p tests/activity-launch :caller:assembleFilteredDebug :caller:assembleControlDebug :target:assembleDebug
python3 -m unittest discover -s tests/activity-launch -p 'test_*.py' -v
```

The host tests validate the harness's acceptance logic, **not** the system hook.

## Device setup (only after installation is authorized)

Use one selected serial and Android user. Install the three APKs from the fixture
modules' `build/outputs/apk` directories using serial-scoped `adb install -r`.
Install/activate the FUQP manager and module separately; record their hashes and
verify which module is loaded. Unlock the device and keep it on its normal display.
The runner opens fixture activities and force-stops **only the fixture target**
before each attempt. It does not install APKs or modify FUQP configuration.

In FUQP, put only `com.iodvd.fuqp.probe.filtered` in scope, using a blacklist that
contains `com.iodvd.fuqp.probe.target`. Leave the control caller unscoped. Avoid
other presets/exclusions for these fixtures. Both APK manifests explicitly
declare target visibility, so Android's ordinary package-visibility rules do not
explain an invisible target. The positive-control activities are exported without
permissions; a separate private activity checks that protected callers cannot
distinguish an existing non-exported component through `SecurityException`.
The probe requires target visibility to be false for the filtered caller and true
for the control caller, even when launch protection is disabled.

## Four configuration runs

Set the two flags in FUQP before each run. The runner **does not automatically
verify or change the flags**: preserve an exported config/screenshot alongside
the report so the asserted configuration has evidence. Wait for it to apply.

| Mode | Global disable | Per-app invert | Filtered caller's installed target |
| --- | --- | --- | --- |
| default | false | false | ActivityNotFoundException |
| disabled | true | false | launches |
| inverted | false | true | launches |
| disabled-inverted | true | true | ActivityNotFoundException |

```sh
python3 tests/activity-launch/run.py --serial SERIAL --user 0 --mode default --output /tmp/fuqp-default.json
```

Repeat with each mode and a separate output file. Every run checks three exported
activities (one with an explicit MIME type), an activity alias, a private activity,
a missing class in the installed package, and a missing-package component, from
both callers (fourteen cases). The typed intent catches hooks that mistake the
resolved MIME type for the calling package when scanning string arguments. The
private activity must report `ActivityNotFoundException` to a protected caller,
and its normal `SecurityException` to a caller without launch protection.
Missing components must
always produce `ActivityNotFoundException`; allowed launches require both a unique
target lifecycle marker and a resumed target activity. Denied launches must have
neither. Failures and harness/setup errors exit nonzero and are saved in JSON.
Not-found messages must retain the caller's original component and, after replacing
that component text, match the missing-class control's message. The overall result
also includes this comparison; a per-case line alone is not the final verdict.

For a meaningful regression, run the default case against the prior module first
and keep the failing report, then repeat against the fix with the same fixture.
Do not count an expected reproduction of the vulnerability as a passing result.

## Remaining device checks

- Record AL/PM counter deltas for isolated launches; an AL delta above one for a
  single blocked launch is a failure. The automated suite does not read counters.
- Launch the target's home-screen icon, use a share sheet to send it text, open
  `fuqp-test://target` from an unfiltered browser, and return to it from Recents.
  These checks remain manual and must not be inferred from the explicit probes.
- Repeat on Samsung/non-Samsung and the relevant API/InxLocker configurations.
  Compilation alone does not establish hook attachment or ROM compatibility.
- Run from a secondary user/profile where available, explicitly setting `--user`.
  A work-profile policy blocking `run-as` or instrumentation is a harness gap,
  not proof of successful filtering.
- The two-second observation window catches ordinary launches; investigate delayed
  OEM starts manually. Do not claim this detects every possible delayed launch.

Target markers remain in the dedicated fixture's private files for diagnosis.
Uninstall only the fixture packages when device cleanup is authorized.
