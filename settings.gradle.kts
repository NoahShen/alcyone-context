pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "alcyone-context"
include(
    ":common",
    ":vfs:api",
    ":vfs:core",
    ":vfs:storage",
    ":vfs:persistence",
    ":vfs:runtime",
    ":integration-tests:vfs",
    ":vfs:git-backup",
    ":context:api",
    ":context:core",
    ":context:persistence-sqldelight",
    ":context:runtime",
    ":context:server",
    ":integration-tests:context",
)
