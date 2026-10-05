plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":vfs:api"))
    // Runtime 的公开签名直接出现 VfsLimits（宿主用它配限额），故用 api。
    api(project(":vfs:core"))
    implementation(project(":vfs:storage"))
    implementation(project(":vfs:persistence"))
    implementation(libs.coroutines)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
