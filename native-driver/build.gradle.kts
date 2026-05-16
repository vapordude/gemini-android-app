plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // NOT `com.gemini.native` -- "native" is a Java reserved keyword and
    // AGP-generated BuildConfig.java would fail javac.
    namespace = "nz.kaimahi.localdriver"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // libgemma4.so per ABI is dropped into src/main/jniLibs/<abi>/ by the
    // Rust build step (cargo + ANDROID_NDK_HOME → cargo-ndk). When the .so
    // is absent (e.g. during the host CI build that skips the NDK step),
    // the Kotlin façade falls back to a "not yet implemented" status that
    // the DriverRouter can detect.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core-bridge"))
    implementation(libs.kotlinx.coroutines.android)
}
