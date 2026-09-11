// AGP 9 는 코틀린을 내장한다 — org.jetbrains.kotlin.android 를 같이 붙이면
// "extension with name 'kotlin' already registered" 로 빌드가 죽는다.
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
        // 안드로이드 8.0. LiteRT-LM 자체는 더 낮은 API 도 받지만, 이 판단이 쓰이는 자리는
        // GB 단위 모델을 올리는 기기라 그 아래를 지원하는 것이 의미가 없다.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 등급 판단은 순수 함수라 로보렉트릭 없이 평범한 유닛 테스트로 검사한다.
    // 안드로이드를 읽는 부분(DeviceSpecs.read)만 기기에서 도는 코드로 남는다.
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
