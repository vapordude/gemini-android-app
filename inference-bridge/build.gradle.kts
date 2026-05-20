plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nz.kaimahi.inference"
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
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.android)
}

// Opt-in task to cross-compile the Rust workspace via cargo-ndk and stage
// the resulting .so files under src/main/jniLibs/<abi>/. NOT hooked into
// preBuild — running it requires the Rust toolchain + cargo-ndk installed.
//
// The CI Android workflow (.github/workflows/android.yml) runs this task
// before `:app:assembleDebug` / `:app:assembleRelease`, so APKs uploaded
// as workflow artifacts always contain libkaimahi_native.so. LOCAL Gradle
// builds without Rust installed will produce a stub APK — the Kotlin
// facades detect the missing .so via `NativeInference.loaded == false`
// and surface "Native runtime not available" errors on first use.
val buildNative by tasks.registering {
    group = "build"
    description = "Cross-compile native/ for Android via cargo-ndk (requires Rust toolchain)."
    doLast {
        val outDir = file("src/main/jniLibs")
        val nativeDir = file("${rootDir}/native")
        exec {
            workingDir = nativeDir
            commandLine = listOf(
                "cargo", "ndk",
                "-t", "arm64-v8a",
                "-t", "x86_64",
                "-o", outDir.absolutePath,
                "build", "--release", "-p", "jni-shim",
            )
        }
    }
}
