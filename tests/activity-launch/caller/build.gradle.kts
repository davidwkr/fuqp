plugins {
    id("com.android.application")
}

android {
    namespace = "com.iodvd.fuqp.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.iodvd.fuqp.probe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    flavorDimensions += "caller"
    productFlavors {
        create("filtered") {
            dimension = "caller"
            applicationIdSuffix = ".filtered"
        }
        create("control") {
            dimension = "caller"
            applicationIdSuffix = ".control"
        }
    }
}
