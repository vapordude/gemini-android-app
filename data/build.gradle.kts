plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.gemini.data"; compileSdk = 34; defaultConfig { minSdk = 26 } }
dependencies { implementation(project(":domain")) }