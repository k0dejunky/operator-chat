plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    // The Compose compiler plugin for Kotlin 2.x. It turns the @Composable
    // functions into the code that drives the UI, so it has to match the
    // Kotlin version exactly.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
