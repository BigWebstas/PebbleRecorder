#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "whisper.h"

// JNI half of com.pebblerecorder.app.transcription.WhisperLib: load a ggml model, run it over
// 16 kHz mono float samples, hand back the text.

namespace {

// whisper_full reports progress on the calling thread, so the JNIEnv from the JNI call stays valid.
struct ProgressTarget {
    JNIEnv *env;
    jobject callback;
    jmethodID onProgress;
};

void onProgress(whisper_context *, whisper_state *, int progress, void *userData) {
    auto *target = static_cast<ProgressTarget *>(userData);
    target->env->CallVoidMethod(target->callback, target->onProgress, progress);
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pebblerecorder_app_transcription_WhisperLib_init(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pebblerecorder_app_transcription_WhisperLib_free(JNIEnv *, jclass, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

// Returns the transcript, or null if whisper failed.
JNIEXPORT jstring JNICALL
Java_com_pebblerecorder_app_transcription_WhisperLib_transcribe(
        JNIEnv *env, jclass, jlong handle, jshortArray samples, jint length, jint threads, jboolean diarize, jobject progress) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en"; // the bundled models are English-only
    params.n_threads = threads;
    params.no_context = true;
    params.tdrz_enable = diarize;
    ProgressTarget target{env, progress, env->GetMethodID(env->GetObjectClass(progress), "onProgress", "(I)V")};
    params.progress_callback = onProgress;
    params.progress_callback_user_data = &target;

    // The audio arrives as 16-bit samples so the Java heap only holds half as much; the floats
    // whisper wants live in native memory, which that heap limit doesn't cover.
    length = std::min(length, env->GetArrayLength(samples));
    std::vector<float> floats(length);
    {
        jshort *data = env->GetShortArrayElements(samples, nullptr);
        for (jint i = 0; i < length; i++) floats[i] = data[i] / 32768.0f;
        env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    }
    int rc = whisper_full(ctx, params, floats.data(), length);
    if (rc != 0) return nullptr;

    std::string text;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        text += whisper_full_get_segment_text(ctx, i);
        // Same marker whisper.cpp's own CLI prints; Kotlin turns it into speaker labels.
        if (diarize && whisper_full_get_segment_speaker_turn_next(ctx, i)) text += " [SPEAKER_TURN]";
    }
    return env->NewStringUTF(text.c_str());
}

}
