pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "neuro-stenographer"
include(":sherpa-onnx-aar", ":core", ":engine-audio", ":engine-sherpa", ":engine-llm", ":app", ":bench")
