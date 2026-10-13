pluginManagement {
    includeBuild("../..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "android-apm-compose-sample"
include(":app")

// Plugin resolution and compiler artifact resolution use separate classpaths.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.android-apm-tool:apm-compiler-plugin"))
            .using(project(":apm-compiler-plugin"))
    }
}
