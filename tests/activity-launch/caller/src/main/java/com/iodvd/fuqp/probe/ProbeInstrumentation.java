package com.iodvd.fuqp.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;

public class ProbeInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Activity caller = null;
        try {
            String targetPackage = arguments.getString("targetPackage");
            String targetClass = arguments.getString("targetClass");
            String token = arguments.getString("token");
            if (targetPackage == null || targetClass == null || token == null) {
                throw new IllegalArgumentException("targetPackage, targetClass and token are required");
            }
            Intent callerIntent = new Intent(getTargetContext(), ProbeActivity.class);
            callerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            caller = startActivitySync(callerIntent);
            waitForIdleSync();
            Activity foregroundCaller = caller;
            boolean[] focused = {false};
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!focused[0] && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync(() -> focused[0] = foregroundCaller.hasWindowFocus());
                SystemClock.sleep(50);
            }
            if (!focused[0]) {
                throw new IllegalStateException("Caller lacks foreground focus; unlock the device");
            }
            boolean visible;
            try {
                getTargetContext().getPackageManager().getPackageInfo(
                    "com.iodvd.fuqp.probe.target", 0);
                visible = true;
            } catch (PackageManager.NameNotFoundException missing) {
                visible = false;
            }
            Intent launch = new Intent();
            launch.setComponent(new ComponentName(targetPackage, targetClass));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            launch.putExtra("fuqp.token", token);
            if (targetClass.equals("com.iodvd.fuqp.probe.target.TypedActivity")) {
                launch.setAction(Intent.ACTION_VIEW);
                launch.setType("text/plain");
            }
            String[] outcome = {"STARTED"};
            String[] exceptionMessage = {""};
            String originalComponent = launch.getComponent().toShortString();
            runOnMainSync(() -> {
                try {
                    foregroundCaller.startActivity(launch);
                } catch (RuntimeException failure) {
                    outcome[0] = failure.getClass().getName();
                    exceptionMessage[0] = failure.getMessage();
                }
            });
            JSONObject observation = new JSONObject();
            observation.put("caller", getTargetContext().getPackageName());
            observation.put("uid", Process.myUid());
            observation.put("token", token);
            observation.put("targetVisible", visible);
            observation.put("outcome", outcome[0]);
            observation.put("originalComponent", originalComponent);
            observation.put("exceptionMessage", exceptionMessage[0]);
            observation.put("mimeType", launch.getType());
            result.putString("fuqp.result", observation.toString());
            runOnMainSync(foregroundCaller::finish);
            caller = null;
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            if (caller != null) {
                Activity finishedCaller = caller;
                runOnMainSync(finishedCaller::finish);
            }
            result.putString("fuqp.error", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
