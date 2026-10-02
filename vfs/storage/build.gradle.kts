plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // 本模块实现 Core 的 Storage 接口，公开签名直接出现 StoragePath / VfsException，故用 api。
    api(project(":vfs:core"))
    implementation(libs.opendal)
    // suspend 接口内部用 Dispatchers.IO 包裹同步的 OpenDAL 阻塞调用。
    implementation(libs.coroutines)
    // Native binary must match the running platform; a fixed classifier would put a macOS
    // .dylib on a Linux classpath. T12 verified the native actually loads and reaches the real
    // filesystem on macOS arm64; other platforms still need a real run before claiming support.
    runtimeOnly(variantOf(libs.opendal) { classifier(opendalNativeClassifier()) })
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
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
