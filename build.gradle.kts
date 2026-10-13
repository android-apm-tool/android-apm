plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "io.github.android-apm-tool"
    version = "0.1.0-SNAPSHOT"
}

tasks.named("build") {
    dependsOn(":apm-gradle-plugin:build", ":apm-compiler-plugin:build")
}

tasks.named("check") {
    dependsOn(":apm-gradle-plugin:check", ":apm-compiler-plugin:check")
}
