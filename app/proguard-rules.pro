# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# QuickJS: methods are invoked by name from JS
-keep class app.cash.quickjs.** { *; }
-keepclassmembers class * implements app.cash.quickjs.** { *; }

# Keep bridge interfaces used by QuickJS reflectively
-keep interface com.gemini.bridge.** { *; }
-keep class com.gemini.bridge.** { *; }

# Domain models (may be reflected / serialized)
-keep class com.gemini.domain.** { *; }

# Jetpack Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
