plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Compiled by a JDK 21 wherever it is built, not by whichever JDK runs Gradle, so a release
    // built here and F-Droid's build of the same commit come out the same.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly(libs.error.prone.core)
    implementation(libs.annotation)
}