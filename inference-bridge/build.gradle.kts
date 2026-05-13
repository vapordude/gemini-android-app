plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gemini.inference"
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
// CI invokes it from the dedicated `native` workflow; the Android workflow
// builds without native libs (the Kotlin facades fall back to stubs).
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
