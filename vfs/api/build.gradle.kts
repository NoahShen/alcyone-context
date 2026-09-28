plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.coroutines)
    api(libs.serialization.json)
}
