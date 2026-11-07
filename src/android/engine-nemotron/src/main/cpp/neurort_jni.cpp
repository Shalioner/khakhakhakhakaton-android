// libneurort.so — тонкая JNI-обёртка над C API onnxruntime (OrtApi) для Nemotron-3-Diarization.
//
// Своей копии onnxruntime нет: берём libonnxruntime.so (ORT 1.28.2), которую уже кладёт в APK
// sherpa-onnx 2.0.1 AAR. Библиотека не линкуется (нет DT_NEEDED), а открывается через dlopen по имени и
// OrtGetApiBase() ищется через dlsym. Kotlin заранее зовёт System.loadLibrary("onnxruntime"), поэтому
// dlopen возвращает уже загруженный экземпляр — в процессе одна ORT на sherpa и Nemotron, pickFirst не нужен.
// Заголовки — onnxruntime_c_api.h v1.28.2 (MIT, third_party/onnxruntime).
//
// API: create(path, threads) -> handle; inputNames/outputNames; run(handle, names, data[], shapes[]) ->
// [data0, shape0, data1, shape1, ...] (только float32); release(handle). Ошибки ORT -> RuntimeException.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

#define LOG_TAG "neurort"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

const OrtApi* g_api = nullptr;
OrtEnv* g_env = nullptr;
std::string g_init_error;
std::once_flag g_once;

void InitOnce() {
    void* h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) { g_init_error = std::string("dlopen libonnxruntime.so: ") + dlerror(); return; }
    using GetApiBaseFn = const OrtApiBase* (*)();
    auto get_base = reinterpret_cast<GetApiBaseFn>(dlsym(h, "OrtGetApiBase"));
    if (!get_base) { g_init_error = "OrtGetApiBase not found"; return; }
    const OrtApiBase* base = get_base();
    // Структура OrtApi только дополняется: берём нашу версию заголовка, иначе — самую старую, где есть всё нужное.
    const OrtApi* api = base->GetApi(ORT_API_VERSION);
    for (uint32_t v = ORT_API_VERSION - 1; !api && v >= 16; --v) api = base->GetApi(v);
    if (!api) { g_init_error = std::string("OrtApi unsupported by runtime ") + base->GetVersionString(); return; }
    OrtEnv* env = nullptr;
    OrtStatus* st = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "neurort", &env);
    if (st) { g_init_error = api->GetErrorMessage(st); api->ReleaseStatus(st); return; }
    g_api = api;
    g_env = env;
    LOGI("onnxruntime %s", base->GetVersionString());
}

bool EnsureApi(JNIEnv* env) {
    std::call_once(g_once, InitOnce);
    if (g_api) return true;
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(ex, g_init_error.c_str());
    return false;
}

// true — ошибка, исключение уже брошено
bool Check(JNIEnv* env, OrtStatus* st) {
    if (!st) return false;
    std::string msg = g_api->GetErrorMessage(st);
    g_api->ReleaseStatus(st);
    LOGE("%s", msg.c_str());
    jclass ex = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(ex, msg.c_str());
    return true;
}

struct Session {
    OrtSession* session = nullptr;
    std::vector<std::string> inputs;
    std::vector<std::string> outputs;
    ~Session() { if (session) g_api->ReleaseSession(session); }
};

