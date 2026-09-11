package com.iodvd.fuqp.zygote;

import static com.iodvd.fuqp.zygote.util.Logcat.logELegacy;
import static com.iodvd.fuqp.zygote.util.Logcat.logILegacy;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.zygisk.ZygoteLoader;

import com.iodvd.fuqp.common.BuildConfig;
import com.iodvd.fuqp.zygote.service.SystemServerHook;

@SuppressWarnings("all")
@DoNotObfuscateType
@DoNotShrinkType
public class ZygoteEntry {
    public static final String TAG = "ZygoteEntry";

    @DoNotObfuscate
    @DoNotShrink
    public static void premain() throws Throwable {

    }

    @DoNotObfuscate
    @DoNotShrink
    public static void main() throws Throwable {
        logILegacy(TAG, String.format("Injected into %s - %s", ZygoteLoader.getPackageName(), BuildConfig.APP_VERSION_NAME), null);

        try {
            SystemServerHook.init();
        } catch (Throwable th) {
            logELegacy(TAG, "An exception occurred while SystemServerHook init", th);

            // do not print "Done" if there is an issue
            return;
        }

        logILegacy(TAG, "Done", null);
    }
}
