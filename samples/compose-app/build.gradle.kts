buildscript {
    dependencies {
        // AGP 9 supplies built-in Kotlin; align it with our compiler plugin.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
