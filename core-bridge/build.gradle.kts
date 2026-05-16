plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nz.kaimahi.bridge"
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    // AppAuth handles the OAuth dance for the gemini-cli flow. Lives here
    // (not in :app) so RestGeminiCore can refresh tokens without bouncing
    // through the UI layer.
    implementation(libs.appauth)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // Android's `org.json` is a stub during host-JVM unit tests; pull in the
    // real artifact so the PatchKernelClient tests can run.
    testImplementation(libs.org.json)
}
