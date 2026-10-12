plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(libs.kotlin.compiler)
    testImplementation(libs.kotlin.compiler)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    dependsOn(pluginJar)
    inputs.file(pluginJar)
    doFirst { systemProperty("apm.test.pluginJar", pluginJar.get().asFile.absolutePath) }
}
