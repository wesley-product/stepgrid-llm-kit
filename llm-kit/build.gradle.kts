// Umbrella artifact: one coordinate that brings in engine + device-tier + resume.
// It has no code of its own. The three modules stay separately published for anyone who wants
// only one of them (resume, for instance, is pure JVM and needs no Android at all).
// It must be an Android library because engine is an AAR and a plain JVM module cannot depend on one.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.wesleyproduct.llmkit"
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    api(project(":engine"))
    api(project(":device-tier"))
    api(project(":resume"))
}

mavenPublishing {
    pom {
        name.set("stepgrid-llm-kit")
        description.set("Everything in stepgrid-llm-kit in one dependency: the LiteRT-LM engine wrapper, device tiering and resumable-download decisions.")
    }
}
