#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <atomic>
#include <cstdio>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>
#include <unistd.h>

namespace {
std::mutex g_mutex;
bool g_backend_initialized = false;
FILE * g_model_file = nullptr;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
int32_t g_last_prompt_tokens = 0;
int32_t g_last_generated_tokens = 0;
int64_t g_last_prompt_elapsed_ms = 0;
int64_t g_last_generation_elapsed_ms = 0;
int64_t g_last_elapsed_ms = 0;
std::atomic<bool> g_abort_requested{false};

bool shouldAbortGeneration(void *) {
    return g_abort_requested.load(std::memory_order_acquire);
}

void throwJava(JNIEnv * env, const char * message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

void releaseModelLocked() {
    if (g_context != nullptr) { llama_free(g_context); g_context = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    if (g_model_file != nullptr) { fclose(g_model_file); g_model_file = nullptr; }
    g_last_prompt_tokens = 0;
    g_last_generated_tokens = 0;
    g_last_prompt_elapsed_ms = 0;
    g_last_generation_elapsed_ms = 0;
    g_last_elapsed_ms = 0;
}

std::string toUtf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3U);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800U && cp <= 0xDBFFU && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00U && low <= 0xDFFFU) {
                cp = 0x10000U + ((cp - 0xD800U) << 10U) + (low - 0xDC00U);
                ++i;
            }
        }
        if (cp <= 0x7FU) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FFU) {
            out.push_back(static_cast<char>(0xC0U | (cp >> 6U)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else if (cp <= 0xFFFFU) {
            out.push_back(static_cast<char>(0xE0U | (cp >> 12U)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else {
            out.push_back(static_cast<char>(0xF0U | (cp >> 18U)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 12U) & 0x3FU)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jstring fromUtf8(JNIEnv * env, const std::string & value) {
    std::vector<jchar> out;
    out.reserve(value.size());
    const auto * bytes = reinterpret_cast<const uint8_t *>(value.data());
    size_t i = 0;
    while (i < value.size()) {
        uint32_t cp = bytes[i++];
        if ((cp & 0x80U) == 0U) {
            // ASCII
        } else if ((cp & 0xE0U) == 0xC0U && i < value.size()) {
            cp = ((cp & 0x1FU) << 6U) | (bytes[i++] & 0x3FU);
        } else if ((cp & 0xF0U) == 0xE0U && i + 1 < value.size()) {
            cp = ((cp & 0x0FU) << 12U) | ((bytes[i] & 0x3FU) << 6U) | (bytes[i + 1] & 0x3FU);
            i += 2;
        } else if ((cp & 0xF8U) == 0xF0U && i + 2 < value.size()) {
            cp = ((cp & 0x07U) << 18U) | ((bytes[i] & 0x3FU) << 12U)
                 | ((bytes[i + 1] & 0x3FU) << 6U) | (bytes[i + 2] & 0x3FU);
            i += 3;
        } else {
            cp = 0xFFFDU;
        }
        if (cp <= 0xFFFFU) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000U;
            out.push_back(static_cast<jchar>(0xD800U + (cp >> 10U)));
            out.push_back(static_cast<jchar>(0xDC00U + (cp & 0x3FFU)));
        }
    }
    if (out.empty()) {
        static constexpr jchar emptyString[] = {0};
        return env->NewString(emptyString, 0);
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

bool decodeTokenBatch(const std::vector<llama_token> & tokens, llama_pos startPosition) {
    if (g_context == nullptr || tokens.empty()) return false;
    llama_batch_ext * batch = llama_batch_ext_init(g_context);
    if (batch == nullptr) return false;

    const uint32_t batchLimit = std::max<uint32_t>(1U, std::min<uint32_t>(llama_n_batch(g_context), 128U));
    bool success = true;
    for (size_t offset = 0; offset < tokens.size() && success; offset += batchLimit) {
        llama_batch_ext_clear(batch);
        const size_t count = std::min<size_t>(batchLimit, tokens.size() - offset);
        for (size_t j = 0; j < count; ++j) {
            const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[offset + j]);
            if (index < 0) { success = false; break; }
            const llama_pos position = startPosition + static_cast<llama_pos>(offset + j);
            if (!llama_batch_ext_set_pos(batch, index, &position)) { success = false; break; }
            if (offset + j + 1U == tokens.size()
                && !llama_batch_ext_set_output_logits(batch, index, true)) {
                success = false;
                break;
            }
        }
        if (success && llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) success = false;
    }
    llama_batch_ext_free(batch);
    return success;
}

bool decodeOneToken(llama_token token, llama_pos position) {
    llama_batch_ext * batch = llama_batch_ext_init(g_context);
    if (batch == nullptr) return false;
    const int32_t index = llama_batch_ext_add_token(batch, 0, token);
    bool success = index >= 0;
    if (success) success = llama_batch_ext_set_pos(batch, index, &position);
    if (success) success = llama_batch_ext_set_output_logits(batch, index, true);
    if (success) success = llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) == 0;
    llama_batch_ext_free(batch);
    return success;
}

std::string tokenToUtf8(const llama_vocab * vocab, llama_token token) {
    std::array<char, 256> buffer{};
    int32_t written = llama_token_to_piece(vocab, token, buffer.data(),
        static_cast<int32_t>(buffer.size()), 0, false);
    if (written >= 0) return std::string(buffer.data(), static_cast<size_t>(written));
    const int32_t required = -written;
    if (required <= 0 || required > 65536) return {};
    std::vector<char> large(static_cast<size_t>(required));
    written = llama_token_to_piece(vocab, token, large.data(), required, 0, false);
    if (written < 0) return {};
    return std::string(large.data(), static_cast<size_t>(written));
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeInitialize(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeLoadModelFromFd(
        JNIEnv * env, jobject, jint fd, jint contextTokens, jint threads, jint gpuLayers, jboolean useVulkan) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseModelLocked();
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
    if (fd < 0 || contextTokens < 32) {
        throwJava(env, "Invalid model descriptor or context size.");
        return;
    }

    const int ownedFd = dup(fd);
    if (ownedFd < 0) {
        throwJava(env, "Could not duplicate the model file descriptor.");
        return;
    }
    if (lseek(ownedFd, 0, SEEK_SET) < 0) {
        close(ownedFd);
        throwJava(env, "The model provider does not expose a seekable file descriptor.");
        return;
    }
    g_model_file = fdopen(ownedFd, "rb");
    if (g_model_file == nullptr) {
        close(ownedFd);
        throwJava(env, "Could not open the model file descriptor.");
        return;
    }

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = useVulkan == JNI_TRUE ? gpuLayers : 0;
    std::vector<ggml_backend_dev_t> selectedDevices;
    if (useVulkan == JNI_TRUE) {
        ggml_backend_dev_t selectedVulkanDevice = nullptr;
        for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
            ggml_backend_dev_t device = ggml_backend_dev_get(index);
            ggml_backend_reg_t registration = ggml_backend_dev_backend_reg(device);
            const char * registrationName = registration == nullptr ? nullptr : ggml_backend_reg_name(registration);
            if (registrationName != nullptr && std::string(registrationName) == "Vulkan") {
                selectedVulkanDevice = device;
                break;
            }
        }
        if (selectedVulkanDevice == nullptr || gpuLayers == 0) {
            releaseModelLocked();
            throwJava(env, "Vulkan requested but no Vulkan backend device is available in this build or on this device.");
            return;
        }
        selectedDevices.push_back(selectedVulkanDevice);
        selectedDevices.push_back(nullptr);
        modelParams.devices = selectedDevices.data();
    }
    g_model = llama_model_load_from_file_ptr(g_model_file, modelParams);
    if (g_model == nullptr) {
        releaseModelLocked();
        throwJava(env, "llama.cpp could not load this GGUF file.");
        return;
    }
    if (llama_model_has_encoder(g_model)) {
        releaseModelLocked();
        throwJava(env, "Encoder-decoder GGUF models are not supported by the current decoder-only reference path.");
        return;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(contextTokens);
    contextParams.n_batch = static_cast<uint32_t>(std::min(contextTokens, 512));
    contextParams.n_ubatch = static_cast<uint32_t>(std::min(contextTokens, 128));
    // Keep llama.cpp's own platform default when the API caller requests threads=0.
    // Only override both pools when the caller explicitly supplies a positive value.
    if (threads > 0) {
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
    }
    // llama.cpp's abort callback is CPU-only. Vulkan requests observe cancellation between
    // completed backend operations in the generation loop; an active GPU kernel cannot be pre-empted.
    contextParams.abort_callback = useVulkan == JNI_TRUE ? nullptr : shouldAbortGeneration;
    contextParams.abort_callback_data = nullptr;
    g_context = llama_init_from_model(g_model, contextParams);
    if (g_context == nullptr) {
        releaseModelLocked();
        throwJava(env, "llama.cpp could not create an inference context.");
        return;
    }
    return;
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeFormatChat(
        JNIEnv * env, jobject, jobjectArray jRoles, jobjectArray jContents) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr) {
        throwJava(env, "No model is loaded.");
        return nullptr;
    }
    if (jRoles == nullptr || jContents == nullptr) {
        throwJava(env, "Chat messages are missing.");
        return nullptr;
    }
    const jsize roleCount = env->GetArrayLength(jRoles);
    const jsize contentCount = env->GetArrayLength(jContents);
    if (roleCount <= 0 || roleCount != contentCount || roleCount > 256) {
        throwJava(env, "Chat message arrays have an invalid size.");
        return nullptr;
    }

    std::vector<std::string> roles;
    std::vector<std::string> contents;
    std::vector<llama_chat_message> messages;
    roles.reserve(static_cast<size_t>(roleCount));
    contents.reserve(static_cast<size_t>(contentCount));
    messages.reserve(static_cast<size_t>(roleCount));
    for (jsize index = 0; index < roleCount; ++index) {
        auto roleObject = static_cast<jstring>(env->GetObjectArrayElement(jRoles, index));
        auto contentObject = static_cast<jstring>(env->GetObjectArrayElement(jContents, index));
        if (roleObject == nullptr || contentObject == nullptr) {
            if (roleObject != nullptr) env->DeleteLocalRef(roleObject);
            if (contentObject != nullptr) env->DeleteLocalRef(contentObject);
            throwJava(env, "Chat messages cannot contain null role or content.");
            return nullptr;
        }
        roles.push_back(toUtf8(env, roleObject));
        contents.push_back(toUtf8(env, contentObject));
        env->DeleteLocalRef(roleObject);
        env->DeleteLocalRef(contentObject);
        if (roles.back() != "system" && roles.back() != "user" && roles.back() != "assistant") {
            throwJava(env, "Unsupported chat message role.");
            return nullptr;
        }
        if (contents.back().empty()) {
            throwJava(env, "Chat message content cannot be empty.");
            return nullptr;
        }
        messages.push_back({roles.back().c_str(), contents.back().c_str()});
    }

    const char * chatTemplate = llama_model_chat_template(g_model, nullptr);
    if (chatTemplate == nullptr || chatTemplate[0] == '\0') {
        throwJava(env, "The selected GGUF does not define a default chat template.");
        return nullptr;
    }
    const int32_t required = llama_chat_apply_template(
        chatTemplate, messages.data(), messages.size(), true, nullptr, 0);
    if (required <= 0 || required > 1'000'000) {
        throwJava(env, "llama.cpp could not apply the model chat template.");
        return nullptr;
    }
    std::vector<char> prompt(static_cast<size_t>(required) + 1U, '\0');
    const int32_t written = llama_chat_apply_template(
        chatTemplate, messages.data(), messages.size(), true,
        prompt.data(), static_cast<int32_t>(prompt.size()));
    if (written <= 0 || written > required) {
        throwJava(env, "The formatted chat prompt exceeded its expected size.");
        return nullptr;
    }
    return fromUtf8(env, std::string(prompt.data(), static_cast<size_t>(written)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeGenerate(
        JNIEnv * env, jobject, jstring jPrompt, jint maxOutputTokens, jfloat temperature, jfloat topP, jint topK, jint seed) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_prompt_tokens = 0;
    g_last_generated_tokens = 0;
    g_last_prompt_elapsed_ms = 0;
    g_last_generation_elapsed_ms = 0;
    g_last_elapsed_ms = 0;
    if (g_model == nullptr || g_context == nullptr) {
        throwJava(env, "No model is loaded.");
        return nullptr;
    }
    if (maxOutputTokens < 1 || maxOutputTokens > 8192 || !std::isfinite(temperature) || temperature < 0.0f || temperature > 2.0f || !std::isfinite(topP) || topP <= 0.0f || topP > 1.0f || topK < 0 || topK > 1024 || seed < 0) {
        throwJava(env, "maxOutputTokens must be between 1 and 8192.");
        return nullptr;
    }
    if (g_abort_requested.exchange(false, std::memory_order_acq_rel)) {
        throwJava(env, "Generation cancelled.");
        return nullptr;
    }

    const std::string prompt = toUtf8(env, jPrompt);
    if (prompt.empty()) {
        throwJava(env, "Prompt must not be empty.");
        return nullptr;
    }
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t needed = -llama_tokenize(vocab, prompt.data(),
        static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (needed <= 0) {
        throwJava(env, "Prompt tokenization failed.");
        return nullptr;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t tokenCount = llama_tokenize(vocab, prompt.data(),
        static_cast<int32_t>(prompt.size()), tokens.data(), needed, true, true);
    if (tokenCount <= 0) {
        throwJava(env, "Prompt tokenization failed.");
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(tokenCount));

    // Tokenization is not interruptible in llama.cpp. Observe cancellation immediately after it
    // completes rather than starting prompt evaluation for a request that has already expired.
    if (g_abort_requested.exchange(false, std::memory_order_acq_rel)) {
        throwJava(env, "Generation cancelled.");
        return nullptr;
    }

    const uint32_t contextSize = llama_n_ctx(g_context);
    if (tokens.size() + static_cast<size_t>(maxOutputTokens) > contextSize) {
        throwJava(env, "Prompt plus requested output exceeds the loaded context size.");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_context), false);
    const auto promptStarted = std::chrono::steady_clock::now();
    if (!decodeTokenBatch(tokens, 0)) {
        const bool aborted = g_abort_requested.exchange(false, std::memory_order_acq_rel);
        throwJava(env, aborted ? "Generation cancelled." : "Prompt evaluation failed.");
        return nullptr;
    }
    if (g_abort_requested.exchange(false, std::memory_order_acq_rel)) {
        throwJava(env, "Generation cancelled after prompt evaluation.");
        return nullptr;
    }
    const auto promptFinished = std::chrono::steady_clock::now();

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    samplerParams.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(samplerParams);
    if (sampler == nullptr) {
        throwJava(env, "Could not create the sampler.");
        return nullptr;
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
        if (topP < 1.0f) llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    const auto generationStarted = std::chrono::steady_clock::now();
    std::string output;
    int32_t generated = 0;
    llama_pos nextPosition = static_cast<llama_pos>(tokens.size());
    for (int32_t i = 0; i < maxOutputTokens; ++i) {
        if (g_abort_requested.load(std::memory_order_acquire)) {
            llama_sampler_free(sampler);
            g_abort_requested.store(false, std::memory_order_release);
            throwJava(env, "Generation cancelled.");
            return nullptr;
        }
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (g_abort_requested.exchange(false, std::memory_order_acq_rel)) {
            llama_sampler_free(sampler);
            throwJava(env, "Generation cancelled.");
            return nullptr;
        }
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) break;
        const std::string piece = tokenToUtf8(vocab, token);
        if (piece.empty()) {
            llama_sampler_free(sampler);
            throwJava(env, "Could not convert a generated token to UTF-8.");
            return nullptr;
        }
        output += piece;
        ++generated;
        if (i + 1 < maxOutputTokens) {
            if (!decodeOneToken(token, nextPosition)) {
                llama_sampler_free(sampler);
                const bool aborted = g_abort_requested.exchange(false, std::memory_order_acq_rel);
                throwJava(env, aborted ? "Generation cancelled." : "Generation decode failed.");
                return nullptr;
            }
            ++nextPosition;
        }
    }
    llama_sampler_free(sampler);
    if (g_abort_requested.exchange(false, std::memory_order_acq_rel)) {
        throwJava(env, "Generation cancelled.");
        return nullptr;
    }

    const auto generationFinished = std::chrono::steady_clock::now();
    const auto totalFinished = generationFinished;
    const auto promptElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        promptFinished - promptStarted).count();
    const auto generationElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        generationFinished - generationStarted).count();
    const auto totalElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        totalFinished - promptStarted).count();

    // nativeBeginGeneration() resets the flag for the next request. Do not clear it here:
    // a cancellation arriving after the final check must not be overwritten during return.
    g_last_prompt_tokens = tokenCount;
    g_last_generated_tokens = generated;
    g_last_prompt_elapsed_ms = promptElapsed;
    g_last_generation_elapsed_ms = generationElapsed;
    g_last_elapsed_ms = totalElapsed;
    return fromUtf8(env, output);
}



extern "C" JNIEXPORT void JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeBeginGeneration(JNIEnv *, jobject) {
    g_abort_requested.store(false, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeAbortGeneration(JNIEnv *, jobject) {
    // This callback is intentionally lock-free: generation owns g_mutex on the worker thread.
    g_abort_requested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseModelLocked();
}


extern "C" JNIEXPORT void JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseModelLocked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeSystemInfo(JNIEnv * env, jobject) {
    const char * info = llama_print_system_info();
    std::string result = info == nullptr ? "unknown" : std::string(info);
    result += "\\nbackend_devices=";
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        const char * name = ggml_backend_dev_name(device);
        const char * description = ggml_backend_dev_description(device);
        if (index != 0) result += ";";
        result += (name == nullptr ? "unknown" : name);
        result += ":";
        result += (description == nullptr ? "unknown" : description);
    }
    return fromUtf8(env, result);
}

extern "C" JNIEXPORT jint JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeLastPromptTokens(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_last_prompt_tokens;
}

extern "C" JNIEXPORT jint JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeLastGeneratedTokens(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_last_generated_tokens;
}

extern "C" JNIEXPORT jlong JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeLastPromptElapsedMillis(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jlong>(g_last_prompt_elapsed_ms);
}

extern "C" JNIEXPORT jlong JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeLastGenerationElapsedMillis(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jlong>(g_last_generation_elapsed_ms);
}

extern "C" JNIEXPORT jlong JNICALL
Java_de_mrlongnight_llmreference_engine_nativebridge_NativeLlama_nativeLastElapsedMillis(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jlong>(g_last_elapsed_ms);
}
