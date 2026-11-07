cmake_minimum_required(VERSION 3.22.1)
project(neurort CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libneurort.so: JNI к onnxruntime C API. С libonnxruntime.so НЕ линкуется — она открывается через dlopen
# (её кладёт в APK sherpa-onnx 2.0.1 AAR), поэтому в APK остаётся одна копия ORT и AGP не упаковывает вторую.
add_library(neurort SHARED neurort_jni.cpp)
target_include_directories(neurort PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/onnxruntime)
target_compile_options(neurort PRIVATE -O2 -fvisibility=hidden -Wall -Wextra)
target_link_libraries(neurort PRIVATE android log dl)
# Выравнивание сегментов 16 КБ: устройства Android 15+ со страницами 16 КБ иначе не загрузят .so.
target_link_options(neurort PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--as-needed")
