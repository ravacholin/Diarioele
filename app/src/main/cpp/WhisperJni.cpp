#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

namespace {

struct RuntimeState {
    whisper_context * context = nullptr;
    std::atomic_bool cancelled{false};
    std::mutex inference_mutex;

    ~RuntimeState() {
        if (context != nullptr) {
            whisper_free(context);
        }
    }
};

RuntimeState * state_from(jlong handle) {
    return reinterpret_cast<RuntimeState *>(static_cast<intptr_t>(handle));
}

void throw_java(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass type = env->FindClass(class_name);
    if (type != nullptr) {
        env->ThrowNew(type, message.c_str());
    }
}

bool abort_requested(void * user_data) {
    return static_cast<RuntimeState *>(user_data)->cancelled.load();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_capo_diarioclase_processing_transcription_WhisperNativeBridge_nativeInit(
        JNIEnv * env,
        jobject,
        jstring model_path) {
    if (model_path == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "model_path_missing");
        return 0;
    }

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0;
    }

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    whisper_context * context = whisper_init_from_file_with_params(path, context_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "model_load_failed");
        return 0;
    }

    auto * state = new RuntimeState();
    state->context = context;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_capo_diarioclase_processing_transcription_WhisperNativeBridge_nativeTranscribe(
        JNIEnv * env,
        jobject,
        jlong handle,
        jfloatArray samples,
        jstring prompt,
        jint threads) {
    RuntimeState * state = state_from(handle);
    if (state == nullptr || state->context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "runtime_not_loaded");
        return nullptr;
    }
    if (samples == nullptr || env->GetArrayLength(samples) <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "audio_samples_missing");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(state->inference_mutex);
    state->cancelled.store(false);

    const jsize sample_count = env->GetArrayLength(samples);
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) {
        return nullptr;
    }

    const char * initial_prompt = prompt == nullptr
        ? nullptr
        : env->GetStringUTFChars(prompt, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, static_cast<int>(threads));
    params.language = "es";
    params.translate = false;
    params.detect_language = false;
    params.initial_prompt = initial_prompt;
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = state;

    const int result = whisper_full(state->context, params, pcm, sample_count);

    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (initial_prompt != nullptr) {
        env->ReleaseStringUTFChars(prompt, initial_prompt);
    }

    if (result != 0) {
        if (state->cancelled.load()) {
            throw_java(env, "java/lang/InterruptedException", "transcription_cancelled");
        } else {
            throw_java(env, "java/lang/IllegalStateException", "whisper_error_" + std::to_string(result));
        }
        return nullptr;
    }

    jclass span_class = env->FindClass(
        "com/capo/diarioclase/processing/transcription/NativeSpan"
    );
    if (span_class == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
        span_class,
        "<init>",
        "(JJLjava/lang/String;D)V"
    );
    if (constructor == nullptr) {
        return nullptr;
    }

    const int segment_count = whisper_full_n_segments(state->context);
    jobjectArray output = env->NewObjectArray(segment_count, span_class, nullptr);
    for (int segment = 0; segment < segment_count; ++segment) {
        const int token_count = whisper_full_n_tokens(state->context, segment);
        double confidence = 0.0;
        for (int token = 0; token < token_count; ++token) {
            confidence += whisper_full_get_token_p(state->context, segment, token);
        }
        if (token_count > 0) {
            confidence /= token_count;
        }

        const jlong start_ms =
            static_cast<jlong>(whisper_full_get_segment_t0(state->context, segment) * 10);
        const jlong end_ms =
            static_cast<jlong>(whisper_full_get_segment_t1(state->context, segment) * 10);
        const char * text = whisper_full_get_segment_text(state->context, segment);
        jstring java_text = env->NewStringUTF(text == nullptr ? "" : text);
        jobject java_span = env->NewObject(
            span_class,
            constructor,
            start_ms,
            end_ms,
            java_text,
            static_cast<jdouble>(confidence)
        );
        env->SetObjectArrayElement(output, segment, java_span);
        env->DeleteLocalRef(java_span);
        env->DeleteLocalRef(java_text);
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_capo_diarioclase_processing_transcription_WhisperNativeBridge_nativeCancel(
        JNIEnv *,
        jobject,
        jlong handle) {
    RuntimeState * state = state_from(handle);
    if (state != nullptr) {
        state->cancelled.store(true);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_capo_diarioclase_processing_transcription_WhisperNativeBridge_nativeFree(
        JNIEnv *,
        jobject,
        jlong handle) {
    delete state_from(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_capo_diarioclase_processing_transcription_WhisperNativeBridge_nativeVersion(
        JNIEnv * env,
        jobject) {
    return env->NewStringUTF(whisper_version());
}
