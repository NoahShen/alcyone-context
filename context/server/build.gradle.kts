plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    implementation(projects.context.runtime)
    // Core 内部工具（IngestionTimestampText）供 HTTP 原始时间文本按同一契约解析；不访问 Persistence/JDBC。
    implementation(projects.context.core)
    implementation(libs.serialization.json)
    implementation(libs.snakeyaml)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.test.host)
    // 测试用真实 VFS LocalFs 配置组装（生产 Server 不直接依赖 VFS 类型）。
    testImplementation(projects.vfs.runtime)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
