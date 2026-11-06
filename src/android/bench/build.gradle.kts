import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    // sherpa-onnx Java API + нативные библиотеки Windows x64 (скачивает tools/fetch_models.py)
    implementation(files("../libs/sherpa-onnx-jvm-2.0.1.jar"))
    runtimeOnly(files("../libs/sherpa-onnx-native-lib-win-x64-2.0.1.jar"))
}
application {
    mainClass.set("ru.neurosteno.bench.BenchKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g", "-Dfile.encoding=UTF-8")
}
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.parentFile.parentFile // корень репозитория
}
