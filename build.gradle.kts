// Top-level build file. Plugins are declared here with `apply false` so the
// versions are resolved once for the whole project, then *applied* in each
// module (see app/build.gradle.kts). Versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
