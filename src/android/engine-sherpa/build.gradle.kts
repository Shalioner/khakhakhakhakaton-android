plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.neurosteno.sherpa"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    api(project(":engine-audio"))
    // sherpa-onnx 2.0.1 AAR (Apache-2.0), скачивается tools/fetch_models.py в src/android/libs
    api(project(":sherpa-onnx-aar"))
}
