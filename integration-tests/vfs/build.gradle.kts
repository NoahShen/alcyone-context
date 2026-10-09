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

// T24：真实 WebDAV 用例单独一个 source set 和任务，**不挂进 check / build**。
//
// 为什么不用 @EnabledIfEnvironmentVariable：那样默认跑法会留下「已跳过」的记录，
// 看起来像跑了又没跑。这里干脆让这批用例不进默认任务；唯一入口是 ./scripts/webdav-check，
// 它负责起服务并在服务缺失时直接失败——跳过不等于通过。
sourceSets {
    create("webdavTest") {
        compileClasspath = sourceSets["main"].output + configurations["testCompileClasspath"]
        runtimeClasspath = output + sourceSets["main"].output + configurations["testRuntimeClasspath"]
    }
}

tasks.register<Test>("webdavTest") {
    description = "Runs the real WebDAV integration tests; use ./scripts/webdav-check so the fixture service is started."
    group = "verification"
    testClassesDirs = sourceSets["webdavTest"].output.classesDirs
    classpath = sourceSets["webdavTest"].runtimeClasspath
}
