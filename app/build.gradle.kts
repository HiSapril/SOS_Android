plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.javafinal"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.javafinal"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val apiBaseUrl = providers.gradleProperty("API_BASE_URL").orElse("http://10.0.2.2:8000/").get()
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // MapLibre Native SDK (Bản đồ Vector mã nguồn mở)
    implementation(libs.maplibre)

    // GPS / Location
    implementation(libs.play.services.location)

    // Real-time WebSocket & Network
    implementation(libs.okhttp)

    // JSON parsing
    implementation(libs.gson)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
