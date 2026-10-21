// The AI egress ledger's platform-free core. Everything here is plain Kotlin, so it
// builds and unit-tests on the JVM; the Android glue (UID attribution, the tunnel,
// persistence, UI) lives in :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Same bytecode level as :app (JavaVersion.VERSION_17).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}
