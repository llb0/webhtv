// Trickplay thumbnail extractor.
//
// Reuses the FFmpeg 7.1.4 build that already ships with the app for mpv.
// Those libraries are renamed (libmvformat / libmvcodec / libmvutil / libmwscale)
// and live in the mpv asset bundle, so they are opened at runtime with dlopen
// and every symbol is bound through dlsym. Nothing is linked at build time,
// which keeps the linker namespace clean and cannot clash with the FFmpeg
// 9.0.1 copy inside nextlib.
//
// One AVFormatContext + decoder is kept per media, so a whole grid of
// thumbnails costs one probe instead of one probe per frame.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "TrickplayExtractor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int64_t kOpenTimeoutMs = 20000;
constexpr int64_t kReadTimeoutMs = 15000;

// ---- runtime bindings ---------------------------------------------------------
decltype(&avformat_network_init) p_avformat_network_init;
decltype(&avformat_alloc_context) p_avformat_alloc_context;
decltype(&avformat_open_input) p_avformat_open_input;
decltype(&avformat_find_stream_info) p_avformat_find_stream_info;
decltype(&avformat_close_input) p_avformat_close_input;
decltype(&av_find_best_stream) p_av_find_best_stream;
decltype(&avcodec_find_decoder) p_avcodec_find_decoder;
decltype(&avcodec_alloc_context3) p_avcodec_alloc_context3;
decltype(&avcodec_parameters_to_context) p_avcodec_parameters_to_context;
decltype(&avcodec_open2) p_avcodec_open2;
decltype(&avcodec_free_context) p_avcodec_free_context;
decltype(&avcodec_flush_buffers) p_avcodec_flush_buffers;
decltype(&avcodec_send_packet) p_avcodec_send_packet;
decltype(&avcodec_receive_frame) p_avcodec_receive_frame;
decltype(&av_packet_alloc) p_av_packet_alloc;
decltype(&av_packet_free) p_av_packet_free;
decltype(&av_packet_unref) p_av_packet_unref;
decltype(&av_frame_alloc) p_av_frame_alloc;
decltype(&av_frame_free) p_av_frame_free;
decltype(&av_frame_unref) p_av_frame_unref;
decltype(&av_seek_frame) p_av_seek_frame;
decltype(&av_read_frame) p_av_read_frame;
decltype(&av_rescale_q) p_av_rescale_q;
decltype(&av_dict_set) p_av_dict_set;
decltype(&av_dict_free) p_av_dict_free;
decltype(&sws_getContext) p_sws_getContext;
decltype(&sws_scale) p_sws_scale;
decltype(&sws_freeContext) p_sws_freeContext;
decltype(&av_image_alloc) p_av_image_alloc;
decltype(&av_freep) p_av_freep;

bool gBound = false;
std::mutex gBindMutex;

void* openLibrary(const char* dir, const char* name) {
    std::string path = std::string(dir) + "/" + name;
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        LOGW("dlopen failed: %s (%s)", path.c_str(), dlerror());
    }
    return handle;
}

void* gHandles[6] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
int gHandleCount = 0;

