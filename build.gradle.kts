import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless) apply false
}

// gradle/toolchain.versions is the single source for the project-local JDK and Gradle versions.
// scripts/dev reads the same file, so the guard and the bootstrap entry can never disagree.
val toolchainVersions: Map<String, String> =
    file("gradle/toolchain.versions")
        .takeIf { it.isFile }
        ?.readLines()
        ?.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                null
            } else {
                val key = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=', "").trim()
                require(key.isNotEmpty() && value.isNotEmpty()) { "Malformed line in gradle/toolchain.versions: $trimmed" }
                key to value
            }
        }
        ?.toMap()
        ?: error("Missing gradle/toolchain.versions: it holds the JDK and Gradle versions and checksums.")
fun toolchainValue(
    key: String,
): String = toolchainVersions[key] ?: error("gradle/toolchain.versions is missing '$key'")

check(File(System.getProperty("java.home")).canonicalFile ==
    rootDir.resolve(".local/jdk/jdk-${toolchainValue("jdk.version")}/Contents/Home").canonicalFile
) {
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

        // 格式检查挂在 check 上：build 因风格问题失败。规则见根 .editorconfig，版本见 gradle/libs.versions.toml。
        apply(plugin = "com.diffplug.spotless")
        extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
                ktlint(libs.versions.ktlint.get())
            }
            kotlinGradle {
                target("*.gradle.kts")
                ktlint(libs.versions.ktlint.get())
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn("spotlessCheck") }
    }
}

tasks.wrapper {
    gradleVersion = toolchainValue("gradle.version")
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = toolchainValue("gradle.sha256")
}
