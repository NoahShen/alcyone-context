plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(project(":vfs:core"))
    implementation(libs.opendal)
    // Native binary must match the running platform; a fixed classifier would put a macOS
    // .dylib on a Linux classpath. Backends and native loading are validated in T12, so this
    // module only has to resolve, not prove the native works.
    runtimeOnly(variantOf(libs.opendal) { classifier(opendalNativeClassifier()) })
}

// Classifier names published by org.apache.opendal:opendal; verified present for 0.50.6.
fun opendalNativeClassifier(): String {
    val onMac = System.getProperty("os.name").startsWith("Mac")
    val onArm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
    return when {
        onMac && onArm -> "osx-aarch_64"
        onMac -> "osx-x86_64"
        onArm -> "linux-aarch_64"
        else -> "linux-x86_64"
    }
}
