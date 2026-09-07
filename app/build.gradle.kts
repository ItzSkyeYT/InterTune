@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties


plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

/**
 * Last.fm credentials, from last.fm/api/account/create.
 *
 * Kept in local.properties, which is gitignored, so the fork's key never lands in a public repo.
 * Absent is a supported state: the build works, and the Last.fm setting hides itself rather than
 * offering a login that cannot succeed.
 */
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.dd3boh.outertune"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.skye.intertune"
        minSdk = 24
        targetSdk = 36
        versionCode = 83
        versionName = "0.10.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Obfuscated, not encrypted, and the difference matters. Anything the app can read, so
        // can anyone holding the apk. What this does buy is that `strings app.apk | grep` no
        // longer finds the credentials lying in plain sight, which is how they are actually
        // harvested in bulk. Someone reading the code still gets them in a minute.
        //
        // The only real fix is a server relaying the calls, which this app does not have. A leaked
        // Last.fm key costs a rate limit, not an account, and is revocable, so this is the right
        // amount of effort for what is at stake.
        fun obfuscated(value: String): String {
            if (value.isEmpty()) return "\"\""
            val mask = "InterTune".toByteArray()
            val out = value.toByteArray().mapIndexed { i, b ->
                (b.toInt() xor mask[i % mask.size].toInt()) and 0xff
            }
            return out.joinToString(prefix = "new int[]{", postfix = "}")
        }
        buildConfigField(
            "int[]",
            "LASTFM_API_KEY",
            obfuscated(localProperties.getProperty("lastfm.apiKey", ""))
        )
        buildConfigField(
            "int[]",
            "LASTFM_API_SECRET",
            obfuscated(localProperties.getProperty("lastfm.apiSecret", ""))
        )

        // Polls, same treatment and the same honesty about it. None of these is a secret, but the
        // Umami website id lets anyone post events to that site, and the gist url is a maintainer
        // address that does not need to be handed out in a grep.
        buildConfigField(
            "int[]",
            "POLLS_URL",
            obfuscated(localProperties.getProperty("polls.gistUrl", ""))
        )
        buildConfigField(
            "int[]",
            "POLLS_UMAMI_URL",
            obfuscated(localProperties.getProperty("polls.umamiUrl", ""))
        )
        buildConfigField(
            "int[]",
            "POLLS_UMAMI_WEBSITE_ID",
            obfuscated(localProperties.getProperty("polls.umamiWebsiteId", ""))
        )
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("ot_release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                (keystoreProperties["keyAlias"] as? String)?.let {
                    keyAlias = it
                }
                (keystoreProperties["keyPassword"] as? String)?.let {
                    keyPassword = it
                }
                (keystoreProperties["storePassword"] as? String)?.let {
                    storePassword = it
                }
            }
        } else {
            create("ot_release") { }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("ot_release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        // userdebug is release builds without minify
        create("userdebug") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
//            isDebuggable = true
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

// build variants and stuff
//    splits {
//        abi {
//            isEnable = true
//            reset()
//
//            include("x86_64", "x86", "armeabi-v7a", "arm64-v8a")
//            isUniversalApk = true
//        }
//    }

    flavorDimensions.add("abi")

    productFlavors {
        // main version
        create("core") {
            isDefault = true
            dimension = "abi"
        }

        // fully featured version, large file size
        create("full") {
            dimension = "abi"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "InterTune-${variant.versionName}-${output.baseName}-${output.versionCode}.apk"
                output.outputFileName = outputFileName
            }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")

        }
    }

    tasks.withType<KotlinCompile> {
        if (!name.substringAfter("compile").lowercase().startsWith("full")) {
            exclude("**/*FFmpegScanner.kt")
            exclude("**/*NextRendersFactory.kt")
        } else {
            exclude("**/*FFmpegScannerDud.kt")
            exclude("**/*ffdecoderDud.kt")
        }
    }


    aboutLibraries {
        offlineMode = true

        collect {
            fetchRemoteLicense = false
            fetchRemoteFunding = false
            filterVariants.addAll("release")
        }

        export {
            // Remove the "generated" timestamp to allow for reproducible builds
            excludeFields = listOf("generated")
        }

        license {
            // Define the strict mode, will fail if the project uses licenses not allowed
            strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
            // Allowed set of licenses, this project will be able to use without build failure
            allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause", "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1", "GNU GENERAL PUBLIC LICENSE, Version 3", "GPL-3.0-only", "GPL-3.0-or-later", "EPL-2.0", "MIT", "MPL-2.0", "Public Domain")

            // Full license text for license IDs mentioned here will be included, even if no detected dependency uses them.
             additionalLicenses.addAll("apache_2_0", "gpl_2_1") // taglib, ffMpeg in ffMetadataEx
        }

        library {
            duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
            duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
        }
    }

    // for RB
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        lintConfig = file("lint.xml")
    }

    androidResources {
        generateLocaleConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kyant.backdrop)
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    // compose
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)
    implementation(libs.compose.icons.extended)

    // ui
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.shimmer)

    // material
    implementation(libs.adaptive)
    implementation(libs.material3)
    implementation(libs.palette)

    // viewmodel
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.media3)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.workmanager)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)

    // modules
    implementation(project(":innertube"))
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":lastfm"))
    implementation(project(":material-color-utilities"))
    implementation(project(":taglib"))

    // misc
    implementation(libs.aboutlibraries.compose.m3)

    // sdk24 support
    // Support for N is officially unsupported even it the app should still work. Leave this outside of the version catalog.
    implementation("androidx.webkit:webkit:1.14.0")
}

afterEvaluate {
    dependencies {
        add("fullImplementation", project(":ffMetadataEx"))
    }
}
