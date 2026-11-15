plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    application
}

application {
    mainClass = "com.github.noahshen.alcyone.context.memory.server.StandaloneMainKt"
    applicationName = "alcyone-context"
    // Standalone only: suppress no-provider warnings; embedded library behavior is unchanged.
    applicationDefaultJvmArgs = listOf("-Dslf4j.internal.verbosity=ERROR")
}

val writeProcessTestClasspath by tasks.registering {
    val runtime = configurations.testRuntimeClasspath
    val classes = sourceSets.test.get().output + sourceSets.main.get().output
    inputs.files(runtime, classes)
    val output = layout.buildDirectory.file("standalone-process-test.classpath")
    outputs.file(output)
    doLast {
        output.get().asFile.writeText((classes.files + runtime.get().files).joinToString(File.pathSeparator))
    }
}

tasks.test {
    dependsOn(writeProcessTestClasspath, tasks.named("installDist"))
    inputs.files(writeProcessTestClasspath.map { it.outputs.files })
}

dependencies {
    implementation(projects.context.runtime)
    // Standalone upgrade 只调用公共 ContextOfflineBackup；不访问 JDBC/SQLDelight/Repository。
    implementation(projects.context.persistenceSqldelight)
    // Core 内部工具（IngestionTimestampText）供 HTTP 原始时间文本按同一契约解析；不访问 Persistence/JDBC。
    implementation(projects.context.core)
    implementation(libs.serialization.json)
    implementation(libs.snakeyaml)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.test.host)
    // 测试用真实 VFS LocalFs 配置组装（生产 Server 不直接依赖 VFS 类型）。
    // managed 公共 Factory 签名需要 VfsRuntimeConfig；生产 Server 不直接访问 VFS。
    implementation(projects.vfs.runtime)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
