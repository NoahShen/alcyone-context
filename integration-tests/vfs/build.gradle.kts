plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(project(":vfs:runtime"))
    // Runtime 只把 :vfs:api 透出来；跨模块组合要用的 Core、SQLite 与 Local FS Adapter 自己声明（T13）。
    testImplementation(project(":vfs:core"))
    testImplementation(project(":vfs:persistence"))
    testImplementation(project(":vfs:storage"))
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
