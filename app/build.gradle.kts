plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.layerwatch"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.layerwatch"
        minSdk = 35
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.4"
    }

    // Play upload key, from ~/.gradle/gradle.properties (never the repo). Without it,
    // release builds are produced unsigned.
    val uploadStore = providers.gradleProperty("LAYERWATCH_UPLOAD_STORE_FILE").orNull
    val uploadPassword = providers.gradleProperty("LAYERWATCH_UPLOAD_PASSWORD").orNull
    signingConfigs {
        if (uploadStore != null && uploadPassword != null) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = uploadPassword
                keyAlias = "upload"
                keyPassword = uploadPassword
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the Play build, so a device can run both.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.inspector.frame)
    implementation(libs.glance.appwidget)

    testImplementation(libs.junit)
}
