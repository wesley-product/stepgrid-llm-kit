plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

// 안드로이드에 안 붙인다 — 큰 파일을 이어받는 판단은 플랫폼과 상관없는 규칙이다.
// 서버에서도, 데스크톱에서도 같은 답이 나와야 한다.
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