bool bindSymbols() {
    if (gBound) return true;
    // Look the symbol up in each library we opened; RTLD_DEFAULT is not reliable
    // here because the mpv bundle may already be loaded with local scope.
    auto bind = [](const char* name) -> void* {
        for (int i = 0; i < gHandleCount; i++) {
            void* symbol = dlsym(gHandles[i], name);
            if (symbol != nullptr) return symbol;
        }
        LOGW("missing symbol %s", name);
        return nullptr;
    };
#define BIND(fn) p_##fn = reinterpret_cast<decltype(p_##fn)>(bind(#fn))
    BIND(avformat_network_init);
    BIND(avformat_alloc_context);
    BIND(avformat_open_input);
    BIND(avformat_find_stream_info);
    BIND(avformat_close_input);
    BIND(av_find_best_stream);
    BIND(avcodec_find_decoder);
    BIND(avcodec_alloc_context3);
    BIND(avcodec_parameters_to_context);
    BIND(avcodec_open2);
    BIND(avcodec_free_context);
    BIND(avcodec_flush_buffers);
    BIND(avcodec_send_packet);
    BIND(avcodec_receive_frame);
    BIND(av_packet_alloc);
    BIND(av_packet_free);
    BIND(av_packet_unref);
    BIND(av_frame_alloc);
    BIND(av_frame_free);
    BIND(av_frame_unref);
    BIND(av_seek_frame);
    BIND(av_read_frame);
    BIND(av_rescale_q);
    BIND(av_dict_set);
    BIND(av_dict_free);
    BIND(sws_getContext);
    BIND(sws_scale);
    BIND(sws_freeContext);
    BIND(av_image_alloc);
    BIND(av_freep);
#undef BIND
    gBound = p_avformat_open_input != nullptr && p_avcodec_open2 != nullptr &&
             p_avcodec_send_packet != nullptr && p_sws_scale != nullptr &&
             p_av_packet_alloc != nullptr && p_av_frame_alloc != nullptr;
    if (!gBound) LOGW("FFmpeg symbols could not be bound");
    else LOGI("FFmpeg symbols bound");
    return gBound;
}

// ---- extractor ----------------------------------------------------------------
struct Extractor {
    AVFormatContext* fmt = nullptr;
    AVCodecContext* dec = nullptr;
    AVPacket* pkt = nullptr;
    AVFrame* frame = nullptr;
    int streamIndex = -1;
    std::atomic<int64_t> deadlineMs{0};
    std::string url;
};

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

int interruptCallback(void* opaque) {
    auto* ex = static_cast<Extractor*>(opaque);
    if (ex == nullptr) return 0;
    int64_t deadline = ex->deadlineMs.load(std::memory_order_relaxed);
    if (deadline == 0) return 0;
    return nowMs() > deadline ? 1 : 0;
}

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Player header map -> "K: V\r\nK2: V2\r\n" as libavformat expects.
void applyHeaders(JNIEnv* env, jobjectArray headers, AVDictionary** options) {
    if (headers == nullptr) return;
    std::string headerBlock;
    std::string userAgent;
    std::string referer;
    jsize count = env->GetArrayLength(headers);
    for (jsize i = 0; i + 1 < count; i += 2) {
        jobject keyObj = env->GetObjectArrayElement(headers, i);
        jobject valueObj = env->GetObjectArrayElement(headers, i + 1);
        std::string key = jstringToUtf8(env, static_cast<jstring>(keyObj));
        std::string value = jstringToUtf8(env, static_cast<jstring>(valueObj));
        if (keyObj != nullptr) env->DeleteLocalRef(keyObj);
        if (valueObj != nullptr) env->DeleteLocalRef(valueObj);
        if (key.empty() || value.empty()) continue;
        if (key == "Range" || key == "range") continue;
        headerBlock += key + ": " + value + "\r\n";
        if (key == "User-Agent" || key == "user-agent") userAgent = value;
        if (key == "Referer" || key == "referer") referer = value;
    }
    if (!headerBlock.empty()) p_av_dict_set(options, "headers", headerBlock.c_str(), 0);
    if (!userAgent.empty()) p_av_dict_set(options, "user_agent", userAgent.c_str(), 0);
    if (!referer.empty()) p_av_dict_set(options, "referer", referer.c_str(), 0);
}

void releaseExtractor(Extractor* ex) {
    if (ex == nullptr) return;
    if (ex->frame != nullptr) p_av_frame_free(&ex->frame);
    if (ex->pkt != nullptr) p_av_packet_free(&ex->pkt);
    if (ex->dec != nullptr) p_avcodec_free_context(&ex->dec);
    if (ex->fmt != nullptr) p_avformat_close_input(&ex->fmt);
    delete ex;
}

