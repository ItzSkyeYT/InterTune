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
