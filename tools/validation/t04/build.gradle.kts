plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("app.cash.sqldelight") version "2.1.0"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
sqldelight { databases { create("ProbeDatabase") { packageName.set("probe") } } }
dependencies {
    implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
    implementation("org.apache.opendal:opendal:0.50.6")
    runtimeOnly("org.apache.opendal:opendal:0.50.6:osx-aarch_64")
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass.set("ProbeKt") }
