plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// 多个验收进程共享工作树时，可按调用指定独立的二进制与 XML 输出目录。
// 未指定时保留 Gradle 默认路径，不改变既有开发与 CI 行为。
tasks.named<Test>("test") {
    providers.gradleProperty("contextDreamingTestDir").orNull?.let { output ->
        val directory = layout.projectDirectory.dir(output)
        binaryResultsDirectory.set(directory.dir("binary"))
        reports.junitXml.outputLocation.set(directory.dir("xml"))
        reports.html.outputLocation.set(directory.dir("html"))
    }
}

dependencies {
    testImplementation(projects.context.runtime)
    testImplementation(projects.context.server)
    // B3：集成测试直接使用持久化装配（ContextDatabaseFactory / ContextDatabaseImpl），不经 Runtime。
    testImplementation(projects.context.persistenceSqldelight)
    // CT12：端到端测试通过公共 Factory 构造 Runtime，需要测试级 VfsRuntimeConfig 类型（生产依赖图不变）。
    testImplementation(projects.vfs.runtime)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
