plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("ContextDatabaseSqlite") {
            packageName.set("com.github.noahshen.alcyone.context.memory.persistence.sqldelight")
        }
    }
}

dependencies {
    api(projects.context.api)
    api(projects.context.core)
    // TimeCodec 在本模块被直接引用，显式声明而不依赖 Core 的传递暴露。
    implementation(projects.common)
    implementation(libs.sqlite.driver)
    implementation(libs.sqlite.jdbc)
    implementation(libs.coroutines)
    // JSON 列编解码（messages_json 等）使用 kotlinx.serialization，不引入额外框架。
    implementation(libs.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
