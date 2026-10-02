plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":vfs:api"))
    implementation(project(":vfs:core"))
    implementation(project(":vfs:storage"))
    implementation(project(":vfs:persistence"))
}
