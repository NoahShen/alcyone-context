plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(libs.uuid)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
