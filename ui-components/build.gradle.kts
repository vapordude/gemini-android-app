plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.gemini.ui"; compileSdk = 34; defaultConfig { minSdk = 26 }; buildFeatures { compose = true }; composeOptions { kotlinCompilerExtensionVersion = "1.5.11" } }
dependencies { implementation(platform("androidx.compose:compose-bom:2024.06.00")); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3") }