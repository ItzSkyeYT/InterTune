plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // ONNX Runtime's Java API, to compile the model's runner against. Nothing of it is packaged from
    // here: the debug app brings the Android runtime (onnxruntime-android, same API, same version),
    // and the tests below bring the desktop one.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // A segment's spectra in and out are 78 MB of floats, and the reference test holds the model's
    // output beside its own.
    maxHeapSize = "1g"
}
