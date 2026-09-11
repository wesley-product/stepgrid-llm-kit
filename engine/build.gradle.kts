// AGP 9 bundles Kotlin — adding org.jetbrains.kotlin.android on top fails with
// "extension with name 'kotlin' already registered".
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.wesleyproduct.llmkit.engine"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        // Same floor as device-tier: devices that load GB-sized models are all on API 26+.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Everything that decides *how* to run (backend ladder, sampler ladder, stream merging) is
    // pure Kotlin and tested here on the JVM. Only LlmEngine itself touches the native runtime,
    // and that can only be exercised on a device.
    testOptions.unitTests.all { it.useJUnitPlatform() }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
    explicitApi()
}

dependencies {
    // `api`, not `implementation`: consumers must have the runtime on their classpath anyway, and
    // declaring it transitively means one coordinate to bump instead of two drifting apart.
    api(libs.litertlm.android)
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    pom {
        name.set("stepgrid-llm-kit :: engine")
        description.set("A small, serialized wrapper over LiteRT-LM: one shared engine, GPU→CPU fallback ladder, retry-aware sampling, streamed and one-shot generation.")
    }
}
