plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":apm-catalog"))
    compileOnly(libs.kotlin.compiler)
    testImplementation(libs.kotlin.compiler)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Compiler plugins are loaded as standalone JARs, without Gradle runtime dependencies.
tasks.jar {
    val catalog = project(":apm-catalog").tasks.named("jar")
    dependsOn(catalog)
    from(catalog.map { zipTree(it.outputs.files.singleFile) }) { exclude("META-INF/MANIFEST.MF") }
}

tasks.test {
    useJUnitPlatform()
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    dependsOn(pluginJar)
    inputs.file(pluginJar)
    doFirst { systemProperty("apm.test.pluginJar", pluginJar.get().asFile.absolutePath) }
}
