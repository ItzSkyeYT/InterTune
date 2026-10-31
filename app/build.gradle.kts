@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.ApplicationBuildType
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
 * Credentials for the maintainer's services: the Last.fm API account and the polls' gist and Umami
 * site.
 *
 * In services.properties, which is committed. They used to live in the gitignored local.properties,
 * and that made a reproducible F-Droid build impossible: a build from source could never contain
 * what the published APK did. They only work in the app signed with the release key, see
 * BuiltInKeys.kt. local.properties is still read as a fallback, for a checkout whose
 * services.properties has not been filled in. Absent is a supported state: the build works, and
 * the features hide themselves rather than offering what cannot succeed.
 */
val servicesProperties = Properties()
rootProject.file("services.properties").takeIf { it.exists() }?.let { file ->
    FileInputStream(file).use { servicesProperties.load(it) }
}
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
fun service(key: String): String =
    servicesProperties.getProperty(key)?.takeIf { it.isNotBlank() } ?: localProperties.getProperty(key, "")

/**
 * The tester lock's values in a build type that can have it (src/preview, testerlock): whether it is on, and the
 * door it asks. The preview and the beta type (the Tester app) both call it, so there is one place that says what
 * the app reads and what publish-preview.sh reads, and one address for the door. The door is the testers' real one
 * unless -Pintertune.testerDoor points an emulator at a stand-in.
 *
 * Read when the app runs, never copied into the classes that use them: a build that changed only these would
 * otherwise keep the old values in whatever Kotlin did not compile again. The same two are in the manifest,
 * where publish-preview.sh reads them before it publishes.
 */
fun ApplicationBuildType.testerLock(on: Boolean) {
    val door = (project.findProperty("intertune.testerDoor") as String?)
        ?: "https://intertune-testbuild.intertune.workers.dev"
    buildConfigField("boolean", "TESTER_LOCK", "Boolean.parseBoolean(\"$on\")")
    buildConfigField("String", "TESTER_DOOR", "String.valueOf(\"$door\")")
    manifestPlaceholders["testerLock"] = on.toString()
    manifestPlaceholders["testerDoor"] = door
}

