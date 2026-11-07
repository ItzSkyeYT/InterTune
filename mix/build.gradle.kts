import java.security.MessageDigest

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Only for its Fft. The fingerprint code itself is not used here.
    implementation(project(":fingerprint"))
    // The stored form of a saved transition (edit/TransitionJson.kt): JSON trees only, no compiler plugin.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // The host trial holds two songs' worth of decoded audio at a time, which the default worker
    // heap of 512 MB only just fits.
    maxHeapSize = "1g"
}

/**
 * A stamp of the code that analyses songs and picks Auto's change (this module and the FFT it
 * borrows), written as a constant, MixCode.STAMP. The Mix editor keeps what the analysis and Auto
 * found for a pair under it, so a new build of the app takes them as they are unless this code
 * changed: with the install time as the stamp, every update analysed every pair again (7 Nov
 * 2026). Only the sources' paths and bytes go in, so every machine computes the same stamp from
 * the same sources and a reproducible build stays reproducible. MixCodeTest computes it again.
 */
abstract class MixCodeStamp : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mixSources: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fingerprintSources: DirectoryProperty

    @get:OutputDirectory
    abstract val out: DirectoryProperty

    @TaskAction
    fun write() {
        fun stampOf(keep: (label: String, path: String) -> Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for ((label, dir) in listOf("mix" to mixSources, "fingerprint" to fingerprintSources)) {
                val root = dir.get().asFile
                val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
                for (path in files) {
                    if (!keep(label, path)) continue
                    digest.update("$label/$path".toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    digest.update(root.resolve(path).readBytes())
                    digest.update(0)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val stamp = stampOf { _, _ -> true }
        // The analysis of a song's end or opening: everything but how a change is chosen, planned and
        // played (auto, engine, edit, pill and the renderer's own files), so a change to mixing keeps it.
        val mixing = listOf("/auto/", "/engine/", "/edit/", "/pill/", "/Wsola.kt", "/MixRender.kt", "/MixPlan.kt", "/HeldTail.kt", "/Planner.kt")
        fun analysis(label: String, path: String) = label == "fingerprint" || mixing.none { "/$path".contains(it) }
        val analysisStamp = stampOf(::analysis)
        // Auto's pick: the analysis and Auto's own rules (a change to how changes are rendered keeps it).
        fun auto(path: String) = "/$path".contains("/auto/") || listOf("/engine/AutoSpec.kt", "/engine/AutoOnBands.kt").any { "/$path".endsWith(it) }
        val autoStamp = stampOf { label, path -> analysis(label, path) || (label == "mix" && auto(path)) }
        val file = out.get().asFile.resolve("com/dd3boh/outertune/mix/MixCode.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.dd3boh.outertune.mix
            |
            |/** Written by the build (mix/build.gradle.kts, MixCodeStamp): stamps of this module's sources. */
            |object MixCode {
            |    /** All of it. */
            |    const val STAMP = "$stamp"
            |
            |    /** The code an analysis of a song comes from: what the editor's kept analyses are kept under. */
            |    const val ANALYSIS = "$analysisStamp"
            |
            |    /** The analysis and Auto's rules: what Auto's kept picks are kept under. */
            |    const val AUTO = "$autoStamp"
            |}
            |""".trimMargin()
        )
    }
}

val mixCodeStamp = tasks.register<MixCodeStamp>("mixCodeStamp") {
    mixSources.set(layout.projectDirectory.dir("src/main"))
    fingerprintSources.set(rootProject.layout.projectDirectory.dir("fingerprint/src/main"))
    out.set(layout.buildDirectory.dir("generated/mixcode/kotlin"))
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(mixCodeStamp)
}
