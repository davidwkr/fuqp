#!/usr/bin/env python3
import argparse
import json
import re
import subprocess
import time
import uuid
from pathlib import Path


TARGET = "com.iodvd.fuqp.probe.target"
FILTERED = "com.iodvd.fuqp.probe.filtered"
CONTROL = "com.iodvd.fuqp.probe.control"
NOT_FOUND = "android.content.ActivityNotFoundException"
SECURITY = "java.lang.SecurityException"
COMPONENTS = ("TargetActivity", "SecondActivity", "TypedActivity", "TargetAlias", "PrivateActivity")
MODES = {"default": True, "disabled": False, "inverted": False, "disabled-inverted": True}


def validate(observation, expected, visible, marker_seen, foreground, caller, uid):
    failures = []
    if observation.get("caller") != caller or observation.get("uid") != uid:
        failures.append("Probe did not execute as the expected application UID")
    if observation.get("targetVisible") is not visible:
        failures.append("Target PackageManager visibility does not match the fixture configuration")
    if observation.get("outcome") != expected:
        failures.append(f"Expected {expected}, got {observation.get('outcome')}")
    if expected == NOT_FOUND:
        component = observation.get("originalComponent")
        if not component or component not in (observation.get("exceptionMessage") or ""):
            failures.append("Not-found exception does not identify the original requested component")
    if expected == "STARTED":
        if not marker_seen:
            failures.append("Launch returned success but target did not record onCreate")
        if not foreground:
            failures.append("Target did not become the resumed activity")
    elif marker_seen or foreground:
        failures.append("A rejected launch still started or foregrounded the target")
    return failures


def main():
    parser = argparse.ArgumentParser(description="Run real-app-UID FUQP launch regression probes")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--user", type=int, default=0)
    parser.add_argument("--mode", choices=MODES, required=True,
                        help="Expected manually configured FUQP disable/invert combination")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()

    def adb(*command, check=True):
        return subprocess.run(["adb", "-s", arguments.serial, *command],
                              text=True, capture_output=True, check=check, timeout=40)

    def package_uid(package):
        output = adb("shell", "pm", "list", "packages", "-U", "--user",
                     str(arguments.user), package).stdout
        match = re.search(r"^package:" + re.escape(package) + r" uid:(\d+)", output, re.M)
        if not match:
            raise RuntimeError(f"Fixture package missing or UID unavailable: {package}")
        return int(match.group(1))

    def marker_present(token):
        result = adb("shell", "run-as", TARGET, "--user", str(arguments.user),
                     "cat", "files/launch-events", check=False)
        if result.returncode != 0:
            combined = result.stdout + result.stderr
            if "No such file or directory" in combined:
                return False
            raise RuntimeError("Cannot read target launch evidence: " + combined)
        return token in result.stdout.splitlines()

    def foreground_target():
        output = adb("shell", "dumpsys", "activity", "activities").stdout
        resumed = [line for line in output.splitlines()
                   if "mResumedActivity" in line or "topResumedActivity" in line
                   or "ResumedActivity:" in line]
        if not resumed:
            raise RuntimeError("No supported resumed-activity field; inspect this ROM manually")
        return any(TARGET + "/" in line for line in resumed)

    report = {
        "serial": arguments.serial,
        "user": arguments.user,
        "declaredConfiguration": arguments.mode,
        "configurationVerifiedAutomatically": False,
        "cases": [],
    }
    failures = 0
    try:
        report["fingerprint"] = adb("shell", "getprop", "ro.build.fingerprint").stdout.strip()
        callers = {CONTROL: package_uid(CONTROL), FILTERED: package_uid(FILTERED)}
        package_uid(TARGET)
        absent_package = TARGET + ".absent"
        absent_listing = adb("shell", "pm", "list", "packages", "--user",
                             str(arguments.user), absent_package).stdout.splitlines()
        if "package:" + absent_package in absent_listing:
            raise RuntimeError("The missing-package control is installed")
        if callers[CONTROL] == callers[FILTERED]:
            raise RuntimeError("Filtered and control callers must have different UIDs")
        for caller, uid in callers.items():
            for component in (*COMPONENTS, "MissingActivity", "MissingPackage"):
                absent = component.startswith("Missing")
                blocked = caller == FILTERED and MODES[arguments.mode]
                expected = NOT_FOUND if absent or blocked else "STARTED"
                if component == "PrivateActivity" and not blocked:
                    expected = SECURITY
                target_package = TARGET + ".absent" if component == "MissingPackage" else TARGET
                target_class = TARGET + "." + component
                token = uuid.uuid4().hex
                adb("shell", "am", "force-stop", "--user", str(arguments.user), TARGET)
                output = adb("shell", "am", "instrument", "--user", str(arguments.user),
                             "-w", "-r", "-e", "targetPackage", target_package,
                             "-e", "targetClass", target_class, "-e", "token", token,
                             caller + "/com.iodvd.fuqp.probe.ProbeInstrumentation").stdout
                match = re.search(r"^INSTRUMENTATION_RESULT: fuqp.result=(.*)$", output, re.M)
                if not match or "INSTRUMENTATION_CODE: -1" not in output:
                    raise RuntimeError("Probe failed to produce a result: " + output)
                observation = json.loads(match.group(1))
                if observation.get("token") != token:
                    raise RuntimeError("Probe returned a stale launch token")
                time.sleep(2)
                marker_seen = marker_present(token)
                foreground = foreground_target()
                errors = validate(observation, expected, caller == CONTROL,
                                  marker_seen, foreground, caller, uid)
                report["cases"].append({
                    "component": target_package + "/" + target_class,
                    "observation": observation,
                    "expected": expected,
                    "targetOnCreate": marker_seen,
                    "targetResumed": foreground,
                    "failures": errors,
                })
                failures += len(errors)
                print(f"{'FAIL' if errors else 'PASS'} {caller}: {component}")
            caller_cases = [case for case in report["cases"]
                            if case["observation"]["caller"] == caller]
            baseline = next(case["observation"] for case in caller_cases
                            if case["component"].endswith(".MissingActivity"))
            baseline_shape = (baseline.get("exceptionMessage") or "").replace(
                baseline.get("originalComponent") or "<missing>", "<component>")
            for case in caller_cases:
                observation = case["observation"]
                if case["expected"] != NOT_FOUND:
                    continue
                shape = (observation.get("exceptionMessage") or "").replace(
                    observation.get("originalComponent") or "<missing>", "<component>")
                if shape != baseline_shape:
                    case["failures"].append("Exception message differs from nonexistent-component control")
                    failures += 1
    except (RuntimeError, subprocess.SubprocessError, ValueError) as error:
        report["harnessError"] = str(error)
        failures += 1
    report["passed"] = failures == 0 and len(report["cases"]) == 2 * (len(COMPONENTS) + 2)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"{'PASS' if report['passed'] else 'FAIL'} overall; evidence: {arguments.output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
