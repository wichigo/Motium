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

        // Build config for Supabase keys
        buildConfigField("String", "SUPABASE_URL", "\"https://hjknuqqtmvbfvrmvvtxh.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhqa251cXF0bXZiZnZybXZ2dHhoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTg3MTA0MjIsImV4cCI6MjA3NDI4NjQyMn0.VLh94hdp1Q1OFcKx-yM3j2EifxC5KJjUQEyZ7eMFOIk\"")
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

    // ML Kit Text Recognition (on-device, free)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // OSMDroid for maps
    implementation(libs.osmdroid.android)

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

    // Permissions
    implementation(libs.accompanist.permissions)

    // Date/Time
    implementation(libs.kotlinx.datetime)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockito.android)

    // Compose testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}