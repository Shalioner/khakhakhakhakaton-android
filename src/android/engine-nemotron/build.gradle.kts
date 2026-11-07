plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.neurosteno.nemotron"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 28
        // x86_64 — для эмулятора (debug); release app фильтрует до arm64-v8a
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    api(project(":engine-audio"))
    // libonnxruntime.so (ORT 1.28.2) берётся из того же sherpa-onnx 2.0.1 AAR, что и у engine-sherpa:
    // одна зависимость → одна копия библиотеки в APK, pickFirst не нужен.
    implementation(project(":sherpa-onnx-aar"))
}
