plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

gradlePlugin {
    plugins {
        create("androidApm") {
            id = "io.github.android-apm-tool.apm"
            implementationClass = "dev.androidapm.gradle.AndroidApmPlugin"
            displayName = "Android APM"
            description = "Android APM build integration and opt-in compiler IR probe"
        }
    }
}

tasks.processResources {
    val metadata = mapOf(
        "pluginGroup" to project.group.toString(),
        "pluginVersion" to project.version.toString(),
        "kotlinVersion" to libs.versions.kotlin.get(),
    )
    inputs.properties(metadata)
    filesMatching("android-apm/plugin.properties") { expand(metadata) }
}

tasks.test { useJUnitPlatform() }