// Decode the first frame at (or right before) the requested position.
bool decodeAt(Extractor* ex, int64_t positionMs) {
    AVStream* stream = ex->fmt->streams[ex->streamIndex];
    int64_t ts = p_av_rescale_q(positionMs, AVRational{1, 1000}, stream->time_base);
    ex->deadlineMs.store(nowMs() + kReadTimeoutMs, std::memory_order_relaxed);
    int seekResult = p_av_seek_frame(ex->fmt, ex->streamIndex, ts, AVSEEK_FLAG_BACKWARD);
    if (seekResult < 0) LOGW("seek failed at %lld: %d", (long long) positionMs, seekResult);
    p_avcodec_flush_buffers(ex->dec);
    p_av_frame_unref(ex->frame);
    p_av_packet_unref(ex->pkt);

    while (p_av_read_frame(ex->fmt, ex->pkt) >= 0) {
        if (ex->pkt->stream_index != ex->streamIndex) {
            p_av_packet_unref(ex->pkt);
            continue;
        }
        int sendResult = p_avcodec_send_packet(ex->dec, ex->pkt);
        p_av_packet_unref(ex->pkt);
        if (sendResult < 0 && sendResult != AVERROR(EAGAIN)) continue;
        if (p_avcodec_receive_frame(ex->dec, ex->frame) == 0) {
            ex->deadlineMs.store(0, std::memory_order_relaxed);
            return true;
        }
    }
    p_avcodec_send_packet(ex->dec, nullptr);
    bool ok = p_avcodec_receive_frame(ex->dec, ex->frame) == 0;
    ex->deadlineMs.store(0, std::memory_order_relaxed);
    return ok;
}

