// JNI-мост к llama.cpp: загрузка GGUF, greedy-генерация по chat template модели, отмена между токенами.
#include <jni.h>
#include <cstdio>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "NeuroLLM"
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW_RAW(text) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text)
#else
#define LOGI(...) (fprintf(stderr, "[" TAG "] " __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) (fprintf(stderr, "[" TAG " ERROR] " __VA_ARGS__), fputc('\n', stderr))
#define LOGW_RAW(text) fputs(text, stderr)
#endif

namespace {

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::string tmpl;
};

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGW_RAW(text);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Java-строки приходят в modified UTF-8; для русского текста без эмодзи это совпадает с UTF-8.
std::string jbytes(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string r(n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(r.data()));
    return r;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_neurosteno_llm_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
    std::string path = jstr(env, jpath);

    auto mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("model load failed: %s", path.c_str()); return 0; }

    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = nCtx;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }

    auto *h = new Handle();
    h->model = model;
    h->ctx = ctx;
    h->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(h->sampler, llama_sampler_init_greedy());
    const char *t = llama_model_chat_template(model, nullptr);
    h->tmpl = t ? t : "chatml";
    LOGI("loaded %s ctx=%d threads=%d", path.c_str(), nCtx, nThreads);
    return reinterpret_cast<jlong>(h);
}

// Возвращает UTF-8 байты ответа (декодируются в Kotlin) или null при ошибке.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_neurosteno_llm_LlamaNative_complete(JNIEnv *env, jobject, jlong ptr, jbyteArray jsys, jbyteArray juser,
                                            jint maxTokens, jboolean singleLine, jobject stopper) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (!h) return nullptr;
    std::string sys = jbytes(env, jsys), user = jbytes(env, juser);

    jclass cls = env->GetObjectClass(stopper);
    jmethodID shouldStop = env->GetMethodID(cls, "shouldStop", "()Z");

    llama_chat_message msgs[2] = {{"system", sys.c_str()}, {"user", user.c_str()}};
    std::vector<char> buf(2 * (sys.size() + user.size()) + 512);
    int n = llama_chat_apply_template(h->tmpl.c_str(), msgs, 2, true, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(h->tmpl.c_str(), msgs, 2, true, buf.data(), (int) buf.size());
    }
    if (n < 0) { LOGE("chat template failed"); return nullptr; }
    std::string prompt(buf.data(), n);

    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    llama_memory_clear(llama_get_memory(h->ctx), true);
    llama_sampler_reset(h->sampler);

    int nTok = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(nTok);
    if (llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), nTok, true, true) < 0) return nullptr;
    const int nCtx = (int) llama_n_ctx(h->ctx);
    if (nTok + maxTokens > nCtx) maxTokens = nCtx - nTok;
    if (maxTokens <= 0) { LOGE("prompt too long: %d tokens", nTok); return nullptr; }

    llama_batch batch = llama_batch_get_one(toks.data(), nTok);
    if (llama_decode(h->ctx, batch) != 0) { LOGE("decode prompt failed"); return nullptr; }

    std::string out;
    llama_token tok;
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        if (env->CallBooleanMethod(stopper, shouldStop)) break;
        tok = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        // Для правки реплики ответ — одна строка: перевод строки означает конец полезного текста.
        if (singleLine) {
            size_t nl = out.find('\n');
            if (nl != std::string::npos && out.find_first_not_of(" \r\n") < nl) break;
        }
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, b) != 0) { LOGE("decode failed at %d", i); break; }
    }

    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_neurosteno_llm_LlamaNative_free(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (!h) return;
    if (h->sampler) llama_sampler_free(h->sampler);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}
