plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(project(":apm-catalog"))
    testImplementation(project(":apm-catalog"))
    implementation(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.kotlin.gradle.plugin)
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

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(project(":apm-catalog").tasks.named("jar"))
}

tasks.jar {
    val catalog = project(":apm-catalog").tasks.named("jar")
    dependsOn(catalog)
    from(catalog.map { zipTree(it.outputs.files.singleFile) }) { exclude("META-INF/MANIFEST.MF") }
}