// Scale the decoded frame into [width, height, ARGB pixels...].
jintArray frameToArgb(JNIEnv* env, Extractor* ex, int targetWidth) {
    int srcWidth = ex->frame->width;
    int srcHeight = ex->frame->height;
    if (srcWidth <= 0 || srcHeight <= 0) return nullptr;
    int dstWidth = targetWidth > 0 ? targetWidth : srcWidth;
    if (dstWidth > srcWidth) dstWidth = srcWidth;
    int dstHeight = (int) ((int64_t) dstWidth * srcHeight / srcWidth);
    if ((dstHeight & 1) != 0) dstHeight++;

    SwsContext* sws = p_sws_getContext(srcWidth, srcHeight, (AVPixelFormat) ex->frame->format,
                                       dstWidth, dstHeight, AV_PIX_FMT_BGRA, SWS_POINT, nullptr,
                                       nullptr, nullptr);
    if (sws == nullptr) return nullptr;

    uint8_t* dstData[4] = {nullptr, nullptr, nullptr, nullptr};
    int dstLinesize[4] = {0, 0, 0, 0};
    if (p_av_image_alloc(dstData, dstLinesize, dstWidth, dstHeight, AV_PIX_FMT_BGRA, 1) < 0) {
        p_sws_freeContext(sws);
        return nullptr;
    }
    p_sws_scale(sws, ex->frame->data, ex->frame->linesize, 0, srcHeight, dstData, dstLinesize);
    p_sws_freeContext(sws);

    int pixelCount = dstWidth * dstHeight;
    jintArray result = env->NewIntArray(2 + pixelCount);
    if (result == nullptr) {
        p_av_freep(&dstData[0]);
        return nullptr;
    }
    jint* pixels = new jint[2 + pixelCount];
    pixels[0] = dstWidth;
    pixels[1] = dstHeight;
    jint* out = pixels + 2;
    for (int y = 0; y < dstHeight; y++) {
        const uint8_t* src = dstData[0] + y * dstLinesize[0];
        for (int x = 0; x < dstWidth; x++) {
            jint b = src[x * 4 + 0];
            jint g = src[x * 4 + 1];
            jint r = src[x * 4 + 2];
            *out++ = (jint) (0xFF000000 | (r << 16) | (g << 8) | b);
        }
    }
    env->SetIntArrayRegion(result, 0, 2 + pixelCount, pixels);
    delete[] pixels;
    p_av_freep(&dstData[0]);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fongmi_android_tv_player_TrickplayNative_nativeSetup(JNIEnv* env, jclass, jstring libDir) {
    std::lock_guard<std::mutex> lock(gBindMutex);
    if (gBound) return JNI_TRUE;
    std::string dir = jstringToUtf8(env, libDir);
    if (dir.empty()) return JNI_FALSE;
    // Same order as MPVLib uses: c++ runtime, then the FFmpeg pieces.
    const char* libraries[] = {"libc++_shared.so", "libmvutil.so", "libmwresample.so",
                               "libmwscale.so", "libmvcodec.so", "libmvformat.so"};
    gHandleCount = 0;
    for (const char* library : libraries) {
        void* handle = openLibrary(dir.c_str(), library);
        if (handle == nullptr) return JNI_FALSE;
        if (gHandleCount < 6) gHandles[gHandleCount++] = handle;
    }
    return bindSymbols() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fongmi_android_tv_player_TrickplayNative_nativeOpen(JNIEnv* env, jclass, jstring url,
                                                             jobjectArray headers) {
    if (!gBound) return 0;
    std::string target = jstringToUtf8(env, url);
    if (target.empty()) return 0;

    Extractor* ex = new Extractor();
    ex->url = target;
    ex->fmt = p_avformat_alloc_context();
    if (ex->fmt == nullptr) {
        delete ex;
        return 0;
    }
    ex->fmt->interrupt_callback.callback = interruptCallback;
    ex->fmt->interrupt_callback.opaque = ex;

    AVDictionary* options = nullptr;
    p_av_dict_set(&options, "timeout", "15000000", 0);  // microseconds
    applyHeaders(env, headers, &options);
    ex->deadlineMs.store(nowMs() + kOpenTimeoutMs, std::memory_order_relaxed);
    int openResult = p_avformat_open_input(&ex->fmt, target.c_str(), nullptr, &options);
    p_av_dict_free(&options);
    ex->deadlineMs.store(0, std::memory_order_relaxed);
    if (openResult < 0) {
        LOGW("open failed: %d (%s)", openResult, target.c_str());
        releaseExtractor(ex);
        return 0;
    }
    ex->deadlineMs.store(nowMs() + kOpenTimeoutMs, std::memory_order_relaxed);
    int infoResult = p_avformat_find_stream_info(ex->fmt, nullptr);
    ex->deadlineMs.store(0, std::memory_order_relaxed);
    if (infoResult < 0) {
        LOGW("find_stream_info failed: %d", infoResult);
        releaseExtractor(ex);
        return 0;
    }
    ex->streamIndex = p_av_find_best_stream(ex->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (ex->streamIndex < 0) {
        LOGW("no video stream");
        releaseExtractor(ex);
        return 0;
    }
    AVStream* stream = ex->fmt->streams[ex->streamIndex];
    const AVCodec* codec = p_avcodec_find_decoder(stream->codecpar->codec_id);
    if (codec == nullptr) {
        LOGW("no decoder for codec id %d", stream->codecpar->codec_id);
        releaseExtractor(ex);
        return 0;
    }
    ex->dec = p_avcodec_alloc_context3(codec);
    if (ex->dec == nullptr || p_avcodec_parameters_to_context(ex->dec, stream->codecpar) < 0 ||
        p_avcodec_open2(ex->dec, codec, nullptr) < 0) {
        LOGW("decoder open failed");
        releaseExtractor(ex);
        return 0;
    }
    ex->dec->thread_count = 1;  // thumbnails only; keep memory/CPU low
    ex->pkt = p_av_packet_alloc();
    ex->frame = p_av_frame_alloc();
    if (ex->pkt == nullptr || ex->frame == nullptr) {
        releaseExtractor(ex);
        return 0;
    }
    LOGI("opened %s stream=%d %dx%d", target.c_str(), ex->streamIndex, stream->codecpar->width,
         stream->codecpar->height);
    return reinterpret_cast<jlong>(ex);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_fongmi_android_tv_player_TrickplayNative_nativeGrab(JNIEnv* env, jclass, jlong handle,
                                                             jlong positionMs, jint targetWidth) {
    Extractor* ex = reinterpret_cast<Extractor*>(handle);
    if (ex == nullptr || !gBound) return nullptr;
    if (!decodeAt(ex, positionMs)) return nullptr;
    return frameToArgb(env, ex, targetWidth);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fongmi_android_tv_player_TrickplayNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    Extractor* ex = reinterpret_cast<Extractor*>(handle);
    if (ex == nullptr) return;
    if (gBound) releaseExtractor(ex);
}
