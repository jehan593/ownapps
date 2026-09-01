plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ownapps.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ownapps.app"
        minSdk = 26
        targetSdk = 35
        // Overridable via -PappVersionCode=/-PappVersionName= (CI bumps these per build so
        // update checkers like Obtainium, which key off versionCode, see each build as new);
        // local/default builds fall back to a static version.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"

        // Real phones are arm64-v8a (current) or armeabi-v7a (older/budget) — x86/x86_64 only
        // matter for emulators, which aren't a distribution target for a sideloaded APK. Drops
        // the emulator-only native library variants from every build.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Committed keystore (not the AGP-generated one) so every build — local or CI —
            // signs with the same key. Without this, a fresh CI runner would auto-generate a
            // new ~/.android/debug.keystore each run, giving every release a different
            // signature and breaking in-place updates (Obtainium/Android reject cross-signature
            // installs as a version conflict) even though versionCode keeps increasing.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Same committed keystore as the debug signingConfig above — this is the variant CI
            // and local builds actually ship (see build-apk.yml), so it needs the same pinned key
            // for update checkers (Obtainium) and Android itself to accept it as an in-place
            // update rather than a signature conflict.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // kotlin-stdlib/kotlinx-coroutines metadata for kotlin-reflect and coroutine debug
            // probes — neither is used anywhere in this app (no reflection, no debug-probe API).
            excludes += "kotlin/**"
            excludes += "DebugProbesKt.bin"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.reorderable)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.gson)
}
