plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun propertyOrEnv(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = propertyOrEnv("ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword = propertyOrEnv("ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = propertyOrEnv("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = propertyOrEnv("ANDROID_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() } && file(releaseStoreFilePath!!).exists()

android {
    namespace = "nz.kaimahi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.google.gemini.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // AppAuth's library merge requires this placeholder; our OAuth redirect
        // URI uses this custom scheme (com.google.gemini.android:/oauth2redirect).
        manifestPlaceholders += mapOf("appAuthRedirectScheme" to "com.google.gemini.android")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":domain"))
    implementation(project(":ui-components"))
    implementation(project(":native-driver"))
    implementation(project(":inference-bridge"))
    implementation(project(":agent-bridge"))
    implementation(project(":emdash-bridge"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.auth)
    implementation(libs.appauth)
    implementation(libs.androidx.browser)

    debugImplementation(libs.compose.ui.tooling)
}
