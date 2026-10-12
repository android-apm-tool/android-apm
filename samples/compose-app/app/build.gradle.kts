plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.github.android-apm-tool.apm")
}

android {
    namespace = "dev.androidapm.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.androidapm.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.navigation3.ui)
}
