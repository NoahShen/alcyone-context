plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // Core 的公开签名直接出现 VfsPath / NodeId / VfsException，Adapter 模块要能编译这些实现，故用 api。
    api(project(":vfs:api"))
    implementation(libs.uuid)
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