android {
    namespace = "com.dd3boh.outertune"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.skye.intertune"
        minSdk = 24
        targetSdk = 36
        versionCode = 98
        versionName = "0.12.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Obfuscated, not encrypted, and the difference matters. Anything the app can read, so
        // can anyone holding the apk. What this does buy is that `strings app.apk | grep` no
        // longer finds the credentials lying in plain sight, which is how they are actually
        // harvested in bulk. Someone reading the code still gets them in a minute.
        //
        // The only real fix is a server relaying the calls, which this app does not have. A leaked
        // Last.fm key costs a rate limit, not an account, and is revocable, so this is the right
        // amount of effort for what is at stake. The values are in the public source now, for
        // reproducible builds, and what keeps them to this app is BuiltInKeys' signature check.
        fun obfuscated(value: String): String {
            // An empty int array, not an empty string. These fields are declared int[], so
            // returning "\"\"" emitted `public static final int[] X = "";` and the whole app
            // stopped compiling the moment local.properties was absent, which is every build
            // from source: a fresh clone, a contributor, and F-Droid's buildserver. Both
            // reveal() functions already turn an empty array back into an empty string, so the
            // features switch themselves off exactly as their comments promise.
            if (value.isEmpty()) return "new int[]{}"
            val mask = "InterTune".toByteArray()
            val out = value.toByteArray().mapIndexed { i, b ->
                (b.toInt() xor mask[i % mask.size].toInt()) and 0xff
            }
            return out.joinToString(prefix = "new int[]{", postfix = "}")
        }
        buildConfigField(
            "int[]",
            "LASTFM_API_KEY",
            obfuscated(service("lastfm.apiKey"))
        )
        buildConfigField(
            "int[]",
            "LASTFM_API_SECRET",
            obfuscated(service("lastfm.apiSecret"))
        )

        // Polls, same treatment and the same honesty about it. None of these is a secret, but the
        // Umami website id lets anyone post events to that site, and the gist url is a maintainer
        // address that does not need to be handed out in a grep.
        buildConfigField(
            "int[]",
            "POLLS_URL",
            obfuscated(service("polls.gistUrl"))
        )
        buildConfigField(
            "int[]",
            "POLLS_UMAMI_URL",
            obfuscated(service("polls.umamiUrl"))
        )
        buildConfigField(
            "int[]",
            "POLLS_UMAMI_WEBSITE_ID",
            obfuscated(service("polls.umamiWebsiteId"))
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
        }
        // No else branch. It used to create an empty ot_release here, which the release build
        // type then pointed at, and AGP stops at packaging with "missing required property
        // storeFile" because a signing config with no keystore in it is not a signing config.
        // Leaving the name undefined lets findByName below return null, which is how you ask
        // AGP for an unsigned apk, and unsigned is exactly what a build from source should
        // produce: F-Droid signs its own, and so does anyone else building this without a key.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ot_release")
            // The app's own screens in a car (AutoCarService, AutoSettingsService) exist only where
            // Unreleased.AUTO_REWORK is on: a car that finds them draws the app from them, held back or not.
            manifestPlaceholders["carScreens"] = "false"
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["carScreens"] = "true"
        }

        // A debug build that installs beside the release and the debug build, for trying a
        // release candidate on a fresh profile without touching the debug install that holds a
        // real library. Its name and shortcuts are in src/preview.
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            matchingFallbacks += listOf("debug")
            // A preview published for testers carries its own version, so Obtainium can tell one
            // build from the next: tools/preview/publish-preview.sh passes -PpreviewVersion=<stamp>.
            // Without the property nothing changes.
            (project.findProperty("previewVersion") as String?)?.let { versionNameSuffix = "-preview.$it" }

            // The tester lock: a build for testers opens only with a tester's own code (src/preview,
            // testerlock). The Preview is our own, on our own phones, and has the lock only when
            // -Pintertune.testerLock=true is passed; without it there is none. No other build type reads
            // the property, and none of them has the gate's code. The beta type below has the lock always.
            testerLock(on = project.findProperty("intertune.testerLock") == "true")
        }

        // The testers' own app, InterTune Tester (dev.skye.intertune.tester): the Preview under a package, a name
        // and an icon of its own (the Preview's tape with a T badge on it), so that a build for testers never
        // installs over the Preview that is on our own phones. Its code, its words and its manifest are the
        // Preview's, taken from src/preview (see sourceSets); its name and icon are in src/beta. The lock is
        // always on and has no property to turn it off. publish-preview.sh builds this type. It keeps the
        // Preview's version name, <version>-preview.<stamp>, which the bot and the door read as an opaque tag
        // (experiments/features/tester-app/STATE.md).
        // The type is called beta and not tester because the Android Gradle plugin refuses a build type whose
        // name starts with "test": "BuildType names cannot start with 'test'".
        create("beta") {
            initWith(getByName("preview"))
            applicationIdSuffix = ".tester"
            matchingFallbacks += listOf("preview", "debug")
            versionNameSuffix = getByName("preview").versionNameSuffix
            testerLock(on = true)
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

    // userdebug is release without minify: to a car it says what release says (src/release), and it
    // has release's no tester lock.
    sourceSets {
        getByName("userdebug") {
            manifest.srcFile("src/release/AndroidManifest.xml")
            res.srcDir("src/release/res")
            java.srcDir("src/release/java")
        }
        // The tester lock's rules and its door client are plain Kotlin, in src/preview/jvm. The preview
        // build has them in the app; the other build types' unit tests compile them beside the tests.
        // So the debug unit tests, which the full check runs, test them, and no other build has them.
        // Its words and the network rules its manifest names are in src/preview/res-lock, which the
        // preview and the beta type both take (a folder of its own, since a source set may not hold two
        // copies of a resource and the Tester app has its own name and icon in src/beta/res).
        getByName("preview") {
            java.srcDir("src/preview/jvm")
            res.srcDir("src/preview/res-lock")
        }
        // The beta type (the Tester app) is the preview's code, words, manifest and assets under its own name and
        // icon: the lock's gate and its rules, the folder of words, and the manifest that carries the two marks
        // publish-preview.sh reads. Nothing is copied, so the Tester app cannot drift from the Preview's lock.
        getByName("beta") {
            java.srcDir("src/preview/java")
            java.srcDir("src/preview/jvm")
            res.srcDir("src/preview/res-lock")
            assets.srcDir("src/preview/assets")
            manifest.srcFile("src/preview/AndroidManifest.xml")
        }
        for (tests in listOf("testDebug", "testRelease", "testUserdebug")) {
            getByName(tests) { java.srcDir("src/preview/jvm") }
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
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
            // Libraries built from source rather than taken from Maven, which the plugin cannot see:
            // InnerTubeX's SABR client, compiled into the :sabr module.
            configPath = file("../config")
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

            // CameraX's core carries libyuv (Chromium's, a BSD licence, GPL-compatible) for Listen
            // together's QR scanner. Allowed for that library alone, so that no other "BSD License"
            // comes in unseen under the same vague name.
            allowedLicensesMap.put("44d1a25c593283261968a15c8cc213cd", listOf("androidx.camera:camera-core"))

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

/**
 * What to try, for the Testers page of a build for testers (src/preview, testerlock): -PtesterTry=<a text file> copies that file
 * into the build as an asset, tester_try.txt, and the page shows its lines. Without the property the build has no such asset and
 * the page shows nothing of the kind. Only the preview and the beta type (the Tester app) have the page, so only they are given it.
 * publish-preview.sh passes the notes for testers it is given, the file that starts with "What to try first:".
 */
abstract class CopyTesterTry : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = assets.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        source.get().asFile.copyTo(File(dir, "tester_try.txt"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val path = (project.findProperty("testerTry") as String?)?.takeIf { it.isNotBlank() } ?: return@onVariants
        if (variant.buildType != "preview" && variant.buildType != "beta") return@onVariants
        val file = File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
        // A path that is not a file is a mistake worth stopping for: a build for testers must not go out without the list it was meant to carry.
        if (!file.isFile) throw GradleException("-PtesterTry=$path is not a file")
        val copy = tasks.register<CopyTesterTry>("copyTesterTry${variant.name.replaceFirstChar { it.uppercase() }}") {
            source.set(file)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, CopyTesterTry::assets)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kyant.backdrop)
    implementation(libs.guava)
    implementation(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
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
    // Debug only: it brings PreviewActivity with it, exported, and a release has no use for it.
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)
    implementation(libs.compose.icons.extended)

    // ui
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.shimmer)
    implementation(libs.glance)
    implementation(libs.glance.material3)

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
    // Lyrics shared as a video: the clip's frames and the song's audio encoded on the phone (LyricsVideo).
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    // The app's own screens in a car: a car draws them from this and nothing else. The second is what
    // the first hands the session to the car with; it comes with it either way, named here to compile against.
    implementation(libs.car.app)
    implementation(libs.media.compat)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)

    // Runs the recommendation SQL against the real exported Room schema on the JVM, with no
    // emulator. See RecommendationSqlTest.
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    // The XML pull parser Android itself uses, so the lyrics TTML parser runs in plain JVM tests,
    // where android.util.Xml has no parser behind it. See TtmlInternalNamespaceTest.
    testImplementation(libs.kxml2)

    // modules
    implementation(project(":fingerprint"))
    // AutoMix's analysis, planning and mixing, pure Kotlin like the fingerprint code (Unreleased.AUTOMIX).
    implementation(project(":mix"))
    implementation(project(":innertube"))
    implementation(project(":sabr"))
    implementation(libs.ktor.client.okhttp)
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":lastfm"))
    implementation(libs.work)

    testImplementation(libs.junit)
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

// Hilt's generated Java is compiled by its own task, which takes the JDK that runs Gradle rather
// than the toolchain above, so a machine whose Gradle runs on 17 failed on "invalid source
// release: 21". Every Java compile here uses the same JDK 21 as the Kotlin, as F-Droid's server does.
tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
