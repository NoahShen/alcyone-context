pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "alcyone-context"
include(":common", ":vfs:api", ":vfs:core", ":vfs:storage", ":vfs:persistence", ":vfs:runtime", ":integration-tests:vfs")
