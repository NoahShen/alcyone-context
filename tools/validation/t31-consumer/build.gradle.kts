plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories {
    maven {
        name = "projectLocal"
        url = uri(rootDir.resolve("../../../.local/sdk-repository"))
        content {
            includeGroup("com.github.noahshen.alcyone")
        }
    }
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.github.noahshen.alcyone:alcyone-vfs-runtime:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass.set("com.github.noahshen.alcyone.validation.consumer.ConsumerMainKt")
}

tasks.register("printClasspath") {
    dependsOn("classes")
    doLast {
        val cp = sourceSets["main"].runtimeClasspath.files
        println("=== CONSUMER RUNTIME CLASSPATH ===")
        cp.forEach { println(it.absolutePath) }
    }
}