jobjectArray ToJava(JNIEnv* env, const std::vector<std::string>& v) {
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(v.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < v.size(); ++i) {
        jstring s = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

struct ValueGuard {
    std::vector<OrtValue*> v;
    ~ValueGuard() { for (auto* x : v) if (x) g_api->ReleaseValue(x); }
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ru_neurosteno_nemotron_OrtNative_version(JNIEnv* env, jclass) {
    if (!EnsureApi(env)) return nullptr;
    void* h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_NOLOAD);
    auto get_base = h ? reinterpret_cast<const OrtApiBase* (*)()>(dlsym(h, "OrtGetApiBase")) : nullptr;
    return env->NewStringUTF(get_base ? get_base()->GetVersionString() : "?");
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_neurosteno_nemotron_OrtNative_create(JNIEnv* env, jclass, jstring jpath, jint threads) {
    if (!EnsureApi(env)) return 0;
    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    std::string path(cpath);
    env->ReleaseStringUTFChars(jpath, cpath);

    OrtSessionOptions* so = nullptr;
    if (Check(env, g_api->CreateSessionOptions(&so))) return 0;
    std::unique_ptr<OrtSessionOptions, void (*)(OrtSessionOptions*)> so_guard(so, [](OrtSessionOptions* p) { g_api->ReleaseSessionOptions(p); });
    if (Check(env, g_api->SetIntraOpNumThreads(so, threads > 0 ? threads : 1))) return 0;
    if (Check(env, g_api->SetInterOpNumThreads(so, 1))) return 0;
    if (Check(env, g_api->SetSessionExecutionMode(so, ORT_SEQUENTIAL))) return 0;
    if (Check(env, g_api->SetSessionGraphOptimizationLevel(so, ORT_ENABLE_ALL))) return 0;

    auto s = std::make_unique<Session>();
    if (Check(env, g_api->CreateSession(g_env, path.c_str(), so, &s->session))) return 0;

    OrtAllocator* alloc = nullptr;
    if (Check(env, g_api->GetAllocatorWithDefaultOptions(&alloc))) return 0;
    size_t n = 0;
    if (Check(env, g_api->SessionGetInputCount(s->session, &n))) return 0;
    for (size_t i = 0; i < n; ++i) {
        char* name = nullptr;
        if (Check(env, g_api->SessionGetInputName(s->session, i, alloc, &name))) return 0;
        s->inputs.emplace_back(name);
        g_api->AllocatorFree(alloc, name);
    }
    if (Check(env, g_api->SessionGetOutputCount(s->session, &n))) return 0;
    for (size_t i = 0; i < n; ++i) {
        char* name = nullptr;
        if (Check(env, g_api->SessionGetOutputName(s->session, i, alloc, &name))) return 0;
        s->outputs.emplace_back(name);
        g_api->AllocatorFree(alloc, name);
    }
    LOGI("session %s: %zu inputs, %zu outputs, %d threads", path.c_str(), s->inputs.size(), s->outputs.size(), threads);
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ru_neurosteno_nemotron_OrtNative_inputNames(JNIEnv* env, jclass, jlong h) {
    return ToJava(env, reinterpret_cast<Session*>(h)->inputs);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ru_neurosteno_nemotron_OrtNative_outputNames(JNIEnv* env, jclass, jlong h) {
    return ToJava(env, reinterpret_cast<Session*>(h)->outputs);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ru_neurosteno_nemotron_OrtNative_run(JNIEnv* env, jclass, jlong h, jobjectArray jnames,
                                          jobjectArray jdata, jobjectArray jshapes) {
    if (!EnsureApi(env)) return nullptr;
    auto* s = reinterpret_cast<Session*>(h);
    const jsize n_in = env->GetArrayLength(jnames);

    OrtMemoryInfo* mem = nullptr;
    if (Check(env, g_api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem))) return nullptr;
    std::unique_ptr<OrtMemoryInfo, void (*)(OrtMemoryInfo*)> mem_guard(mem, [](OrtMemoryInfo* p) { g_api->ReleaseMemoryInfo(p); });

    // Копии входов живут до конца Run (Critical-доступ на время долгого Run держать нельзя).
    std::vector<std::vector<float>> bufs(n_in);
    std::vector<std::string> names(n_in);
    std::vector<const char*> name_ptrs(n_in);
    ValueGuard in_vals;
    in_vals.v.assign(n_in, nullptr);
    for (jsize i = 0; i < n_in; ++i) {
        auto jn = static_cast<jstring>(env->GetObjectArrayElement(jnames, i));
        const char* cn = env->GetStringUTFChars(jn, nullptr);
        names[i] = cn;
        env->ReleaseStringUTFChars(jn, cn);
        env->DeleteLocalRef(jn);
        name_ptrs[i] = names[i].c_str();

        auto jd = static_cast<jfloatArray>(env->GetObjectArrayElement(jdata, i));
        auto js = static_cast<jlongArray>(env->GetObjectArrayElement(jshapes, i));
        const jsize len = env->GetArrayLength(jd);
        bufs[i].resize(static_cast<size_t>(len));
        env->GetFloatArrayRegion(jd, 0, len, bufs[i].data());
        std::vector<int64_t> shape(static_cast<size_t>(env->GetArrayLength(js)));
        env->GetLongArrayRegion(js, 0, static_cast<jsize>(shape.size()), reinterpret_cast<jlong*>(shape.data()));
        env->DeleteLocalRef(jd);
        env->DeleteLocalRef(js);
        if (Check(env, g_api->CreateTensorWithDataAsOrtValue(mem, bufs[i].data(), bufs[i].size() * sizeof(float),
                                                             shape.data(), shape.size(),
                                                             ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in_vals.v[i]))) {
            return nullptr;
        }
    }

    const size_t n_out = s->outputs.size();
    std::vector<const char*> out_names(n_out);
    for (size_t i = 0; i < n_out; ++i) out_names[i] = s->outputs[i].c_str();
    ValueGuard out_vals;
    out_vals.v.assign(n_out, nullptr);
    if (Check(env, g_api->Run(s->session, nullptr, name_ptrs.data(), in_vals.v.data(), static_cast<size_t>(n_in),
                              out_names.data(), n_out, out_vals.v.data()))) {
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(2 * n_out), env->FindClass("java/lang/Object"), nullptr);
    for (size_t i = 0; i < n_out; ++i) {
        OrtTensorTypeAndShapeInfo* info = nullptr;
        if (Check(env, g_api->GetTensorTypeAndShape(out_vals.v[i], &info))) return nullptr;
        std::unique_ptr<OrtTensorTypeAndShapeInfo, void (*)(OrtTensorTypeAndShapeInfo*)> info_guard(
            info, [](OrtTensorTypeAndShapeInfo* p) { g_api->ReleaseTensorTypeAndShapeInfo(p); });
        ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
        size_t dims = 0, count = 0;
        if (Check(env, g_api->GetTensorElementType(info, &type))) return nullptr;
        if (Check(env, g_api->GetDimensionsCount(info, &dims))) return nullptr;
        std::vector<int64_t> shape(dims);
        if (Check(env, g_api->GetDimensions(info, shape.data(), dims))) return nullptr;
        if (Check(env, g_api->GetTensorShapeElementCount(info, &count))) return nullptr;
        if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
            jclass ex = env->FindClass("java/lang/RuntimeException");
            env->ThrowNew(ex, ("output " + s->outputs[i] + " is not float32").c_str());
            return nullptr;
        }
        float* data = nullptr;
        if (Check(env, g_api->GetTensorMutableData(out_vals.v[i], reinterpret_cast<void**>(&data)))) return nullptr;
        jfloatArray jd = env->NewFloatArray(static_cast<jsize>(count));
        if (!jd) return nullptr;  // OutOfMemoryError уже брошен
        env->SetFloatArrayRegion(jd, 0, static_cast<jsize>(count), data);
        jlongArray js = env->NewLongArray(static_cast<jsize>(dims));
        env->SetLongArrayRegion(js, 0, static_cast<jsize>(dims), reinterpret_cast<const jlong*>(shape.data()));
        env->SetObjectArrayElement(result, static_cast<jsize>(2 * i), jd);
        env->SetObjectArrayElement(result, static_cast<jsize>(2 * i + 1), js);
        env->DeleteLocalRef(jd);
        env->DeleteLocalRef(js);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_neurosteno_nemotron_OrtNative_release(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Session*>(h);
}
