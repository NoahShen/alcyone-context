plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
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
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
