// AGP 9 bundles Kotlin — adding org.jetbrains.kotlin.android on top fails with
// "extension with name 'kotlin' already registered".
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.wesleyproduct.llmkit.devicetier"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        // Android 8.0. LiteRT-LM itself accepts lower, but this decision is only ever made on
        // devices that load GB-sized models; supporting anything below is meaningless.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The tier decision is a pure function, tested as plain JVM unit tests without Robolectric.
    // Only the part that reads Android (readDeviceSpecs) stays as device-only code.
    testOptions.unitTests.all { it.useJUnitPlatform() }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
    explicitApi()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    pom {
        name.set("stepgrid-llm-kit :: device-tier")
        description.set("Pick an on-device model size from real Android device specs (SoC class + RAM + cores).")
    }
}
