import com.android.ide.common.signing.KeystoreHelper
import com.v7878.zygisk.gradle.ZygoteLoader
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.PrintStream
import java.util.Locale

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.com.github.aerathstuff.zygoteloader)
}

val appPackageName: String by rootProject.extra

android {
    namespace = "$appPackageName.zygote"

    defaultConfig {
        applicationId = namespace
    }
}

tasks.clean {
    for (item in arrayOf("debug", "release")) {
        delete(File(android.sourceSets[item].assets.srcDirs.first(), "manager.apk"))
    }
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        val variantCapped = variant.name.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val variantLowered = variant.name.lowercase(Locale.ROOT)

        val outSrcDir = layout.buildDirectory.dir("generated/source/signInfo/${variantLowered}")
        val outSrc = outSrcDir.get().file("com/iodvd/fuqp/zygote/Magic.java")
        val signInfoTask = tasks.register("generate${variantCapped}SignInfo") {
            description = "Generate signature info for verification"

            outputs.file(outSrc)
            doLast {
                addManagerApp(variantLowered)

                val sign = android.buildTypes[variantLowered].signingConfig
                outSrc.asFile.parentFile.mkdirs()
                val certificateInfo = KeystoreHelper.getCertificateInfo(
                    sign?.storeType,
                    sign?.storeFile,
                    sign?.storePassword,
                    sign?.keyPassword,
                    sign?.keyAlias
                )
                PrintStream(outSrc.asFile).apply {
                    println("package com.iodvd.fuqp.zygote;")
                    println("public final class Magic {")
                    print("public static final byte[] magicNumbers = {")
                    val bytes = certificateInfo.certificate.encoded
                    print(bytes.joinToString(",") { it.toString() })
                    println("};")
                    println("}")
                }
            }
        }
        variant.registerJavaGeneratingTask(signInfoTask, outSrcDir.get().asFile)

        val kotlinCompileTask = tasks.findByName("compile${variantCapped}Kotlin") as KotlinCompile
        kotlinCompileTask.dependsOn(signInfoTask)
        val srcSet = objects.sourceDirectorySet("magic", "magic").srcDir(outSrcDir)
        kotlinCompileTask.source(srcSet)
    }
}

fun addManagerApp(variant: String) {
    val builtFile = File(
        layout.buildDirectory.get().asFile.toString().replace(project.name, "app"),
        "outputs/apk/$variant/${rootProject.name}-${android.defaultConfig.versionName}-${variant}.apk",
    )

    if (!builtFile.exists()) {
        throw GradleException("The manager app for $variant ($builtFile) is not built yet")
    }

    builtFile.copyTo(
        File(android.sourceSets[variant].assets.srcDirs.first(), "manager.apk"),
        overwrite = true,
    )
}

zygisk {
    // inject to system_server
    packages(ZygoteLoader.PACKAGE_SYSTEM_SERVER)

    // module properties
    id = "fuqp_zygisk"
    name = "F-U Query Package Zygisk"
    author = "frknkrc44"
    description = "A Zygisk backend for F-U Query Package"
    entrypoint = "com.iodvd.fuqp.zygote.ZygoteEntry"
    archiveName = "${rootProject.name}-ZYGISK-${android.defaultConfig.versionName}"
    updateJson = "https://raw.githubusercontent.com/davidwkr/fuqp/master/update.json"
    isAddVariantToArchiveName = true
}

dependencies {
    implementation(projects.common)

    implementation(libs.androidx.annotation.jvm)
    implementation(libs.com.android.tools.build.apksig)
    implementation(libs.io.github.vova7878.androidvmtools)
    implementation(libs.io.github.vova7878.r8annotations)
    implementation(libs.dev.rikka.hidden.compat)

    compileOnly(libs.dev.rikka.hidden.stub)
}
