plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Kotlin daemon 发现文件与临时状态限定在项目内
System.setProperty("kotlin.daemon.options", "runFilesPath=${rootDir.resolve("../../../.local/tmp/kotlin-daemon")}")

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

repositories {
    maven {
        name = "projectLocal"
        url = uri(rootDir.resolve("../../../.local/sdk-repository"))
        content {
            includeGroup("com.github.noahshen.alcyone")
        }
    }
    mavenCentral {
        content {
            excludeGroup("com.github.noahshen.alcyone")
        }
    }
}

kotlin {
    jvmToolchain(21)
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    kotlinDaemonJvmArgs = listOf(
        "-Xmx1g",
        "-Djava.io.tmpdir=${rootDir.resolve("../../../.local/tmp")}",
    )
}

dependencies {
    implementation("com.github.noahshen.alcyone:alcyone-vfs-runtime:0.1.0-SNAPSHOT")
    implementation("com.github.noahshen.alcyone:alcyone-vfs-git-backup:0.1.0-SNAPSHOT")
    implementation(versionCatalog.findLibrary("coroutines").get())
    runtimeOnly("org.slf4j:slf4j-simple:${versionCatalog.findVersion("slf4j").get().requiredVersion}")
}

application {
    mainClass.set("com.github.noahshen.alcyone.validation.consumer.PluginConsumerMainKt")
}

tasks.register("printClasspath") {
    dependsOn("classes")
    doLast {
        val cp = sourceSets["main"].runtimeClasspath.files
        println("=== CONSUMER RUNTIME CLASSPATH ===")
        cp.forEach { println(it.absolutePath) }
    }
}
