pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        // Single source of truth for versions: the repository's gradle/libs.versions.toml.
        // Same pattern as tools/validation/t31-consumer.
        create("libs") {
            from(files("../../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "cm1-compat-probe"
