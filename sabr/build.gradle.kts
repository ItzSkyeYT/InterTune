plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

// InnerTubeX's SABR transport, built from the submodule at innertubex/ (pinned to a release tag and
// never edited): its sabr package and the eight files that package needs, nothing else. Choosing
// the client and the format stays ours, as do the n solver, the po tokens and visitorData; this
// only moves the bytes. Why this way and not JitPack: experiments/features/sabr/PLAN.md, option A.
val innertubex = rootProject.file("innertubex/src/commonMain/kotlin")
val taken = listOf(
    // Ours: what the app needs of SABR, in plain Kotlin so the JVM tests reach it.
    "com/dd3boh/outertune/sabr/**",
    "com/metrolist/innertubex/sabr/**",
    "com/metrolist/innertubex/InnerTubeLogger.kt",
    "com/metrolist/innertubex/extraction/FormatSelectors.kt",
    "com/metrolist/innertubex/extraction/AudioQuality.kt",
    "com/metrolist/innertubex/models/YouTubeClient.kt",
    "com/metrolist/innertubex/models/Context.kt",
    "com/metrolist/innertubex/models/YouTubeLocale.kt",
    "com/metrolist/innertubex/models/Thumbnails.kt",
    "com/metrolist/innertubex/models/response/PlayerResponse.kt",
)

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir(innertubex)
        kotlin.include(taken)
    }
    // InnerTubeX's own tests of that package, run here against our versions of Ktor and coroutines.
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.file("innertubex/src/commonTest/kotlin"))
        kotlin.include("com/metrolist/innertubex/sabr/**", "com/dd3boh/outertune/sabr/**")
    }
    compilerOptions {
        // As InnerTubeX's own build sets them.
        optIn.addAll("kotlin.RequiresOptIn", "kotlin.ExperimentalStdlibApi")
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnit()
}
