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
// 未知 OS 或 CPU 明确拒绝，禁止静默落入 Linux x64；与 scripts/dev 统一明确拒绝 macOS x64（R1）。
fun opendalNativeClassifier(
    osName: String = System.getProperty("os.name"),
    osArch: String = System.getProperty("os.arch"),
): String {
    val isMac = osName.startsWith("Mac")
    val isLinux = osName.startsWith("Linux")
    val isArm = osArch in setOf("aarch64", "arm64")
    val isX64 = osArch in setOf("x86_64", "amd64")
    return when {
        isMac && isArm -> "osx-aarch_64"
        isLinux && isArm -> "linux-aarch_64"
        isLinux && isX64 -> "linux-x86_64"
        else -> error("Unsupported OS/CPU for OpenDAL native: os.name='$osName', os.arch='$osArch'")
    }
}

// 供测试执行真实构建逻辑（R1）：测试通过 project.tasks.named("resolveOpendalClassifier") 或直接调用此函数
tasks.register("resolveOpendalClassifier") {
    val targetOs = project.findProperty("targetOs") as? String ?: System.getProperty("os.name")
    val targetArch = project.findProperty("targetArch") as? String ?: System.getProperty("os.arch")
    val classifier = opendalNativeClassifier(targetOs, targetArch)
    extra["classifier"] = classifier
    doLast {
        println("classifier=$classifier")
    }
}
