plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(project(":vfs:api"))
    implementation(libs.uuid)
    implementation(libs.slf4j.api)
}
