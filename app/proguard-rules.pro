# OperatorChat release shrink/obfuscation rules.
# Keep entry points used via reflection/annotations that R8 cannot see.

# OkHttp (consumer rules are bundled, but be explicit about the builder).
-keepclassmembers class okhttp3.** { *; }
-dontwarn okhttp3.**

# Coil (bundled consumer rules handle most; keep the app-level loader).
-keep class coil.** { *; }
-dontwarn coil.**

# org.json (bundled jar has no consumer rules).
-keep class org.json.** { *; }
-dontwarn org.json.**

# Kotlin coroutines + WorkManager.
-keep class androidx.work.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.work.**

# FileProvider / app classes are referenced from the manifest by name.
-keep class com.amethyst2213.operatorchat.** { *; }
# RootEncoder (RTMP) pulls in optional SLF4J bindings; R8 must not fail on the
# missing runtime binder.
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**
