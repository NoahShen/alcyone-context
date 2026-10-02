plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("VfsDatabase") {
            packageName.set("com.github.noahshen.alcyone.context.vfs.persistence.sqldelight")
        }
    }
}

dependencies {
    // 本模块实现 Core 的 Repository 接口，公开签名直接出现 VfsPath / NodeRecord / VfsException，故用 api。
    api(project(":vfs:core"))
    implementation(libs.sqlite.driver)
    // suspend 接口内部用 Dispatchers.IO 包裹 JDBC 阻塞调用。
    implementation(libs.coroutines)
    // metadata.payload 以 JSON 文本存储（见 MetadataQueries.kt）。
    implementation(libs.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
