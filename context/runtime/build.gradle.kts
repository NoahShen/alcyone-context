plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(projects.context.api)
    implementation(projects.context.core)
    implementation(projects.context.persistenceSqldelight)
    implementation(projects.vfs.runtime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.serialization.json)
    implementation(libs.cron.utils)
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
