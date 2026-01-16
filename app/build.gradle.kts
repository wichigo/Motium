plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    // alias(libs.plugins.hilt) // Disable for now
}

android {
    namespace = "com.application.motium"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.application.motium"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build config for Supabase keys (self-hosted)
        buildConfigField("String", "SUPABASE_URL", "\"http://176.168.117.243:8000\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiAiSFMyNTYiLCAidHlwIjogIkpXVCJ9.eyJyb2xlIjogImFub24iLCAiaXNzIjogInN1cGFiYXNlIiwgImlhdCI6IDE3MDQwNjcyMDAsICJleHAiOiAxODYxOTIwMDAwfQ.LUYd4QDV4W3yQc-HgbBHCmsjL1fkPU4xfTdlhabLN4M\"")

        // Stripe configuration (publishable key - safe to include in client)
        buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"pk_test_51RLY4eCsRT1u49RI1vDxDBpBaR0iphMbJk47LKowyTKII2wTwbkYScTIpr7kTOQBQ4dEOTkztv767Pw75MWsBB1y00xLwmOflF\"")
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        // Build type dédié pour les tests de batterie (non-debuggable)
        create("battery") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".battery"
            versionNameSuffix = "-battery"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.addAll(listOf("-opt-in=kotlin.time.ExperimentalTime"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Remove composeOptions - handled by compose-compiler plugin now

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material") // For PullRefresh
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)

    // ViewModel and Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Supabase for backend and authentication
    implementation(libs.supabase.postgrest.kt)
    implementation(libs.supabase.auth.kt)
    implementation(libs.supabase.realtime.kt)
    implementation(libs.supabase.storage.kt)
    implementation(libs.ktor.client.android)
    implementation(libs.multiplatform.settings)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Location Services
    implementation(libs.play.services.location)

    // Google Sign-In
    implementation(libs.play.services.auth)

    // Credential Manager for password autofill/save
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")

    // ML Kit Text Recognition (on-device, free)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // MapLibre for maps (supports MVT vector tiles)
    implementation("org.maplibre.gl:android-sdk:11.8.4")
    implementation("org.maplibre.gl:android-plugin-annotation-v9:3.0.1")

    // Networking
    implementation(libs.retrofit2.retrofit)
    implementation(libs.retrofit2.converter.moshi)
    implementation(libs.okhttp3.logging.interceptor)
    implementation(libs.moshi.kotlin)

    // Dependency Injection - Disabled for now
    // implementation(libs.hilt.android)
    // implementation(libs.androidx.hilt.navigation.compose)
    // ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // WorkManager for background sync
    implementation(libs.androidx.work.runtime.ktx)

    // Security (EncryptedSharedPreferences)
    implementation(libs.androidx.security.crypto)

    // PDF Generation
    implementation(libs.itextpdf)

    // Excel Generation (Apache POI)
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // Stripe Payments
    implementation("com.stripe:stripe-android:22.0.0")

    // Permissions
    implementation(libs.accompanist.permissions)

    // Date/Time
    implementation(libs.kotlinx.datetime)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.room:room-testing:2.8.3")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation("io.mockk:mockk-android:1.13.9")

    // Compose testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}