plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

// Not an Android module on purpose: whether a large download can be resumed is a rule about HTTP,
// not about a platform. It must give the same answer on a server or a desktop.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
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

tasks.withType<Test>().configureEach { useJUnitPlatform() }

mavenPublishing {
    pom {
        name.set("stepgrid-llm-kit :: resume")
        description.set("Decide whether a half-finished HTTP download can be resumed, from what the server actually sent back.")
    }
}
