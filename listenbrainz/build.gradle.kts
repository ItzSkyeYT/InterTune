plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.json)
    testImplementation(libs.junit)
    // ListenBrainz's answers, word for word, with no network. See ListenBrainzClientTest.
    testImplementation(libs.ktor.client.mock)
}
