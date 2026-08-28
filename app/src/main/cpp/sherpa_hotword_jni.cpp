#include <jni.h>
#include <string>
#include <vector>

#include "sherpa_onnx/c-api.h"

namespace {

constexpr int32_t kSampleRate = 16000;

struct SherpaHotwordEngine {
    const SherpaOnnxOfflineRecognizer* recognizer = nullptr;
    const SherpaOnnxVoiceActivityDetector* vad = nullptr;
};

std::string JStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void ThrowIllegalState(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

std::string DecodeSegment(const SherpaOnnxOfflineRecognizer* recognizer, const float* samples, int32_t n) {
    if (recognizer == nullptr || samples == nullptr || n <= 0) return {};

    const SherpaOnnxOfflineStream* stream = SherpaOnnxCreateOfflineStream(recognizer);
    if (stream == nullptr) return {};

    SherpaOnnxAcceptWaveformOffline(stream, kSampleRate, samples, n);
    SherpaOnnxDecodeOfflineStream(recognizer, stream);

    std::string text;
    const SherpaOnnxOfflineRecognizerResult* result = SherpaOnnxGetOfflineStreamResult(stream);
    if (result != nullptr && result->text != nullptr) {
        text = result->text;
    }

    if (result != nullptr) {
        SherpaOnnxDestroyOfflineRecognizerResult(result);
    }
    SherpaOnnxDestroyOfflineStream(stream);
    return text;
}

std::vector<std::string> DrainVadSegments(SherpaHotwordEngine* engine) {
    std::vector<std::string> texts;
    if (engine == nullptr || engine->vad == nullptr || engine->recognizer == nullptr) return texts;

    while (SherpaOnnxVoiceActivityDetectorEmpty(engine->vad) == 0) {
        const SherpaOnnxSpeechSegment* segment = SherpaOnnxVoiceActivityDetectorFront(engine->vad);
        if (segment != nullptr) {
            std::string text = DecodeSegment(engine->recognizer, segment->samples, segment->n);
            if (!text.empty()) {
                texts.emplace_back(std::move(text));
            }
            SherpaOnnxDestroySpeechSegment(segment);
        }
        SherpaOnnxVoiceActivityDetectorPop(engine->vad);
    }
    return texts;
}

jobjectArray ToJavaStringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (array == nullptr) return nullptr;

    for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
        jstring value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(array, i, value);
        env->DeleteLocalRef(value);
    }
    return array;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_auto_1measure_1luoji_feature_voice_domain_SherpaOnnxHotwordNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring modelPath,
    jstring tokensPath,
    jstring vadPath
) {
    const std::string model = JStringToString(env, modelPath);
    const std::string tokens = JStringToString(env, tokensPath);
    const std::string vadModel = JStringToString(env, vadPath);

    SherpaOnnxOfflineRecognizerConfig recognizerConfig{};
    recognizerConfig.feat_config.sample_rate = kSampleRate;
    recognizerConfig.feat_config.feature_dim = 80;
    recognizerConfig.model_config.sense_voice.model = model.c_str();
    recognizerConfig.model_config.sense_voice.language = "zh";
    recognizerConfig.model_config.sense_voice.use_itn = 0;
    recognizerConfig.model_config.tokens = tokens.c_str();
    recognizerConfig.model_config.num_threads = 2;
    recognizerConfig.model_config.debug = 0;
    recognizerConfig.model_config.provider = "cpu";
    recognizerConfig.decoding_method = "greedy_search";
    recognizerConfig.max_active_paths = 4;

    const SherpaOnnxOfflineRecognizer* recognizer =
        SherpaOnnxCreateOfflineRecognizer(&recognizerConfig);
    if (recognizer == nullptr) {
        ThrowIllegalState(env, "Sherpa OfflineRecognizer 初始化失败");
        return 0;
    }

    SherpaOnnxVadModelConfig vadConfig{};
    vadConfig.silero_vad.model = vadModel.c_str();
    vadConfig.silero_vad.threshold = 0.45f;
    vadConfig.silero_vad.min_silence_duration = 0.25f;
    vadConfig.silero_vad.min_speech_duration = 0.25f;
    vadConfig.silero_vad.max_speech_duration = 30.0f;
    vadConfig.silero_vad.window_size = 512;
    vadConfig.sample_rate = kSampleRate;
    vadConfig.num_threads = 2;
    vadConfig.provider = "cpu";
    vadConfig.debug = 0;

    const SherpaOnnxVoiceActivityDetector* vad =
        SherpaOnnxCreateVoiceActivityDetector(&vadConfig, 30.0f);
    if (vad == nullptr) {
        SherpaOnnxDestroyOfflineRecognizer(recognizer);
        ThrowIllegalState(env, "Sherpa Silero VAD 初始化失败");
        return 0;
    }

    auto* engine = new SherpaHotwordEngine();
    engine->recognizer = recognizer;
    engine->vad = vad;
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_auto_1measure_1luoji_feature_voice_domain_SherpaOnnxHotwordNative_nativeAcceptWaveform(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray samples
) {
    auto* engine = reinterpret_cast<SherpaHotwordEngine*>(handle);
    if (engine == nullptr || samples == nullptr) return ToJavaStringArray(env, {});

    jsize n = env->GetArrayLength(samples);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return ToJavaStringArray(env, {});

    SherpaOnnxVoiceActivityDetectorAcceptWaveform(engine->vad, data, static_cast<int32_t>(n));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    return ToJavaStringArray(env, DrainVadSegments(engine));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_auto_1measure_1luoji_feature_voice_domain_SherpaOnnxHotwordNative_nativeFlush(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* engine = reinterpret_cast<SherpaHotwordEngine*>(handle);
    if (engine == nullptr) return ToJavaStringArray(env, {});

    SherpaOnnxVoiceActivityDetectorFlush(engine->vad);
    return ToJavaStringArray(env, DrainVadSegments(engine));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_auto_1measure_1luoji_feature_voice_domain_SherpaOnnxHotwordNative_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* engine = reinterpret_cast<SherpaHotwordEngine*>(handle);
    if (engine == nullptr) return;

    if (engine->vad != nullptr) {
        SherpaOnnxDestroyVoiceActivityDetector(engine->vad);
    }
    if (engine->recognizer != nullptr) {
        SherpaOnnxDestroyOfflineRecognizer(engine->recognizer);
    }
    delete engine;
}
