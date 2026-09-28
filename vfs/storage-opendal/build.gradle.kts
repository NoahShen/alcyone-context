plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(project(":vfs:core"))
    implementation(libs.opendal)
    runtimeOnly(variantOf(libs.opendal) { classifier("osx-aarch_64") })
}

check(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64") {
    "OpenDAL native is verified only on macOS arm64; other platforms require separate validation."
}
