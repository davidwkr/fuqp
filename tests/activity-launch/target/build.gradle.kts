plugins {
    id("com.android.application")
}

android {
    namespace = "com.iodvd.fuqp.probe.target"
    compileSdk = 36
    defaultConfig {
        applicationId = namespace
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
}
