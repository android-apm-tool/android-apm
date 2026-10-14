plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":apm-catalog"))
    implementation("org.soot-oss:sootup.java.bytecode.frontend:3.0.1")
    implementation("org.soot-oss:sootup.analysis.interprocedural:3.0.1")
    implementation("de.upb.cs.swt:heros:1.2.3")
    constraints {
        implementation("com.google.guava:guava:33.6.0-jre") {
            version { strictly("33.6.0-jre") } // Heros otherwise requests an open-ended version range.
        }
    }
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.test { useJUnitPlatform() }

// Preparation uses public tool dependencies only; customer snapshots are never inputs.
tasks.register<Sync>("prepareOfflineRuntime") {
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(layout.buildDirectory.dir("offline-runtime"))
}
