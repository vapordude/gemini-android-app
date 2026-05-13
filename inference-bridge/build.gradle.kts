plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gemini.inference"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.android)
}

// Cross-compile the Rust workspace with cargo-ndk and stage the resulting
// .so files under src/main/jniLibs/<abi>/. Skipped if cargo-ndk is unavailable
// so the project still imports cleanly on developer machines without the
// Rust toolchain installed.
val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compile native/ for Android via cargo-ndk."
    workingDir = file("${rootDir}/native")
    isIgnoreExitValue = true
    val outDir = file("src/main/jniLibs")
    commandLine = listOf(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", outDir.absolutePath,
        "build", "--release", "-p", "jni-shim",
    )
}

tasks.named("preBuild") { dependsOn(buildNative) }
