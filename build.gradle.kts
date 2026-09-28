import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
}

check(File(System.getProperty("java.home")).canonicalFile ==
    rootDir.resolve(".local/jdk/jdk-21.0.12.1+1/Contents/Home").canonicalFile) {
    "Use scripts/dev bootstrap and scripts/dev gradle: the build requires the project-local JDK."
}

// Kotlin daemon discovery files must stay alongside the project toolchain.
System.setProperty("kotlin.daemon.options", "runFilesPath=${rootDir.resolve(".local/tmp/kotlin-daemon")}")

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            kotlinDaemonJvmArgs = listOf(
                "-Xmx1g",
                "-Djava.io.tmpdir=${rootDir.resolve(".local/tmp")}",
            )
        }
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        }
    }
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
}
