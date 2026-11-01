plugins {
    // Kotlin plugin version comes from the repository's gradle/libs.versions.toml
    // (kotlin-jvm alias) — no hardcoded version in this build file.
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories { mavenCentral() }

// The Kotlin daemon must keep its run files inside this probe directory
// (no writes into the user-global directory).
System.setProperty("kotlin.daemon.options", "runFilesPath=${rootDir.resolve(".local/tmp/kotlin-daemon")}")

kotlin { jvmToolchain(21) }

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * org.xerial:sqlite-jdbc has no alias in the repository version catalog.
 * Its version is therefore taken from what SQLDelight's sqlite-driver actually resolves
 * (the driver brings Xerial as a runtime dependency), so there is no hardcoded constant
 * in this build file. Failure to derive it is a hard error, never a fallback value.
 */
val sqldelightVersion = versionCatalog.findVersion("sqldelight").get().requiredVersion
val sqliteDriverFiles = configurations
    .detachedConfiguration(
        dependencies.create("app.cash.sqldelight:sqlite-driver:$sqldelightVersion"),
    ).apply { isTransitive = true }
    .resolve()
val xerialFile = sqliteDriverFiles.firstOrNull { it.name.startsWith("sqlite-jdbc-") }
    ?: throw GradleException(
        "Could not derive the org.xerial:sqlite-jdbc version from the SQLDelight sqlite-driver " +
            "dependency graph (SQLDelight $sqldelightVersion). Add an explicit alias to " +
            "gradle/libs.versions.toml instead of hardcoding a version here.",
    )
// Artifact file name is "sqlite-jdbc-<version>.jar".
val xerialSqliteJdbcVersion: String =
    xerialFile.name.removePrefix("sqlite-jdbc-").removeSuffix(".jar")
dependencies {
    // SQLDelight JDBC driver: version from the repository version catalog (sqldelight alias).
    implementation(versionCatalog.findLibrary("sqlite-driver").get())
    // Xerial SQLite JDBC: version derived from the resolved SQLDelight driver graph (see above).
    implementation("org.xerial:sqlite-jdbc:$xerialSqliteJdbcVersion")
}

application { mainClass.set("cm1probe.MainKt") }

tasks.register("printResolvedVersions") {
    doLast {
        println("kotlin            = ${versionCatalog.findVersion("kotlin").get().requiredVersion}")
        println("sqldelight        = ${versionCatalog.findVersion("sqldelight").get().requiredVersion}")
        println("sqlite-jdbc       = $xerialSqliteJdbcVersion")
    }
}
