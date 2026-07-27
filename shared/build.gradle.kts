import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure-Kotlin module. Nothing here may depend on Android or on JVM-only APIs
// (java.time, java.io, java.util.*) — everything must survive being moved to
// src/commonMain/kotlin if this project converts to Kotlin Multiplatform.
//
// Being a kotlin-jvm rather than an android-library module enforces half of
// that mechanically: Android artifacts are .aar files and cannot resolve here.
// The java.* half is on us. Prefer kotlinx-datetime, kotlinx-serialization,
// kotlinx-coroutines and Okio over their JDK equivalents.

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Ktor's CIO server engine and kotlinx-* are all multiplatform, so the
    // clipboard server keeps its "could move to commonMain" property.
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}
