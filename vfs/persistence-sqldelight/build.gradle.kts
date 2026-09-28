plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.sqldelight)
}

dependencies {
    implementation(project(":vfs:core"))
    implementation(libs.sqlite.driver)
}
