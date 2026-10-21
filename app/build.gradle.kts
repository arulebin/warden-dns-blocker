plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.arulebin.warden"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arulebin.warden"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                // Bind hev-socks5-tunnel's JNI natives to com.arulebin.warden.TProxy.
                cFlags += listOf("-DPKGNAME=com/arulebin/warden", "-DCLSNAME=TProxy")
                arguments += listOf("APP_CFLAGS+=-O3")
            }
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(project(":ledger"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // The Compose BOM pins all Compose artifact versions together so they stay
    // mutually compatible; the individual deps below omit their versions.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Tooling for @Preview rendering — debug builds only.
    debugImplementation(libs.androidx.ui.tooling)

    // JVM unit tests (app/src/test) for the platform-free parts: rules, lists, relay.
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
