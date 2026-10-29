// The libretro host behind CoreHost. The worker owns core calls and presentation; JNI queues
// input and window changes. Vulkan queue access is shared with the core through its queue lock.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/system_properties.h>
#include <sys/utsname.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <future>
#include <iterator>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "libretro.h"
#include "vulkan.h"

namespace {

using Clock = std::chrono::steady_clock;

constexpr char kTag[] = "MielCore";
// Frame periods from a frame's scheduled start to the presentation time requested for it.
constexpr int kPresentLead = 3;
// Checkpoint envelope: magic, identity length (u32), identity (core name and version), payload
// length (u64), FNV-1a 64 checksum of the payload, payload. Integers are little-endian.
constexpr char kStateMagic[] = "THORAZ02";
constexpr size_t kMagicLength = 8;
constexpr uint64_t kMaxStateSize = 512ull << 20;
constexpr uint64_t kFnvOffset = 1469598103934665603ull, kFnvPrime = 1099511628211ull;
constexpr unsigned kMaxFrameSize = 4096;
// Audio is held back after a start, pause or underrun until this much is queued.
constexpr double kAudioPrefillSeconds = 0.05;
constexpr double kStretchQueueSeconds = 0.1;
// Diagnostic sessions checkpoint this often; games only on Home, Back and pause, because
// Azahar's serialize stalls emulation for most of a second.
constexpr auto kDiagnosticCheckpoint = std::chrono::milliseconds(1500);
constexpr auto kStatsInterval = std::chrono::seconds(10);

uint64_t micros() {
    return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now().time_since_epoch())
        .count();
}

// libretro entry points, resolved from the core library.
struct Api {
#define ENTRY(name) decltype(&retro_##name) name = nullptr
    ENTRY(api_version);
    ENTRY(init);
    ENTRY(deinit);
    ENTRY(get_system_info);
    ENTRY(get_system_av_info);
    ENTRY(set_environment);
    ENTRY(set_video_refresh);
    ENTRY(set_audio_sample);
    ENTRY(set_audio_sample_batch);
    ENTRY(set_input_poll);
    ENTRY(set_input_state);
    ENTRY(set_controller_port_device);
    ENTRY(load_game);
    ENTRY(unload_game);
    ENTRY(run);
    ENTRY(serialize_size);
    ENTRY(serialize);
    ENTRY(unserialize);
    ENTRY(get_memory_data);
    ENTRY(get_memory_size);
#undef ENTRY
} api;

// What one CoreHost.start asked for.
struct Launch {
    std::string library, rom, coreName;
    std::vector<std::pair<std::string, std::string>> options;
    bool diagnostic, probe, fresh;
};

std::thread worker;

// Requests from Java.
std::atomic<bool> running{false}, paused{false}, stopping{false}, saveRequested{false},
    exitAfterSave{false};

retro_disk_control_callback disks{};
std::atomic<int> requestedDisc{-1}, requestedAnalog{-1};
std::atomic<bool> analogEnabled{true};
std::atomic<unsigned> discCount{0}, discIndex{0};
std::atomic<bool> discFailed{false};
std::atomic<bool> fastForward{false}, fastForwardSound{true};
std::atomic<double> fastForwardSpeed{3};
// Frames run per frame period: the period over the recent average frame time.
std::atomic<double> speed{1};
double audioRate = 32768;

// The session's state, reported to Java.
std::atomic<bool> ready{false}, restored{false}, restoredBackup{false}, restoreBlocked{false},
    saved{false}, checkpointFailed{false}, failed{false}, exitReady{false};
std::atomic<bool> dual{false}, hardwareContext{false}, audioOutput{false};
std::atomic<uint64_t> checkpoints{0}, periodicSaves{0}, lastSavedFrame{0};

// Counters for the report and the stats log.
std::atomic<uint64_t> frames{0}, upperFrames{0}, lowerFrames{0}, audioFrames{0}, audioDelivered{0},
    inputEvents{0}, glErrors{0}, analogPolls{0}, analogPeak{0};
std::atomic<uint64_t> runMicros{0}, presentMicros{0}, sleepMicros{0}, snapshotMicros{0},
    writeMicros{0};
std::atomic<uint64_t> audioDropped{0}, audioUnderruns{0};

// Controller and touch state, as libretro reads it.
std::atomic<uint32_t> buttons{0}, hats{0}, triggerButtons{0};
std::atomic<int16_t> axes[4], pointerX{0}, pointerY{0};
std::atomic<int16_t> triggers[2]{};
std::atomic<bool> pointerDown{false};
std::atomic<uint16_t> rumbleMotors[2]{};
std::atomic<uint64_t> rumbleRequests{0}, rumblePeak{0};

std::mutex textMutex;  // Guards the strings below.
std::string message = "Not started", coreName, coreVersion, lastCoreMessage, loadingMessage;
double loadingProgress = -1;
std::string systemPath, saveDirectoryPath;
std::string root, statePath, sramPath;

// Surfaces from Java for the lower (0) and upper (1) screen, adopted by the worker.
std::mutex windowsMutex;
ANativeWindow* requestedWindows[2]{};

// Worker-only graphics state.
struct Output {
    ANativeWindow* window = nullptr;
    EGLSurface surface = EGL_NO_SURFACE;
} outputs[2];
EGLDisplay display = EGL_NO_DISPLAY;
EGLContext context = EGL_NO_CONTEXT;
EGLSurface pbuffer = EGL_NO_SURFACE;
EGLConfig config;
// A dual-screen core renders both screens into one framebuffer, top above bottom, with the bottom
// screen inset from the sides by `lowerInset` of the width. A single-screen frame goes up.
bool stacked = true;
float lowerInset = 0;
float displayAspect = 0;
GLuint framebuffer = 0, texture = 0, depthBuffer = 0;
unsigned width = 400, height = 480, allocatedWidth = 0, allocatedHeight = 0;
retro_hw_render_callback hw{};
VulkanVideo vulkan;
bool useVulkan = false;
// Steady-clock time in nanoseconds at which the current Vulkan frame should reach the displays,
// kPresentLead frame periods after its scheduled start.
int64_t presentAt = 0;
const retro_hw_render_context_negotiation_interface_vulkan* vkNegotiation = nullptr;
retro_pixel_format format = RETRO_PIXEL_FORMAT_XRGB8888;
retro_frame_time_callback frameTime{};
retro_audio_callback asyncAudio{};
std::map<std::string, std::string> variables;
bool hasGame = false, initialized = false, hwStarted = false;
// A core that requests a GL context during play gets context_reset after the current retro_run.
bool contextResetPending = false;
// `adb shell setprop debug.miel.option.<key> <value>` overrides a core option during play.
bool variablesChanged = false;

// Interleaved stereo samples from the core (producer) to the AAudio callback (consumer).
// Positions only grow; each side owns one.
AAudioStream* stream = nullptr;
std::vector<int16_t> audioRing(16384);
std::atomic<size_t> audioRead{0}, audioWrite{0}, audioPrefill{0};
std::atomic<bool> audioFlush{false};

void status(const std::string& text) {
    std::lock_guard lock(textMutex);
    message = text;
    loadingMessage = ready ? "" : text;
    loadingProgress = -1;
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", text.c_str());
}

void logCore(enum retro_log_level level, const char* format, ...) {
    if (level < RETRO_LOG_INFO) return;
    char buffer[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    int priority = level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO;
    __android_log_print(priority, "MielCoreLog", "%s", buffer);
}

void require(bool condition, const std::string& error) {
    if (!condition) throw std::runtime_error(error);
}

std::string identity() { return coreName + "\n" + coreVersion; }

// Video.

void allocateFramebuffer() {
    require(width > 0 && height > 0 && width <= kMaxFrameSize && height <= kMaxFrameSize,
            "Unsupported core framebuffer size");
    if (allocatedWidth == width && allocatedHeight == height) return;
    if (!framebuffer) glGenFramebuffers(1, &framebuffer);
    if (!texture) glGenTextures(1, &texture);
    if (!depthBuffer) glGenRenderbuffers(1, &depthBuffer);
    GLint priorDraw = 0, priorRead = 0, priorTexture = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &priorDraw);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &priorRead);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &priorTexture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER,
                              depthBuffer);
    require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE,
            "Core framebuffer is incomplete");
    allocatedWidth = width;
    allocatedHeight = height;
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, priorDraw);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, priorRead);
    glBindTexture(GL_TEXTURE_2D, priorTexture);
}

uintptr_t currentFramebuffer() { return framebuffer; }

retro_proc_address_t procAddress(const char* name) {
    auto proc = eglGetProcAddress(name);
    if (!proc) {
        proc =
            reinterpret_cast<__eglMustCastToProperFunctionPointerType>(dlsym(RTLD_DEFAULT, name));
    }
    return reinterpret_cast<retro_proc_address_t>(proc);
}

// Adopts the surfaces Java attached or detached since the last frame.
void updateWindows() {
    for (int slot = 0; slot < 2; slot++) {
        ANativeWindow* next;
        {
            std::lock_guard lock(windowsMutex);
            next = requestedWindows[slot];
            if (next) ANativeWindow_acquire(next);
        }
        Output& output = outputs[slot];
        if (next == output.window && (!useVulkan || !next || vulkan.visible(slot))) {
            if (next) ANativeWindow_release(next);
            continue;
        }
        if (useVulkan) {
            vulkan.window(slot, next);
            if (output.window) ANativeWindow_release(output.window);
            output.window = next;
            continue;
        }
        eglMakeCurrent(display, pbuffer, pbuffer, context);
        if (output.surface != EGL_NO_SURFACE) eglDestroySurface(display, output.surface);
        if (output.window) ANativeWindow_release(output.window);
        output.window = next;
        output.surface = EGL_NO_SURFACE;
        if (!next) continue;
        EGLint visual;
        eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &visual);
        ANativeWindow_setBuffersGeometry(next, 0, 0, visual);
        output.surface = eglCreateWindowSurface(display, config, next, nullptr);
        // The emulation loop paces frames; vsync on each of the two swaps would halve the rate.
        if (output.surface != EGL_NO_SURFACE &&
            eglMakeCurrent(display, output.surface, output.surface, context)) {
            eglSwapInterval(display, 0);
            eglMakeCurrent(display, pbuffer, pbuffer, context);
        }
    }
    dual = useVulkan ? vulkan.visible(1) : outputs[1].surface != EGL_NO_SURFACE;
}

// Scales the framebuffer rectangle (x0, y0)-(x1, y1) onto a screen, letterboxed.
void present(int slot, int x0, int y0, int x1, int y1) {
    Output& output = outputs[slot];
    if (output.surface == EGL_NO_SURFACE) return;
    uint64_t start = micros();
    if (eglMakeCurrent(display, output.surface, output.surface, context)) {
        while (glGetError() != GL_NO_ERROR) {
        }
        EGLint w = 0, h = 0;
        eglQuerySurface(display, output.surface, EGL_WIDTH, &w);
        eglQuerySurface(display, output.surface, EGL_HEIGHT, &h);
        float contentHeight = std::abs(y1 - y0);
        float contentWidth =
            !stacked && displayAspect > 0 ? contentHeight * displayAspect : std::abs(x1 - x0);
        float scale = std::min(float(w) / contentWidth, float(h) / contentHeight);
        int dw = std::lround(contentWidth * scale), dh = std::lround(contentHeight * scale);
        int dx = (w - dw) / 2, dy = (h - dh) / 2;
        glDisable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glBlitFramebuffer(x0, y0, x1, y1, dx, dy, dx + dw, dy + dh, GL_COLOR_BUFFER_BIT,
                          GL_NEAREST);
        if (glGetError() != GL_NO_ERROR) glErrors++;
        if (eglSwapBuffers(display, output.surface)) (slot ? upperFrames : lowerFrames)++;
    }
    presentMicros += micros() - start;
}

// Uploads a software frame into the framebuffer as RGBA.
void upload(const void* data, unsigned w, unsigned h, size_t pitch) {
    width = w;
    height = h;
    allocateFramebuffer();
    std::vector<uint8_t> rgba(size_t(w) * h * 4);
    auto* rows = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < h; y++) {
        for (unsigned x = 0; x < w; x++) {
            uint32_t pixel;
            if (format == RETRO_PIXEL_FORMAT_RGB565) {
                uint16_t value;
                memcpy(&value, rows + y * pitch + x * 2, 2);
                pixel = ((value >> 11) * 255 / 31) << 16 | (((value >> 5) & 63) * 255 / 63) << 8 |
                        (value & 31) * 255 / 31;
            } else {
                memcpy(&pixel, rows + y * pitch + x * 4, 4);
            }
            size_t at = (size_t(y) * w + x) * 4;
            rgba[at] = pixel >> 16;
            rgba[at + 1] = pixel >> 8;
            rgba[at + 2] = pixel;
            rgba[at + 3] = 255;
        }
    }
    glBindTexture(GL_TEXTURE_2D, texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
}

// libretro's video callback. GLES frames start at the bottom left; Vulkan and software frames
// start at the top left. A stacked frame's top half goes up and its bottom screen goes down.
// Single-screen frames go only to the upper display, including during surface recreation.
void video(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!data) return;
    if (useVulkan) {
        require(data == RETRO_HW_FRAME_BUFFER_VALID, "Vulkan core returned a software frame");
        uint64_t start = micros();
        unsigned mask = vulkan.present(w, h, stacked, lowerInset, presentAt);
        if (mask & 1) lowerFrames++;
        if (mask & 2) upperFrames++;
        presentMicros += micros() - start;
        return;
    }
    bool hardware = data == RETRO_HW_FRAME_BUFFER_VALID;
    if (!hardware) upload(data, w, h, pitch);
    int top = hardware ? h : 0, bottom = hardware ? 0 : h, middle = h / 2;
    int inset = std::lround(w * lowerInset);
    if (stacked) {
        present(1, 0, middle, w, top);
        present(0, inset, bottom, w - inset, middle);
    } else {
        present(1, 0, bottom, w, top);
    }
    eglMakeCurrent(display, pbuffer, pbuffer, context);
}

void setupGraphics() {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    require(display != EGL_NO_DISPLAY && eglInitialize(display, nullptr, nullptr),
            "EGL initialization failed");
    EGLint attributes[] = {EGL_RENDERABLE_TYPE,
                           EGL_OPENGL_ES3_BIT,
                           EGL_SURFACE_TYPE,
                           EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                           EGL_RED_SIZE,
                           8,
                           EGL_GREEN_SIZE,
                           8,
                           EGL_BLUE_SIZE,
                           8,
                           EGL_ALPHA_SIZE,
                           8,
                           EGL_NONE};
    EGLint count;
    require(eglChooseConfig(display, attributes, &config, 1, &count) && count,
            "No GLES3 display config");
    // Azahar's OpenGL renderer needs GLES 3.2; 3.0 still presents its software renderer.
    EGLint gles32[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 2,
                       EGL_NONE};
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, gles32);
    if (context == EGL_NO_CONTEXT) {
        EGLint gles3[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, gles3);
    }
    EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    pbuffer = eglCreatePbufferSurface(display, config, size);
    require(context != EGL_NO_CONTEXT && pbuffer != EGL_NO_SURFACE &&
                eglMakeCurrent(display, pbuffer, pbuffer, context),
            "GLES3 context creation failed");
    __android_log_print(ANDROID_LOG_INFO, kTag, "Host GL: %s", glGetString(GL_VERSION));
    allocateFramebuffer();
    updateWindows();
}

// Audio.

// Queues `count` stereo frames for output, dropping those that do not fit.
void push(const int16_t* data, size_t count) {
    size_t write = audioWrite.load(std::memory_order_relaxed);
    size_t space = audioRing.size() - (write - audioRead.load(std::memory_order_acquire));
    size_t samples = std::min(count * 2, space);
    audioDropped += count - samples / 2;
    for (size_t i = 0; i < samples; i++) audioRing[(write + i) % audioRing.size()] = data[i];
    audioWrite.store(write + samples, std::memory_order_release);
}

// Time-stretches fast-forward audio to real-time length at its original pitch (WSOLA). Each output
// grain starts where the input best matches the previous grain's end and crossfades into it.
class Stretch {
public:
    void reset(double rate) {
        grain = size_t(rate * .04);
        overlap = size_t(rate * .01);
        seek = size_t(rate * .015);
        input.clear();
        tail.assign(overlap * 2, 0);
        carry = 0;
        skip = 0;
        primed = false;
    }

    // Consumes `step` input frames per output frame; queues output through push.
    void add(const int16_t* data, size_t count, double step) {
        input.insert(input.end(), data, data + count * 2);
        while (true) {
            size_t dropped = std::min(skip, input.size() / 2);
            input.erase(input.begin(), input.begin() + dropped * 2);
            skip -= dropped;
            if (skip > 0 || input.size() / 2 < seek + grain) break;
            size_t best = primed ? match() : 0;
            output.clear();
            for (size_t i = 0; i < overlap * 2; i++) {
                float weight = float(i / 2) / overlap;
                output.push_back(int16_t(tail[i] * (1 - weight) + input[best * 2 + i] * weight));
            }
            auto body = input.begin() + (best + overlap) * 2;
            output.insert(output.end(), body, body + (grain - 2 * overlap) * 2);
            auto end = input.begin() + (best + grain - overlap) * 2;
            tail.assign(end, end + overlap * 2);
            primed = true;
            push(output.data(), output.size() / 2);
            double advance = step * (grain - overlap) + carry;
            skip = size_t(advance);
            carry = advance - skip;
        }
    }

private:
    // The offset within the seek window whose start correlates best with `tail`.
    size_t match() const {
        size_t best = 0;
        float bestScore = -INFINITY;
        for (size_t offset = 0; offset < seek; offset++) {
            float dot = 0, energy = 1;
            for (size_t i = 0; i < overlap; i += 2) {
                float a = float(tail[i * 2]) + tail[i * 2 + 1];
                float b = float(input[(offset + i) * 2]) + input[(offset + i) * 2 + 1];
                dot += a * b;
                energy += b * b;
            }
            float score = dot / std::sqrt(energy);
            if (score > bestScore) {
                bestScore = score;
                best = offset;
            }
        }
        return best;
    }

    // Input frames still to discard before the next grain.
    size_t grain = 0, overlap = 0, seek = 0, skip = 0;
    std::vector<int16_t> input, tail, output;
    double carry = 0;
    bool primed = false;
};

Stretch stretch;
std::atomic<bool> stretching{false};

// During fast-forward the measured speed sets the stretch, leaning on the queue's fill around
// kStretchQueueSeconds so output neither runs dry nor overflows.
size_t audio(const int16_t* data, size_t count) {
    audioFrames += count;
    if (!fastForward) {
        stretching = false;
        push(data, count);
        return count;
    }
    if (!fastForwardSound) return count;
    if (!stretching.exchange(true)) stretch.reset(audioRate);
    size_t queued =
        (audioWrite.load(std::memory_order_relaxed) - audioRead.load(std::memory_order_acquire)) /
        2;
    double fill = queued / (audioRate * kStretchQueueSeconds);
    stretch.add(data, count, std::max(1., speed.load()) * std::clamp(.5 + .5 * fill, .5, 1.5));
    return count;
}

void sample(int16_t left, int16_t right) {
    int16_t values[] = {left, right};
    audio(values, 1);
}

aaudio_data_callback_result_t outputAudio(AAudioStream*, void*, void* output, int32_t count) {
    auto* pcm = static_cast<int16_t*>(output);
    std::fill(pcm, pcm + count * 2, 0);
    static bool primed = false;
    size_t read = audioRead.load(std::memory_order_relaxed);
    size_t write = audioWrite.load(std::memory_order_acquire);
    if (audioFlush.exchange(false)) {
        audioRead.store(write, std::memory_order_release);
        primed = false;
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    if (paused) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    size_t available = write - read;
    if (!primed && available < audioPrefill) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    size_t samples = std::min(available, size_t(count) * 2);
    if (samples < size_t(count) * 2) audioUnderruns++;
    for (size_t i = 0; i < samples; i++) pcm[i] = audioRing[(read + i) % audioRing.size()];
    audioRead.store(read + samples, std::memory_order_release);
    audioDelivered += samples / 2;
    primed = samples == size_t(count) * 2;
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void setupAudio(double rate) {
    audioRate = rate;
    audioPrefill = size_t(rate * kAudioPrefillSeconds) * 2;
    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);
    if (!builder) return;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, int32_t(rate));
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, outputAudio, nullptr);
    auto result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result == AAUDIO_OK) {
        audioOutput = AAudioStream_requestStart(stream) == AAUDIO_OK;
    } else {
        stream = nullptr;
        __android_log_print(ANDROID_LOG_WARN, kTag, "Audio output unavailable: %s",
                            AAudio_convertResultToText(result));
    }
}

// Input.

void poll() {}

int16_t input(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        uint32_t bits = buttons | hats | triggerButtons;
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return int16_t(bits);
        return id < 16 && (bits & (1u << id)) ? 1 : 0;
    }
    if (device == RETRO_DEVICE_ANALOG) {
        int16_t value;
        if (index < 2 && id < 2)
            value = axes[index * 2 + id];
        else if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON &&
                 (id == RETRO_DEVICE_ID_JOYPAD_L2 || id == RETRO_DEVICE_ID_JOYPAD_R2))
            value = triggers[id == RETRO_DEVICE_ID_JOYPAD_R2];
        else
            return 0;
        analogPolls++;
        analogPeak = std::max(analogPeak.load(), uint64_t(std::abs(int(value))));
        return value;
    }
    if (device == RETRO_DEVICE_POINTER && index == 0) {
        if (id == RETRO_DEVICE_ID_POINTER_X) return pointerX;
        if (id == RETRO_DEVICE_ID_POINTER_Y) return pointerY;
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED) return pointerDown;
    }
    return 0;
}

void releaseInput() {
    buttons = hats = triggerButtons = 0;
    pointerDown = false;
    for (auto& axis : axes) axis = 0;
    for (auto& trigger : triggers) trigger = 0;
}

bool rumble(unsigned port, retro_rumble_effect effect, uint16_t strength) {
    if (port != 0 || (effect != RETRO_RUMBLE_STRONG && effect != RETRO_RUMBLE_WEAK)) return false;
    rumbleMotors[effect] = paused ? 0 : strength;
    rumbleRequests++;
    rumblePeak = std::max(rumblePeak.load(), uint64_t(strength));
    return true;
}

// The core's environment.

void readOptionOverrides() {
    for (auto& [key, value] : variables) {
        char override[PROP_VALUE_MAX] = "";
        if (__system_property_get(("debug.miel.option." + key).c_str(), override) > 0 &&
            value != override) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "Option %s: %s", key.c_str(), override);
            value = override;
            variablesChanged = true;
        }
    }
}

const char* defaultValue(const char* value, const char* first) {
    return value ? value : (first ? first : "");
}

bool environment(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            *static_cast<unsigned*>(data) = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
            std::memcpy(&disks, data, sizeof(disks));
            return true;
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
            static_cast<retro_rumble_interface*>(data)->set_rumble_state = rumble;
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = logCore;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 2;
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
            return environment(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2,
                               static_cast<retro_core_options_v2_intl*>(data)->us);
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            return environment(RETRO_ENVIRONMENT_SET_CORE_OPTIONS,
                               static_cast<retro_core_options_intl*>(data)->us);
        // Options the host did not set take the core's defaults.
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            auto* option = static_cast<retro_core_options_v2*>(data)->definitions;
            for (; option && option->key; option++) {
                variables.try_emplace(option->key,
                                      defaultValue(option->default_value, option->values[0].value));
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: {
            auto* option = static_cast<retro_core_option_definition*>(data);
            for (; option && option->key; option++) {
                variables.try_emplace(option->key,
                                      defaultValue(option->default_value, option->values[0].value));
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            // Each value reads "Description; first|second|…"; the first choice is the default.
            for (auto* variable = static_cast<retro_variable*>(data); variable && variable->key;
                 variable++) {
                std::string value = variable->value ? variable->value : "";
                auto choices = value.find("; ");
                if (choices != std::string::npos) value = value.substr(choices + 2);
                variables.try_emplace(variable->key, value.substr(0, value.find('|')));
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* variable = static_cast<retro_variable*>(data);
            auto it = variables.find(variable->key ? variable->key : "");
            variable->value = it == variables.end() ? nullptr : it->second.c_str();
            return variable->value != nullptr;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = variablesChanged;
            variablesChanged = false;
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            static std::string path;
            path = systemPath;
            *static_cast<const char**>(data) = path.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            static std::string path;
            path = saveDirectoryPath;
            *static_cast<const char**>(data) = path.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *static_cast<const char**>(data) = root.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
            auto* request = static_cast<retro_hw_render_context_negotiation_interface*>(data);
            request->interface_version =
                request->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN ? 1
                                                                                                : 0;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            auto* request =
                static_cast<retro_hw_render_context_negotiation_interface_vulkan*>(data);
            if (!useVulkan ||
                request->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN ||
                request->interface_version < 1)
                return false;
            vkNegotiation = request;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            if (!useVulkan || !vulkan.interface.device) return false;
            *static_cast<const retro_hw_render_interface**>(data) =
                reinterpret_cast<const retro_hw_render_interface*>(&vulkan.interface);
            return true;
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            *static_cast<unsigned*>(data) =
                useVulkan ? RETRO_HW_CONTEXT_VULKAN : RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            return true;
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto* request = static_cast<retro_hw_render_callback*>(data);
            if (request->context_type == RETRO_HW_CONTEXT_NONE && hasGame && !useVulkan) {
                hw = {};
                hwStarted = false;
                return true;
            }
            if (useVulkan) {
                if (request->context_type != RETRO_HW_CONTEXT_VULKAN) return false;
                hw = *request;
                return true;
            }
            if (request->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
                request->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                request->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) {
                return false;
            }
            GLint minor = 0;
            glGetIntegerv(GL_MINOR_VERSION, &minor);
            if (request->version_major > 3 ||
                (request->version_major == 3 && request->version_minor > unsigned(minor))) {
                return false;
            }
            request->get_current_framebuffer = currentFramebuffer;
            request->get_proc_address = procAddress;
            hw = *request;
            contextResetPending = hasGame;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            format = *static_cast<retro_pixel_format*>(data);
            return format == RETRO_PIXEL_FORMAT_XRGB8888 || format == RETRO_PIXEL_FORMAT_RGB565;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            auto* geometry = static_cast<retro_game_geometry*>(data);
            if (geometry->base_width > kMaxFrameSize || geometry->base_height > kMaxFrameSize) {
                return false;
            }
            width = geometry->base_width;
            height = geometry->base_height;
            if (coreName == "dolphin-emu") displayAspect = geometry->aspect_ratio;
            if (!useVulkan && context != EGL_NO_CONTEXT) allocateFramebuffer();
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            return environment(RETRO_ENVIRONMENT_SET_GEOMETRY,
                               &static_cast<retro_system_av_info*>(data)->geometry);
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
            frameTime = *static_cast<retro_frame_time_callback*>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK:
            asyncAudio = *static_cast<retro_audio_callback*>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            *static_cast<int*>(data) = 3;
            return true;
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            *static_cast<float*>(data) = 60.f;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            std::lock_guard lock(textMutex);
            lastCoreMessage = static_cast<retro_message*>(data)->msg;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            *static_cast<unsigned*>(data) = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            if (!data) return true;
            const auto& notification = *static_cast<retro_message_ext*>(data);
            if (!ready && notification.type == RETRO_MESSAGE_TYPE_STATUS) {
                std::lock_guard lock(textMutex);
                loadingMessage = notification.msg && *notification.msg ? notification.msg : message;
                loadingProgress = -1;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SHUTDOWN:
            failed = true;
            status("Core requested shutdown");
            stopping = true;
            return true;
        default:
            return false;
    }
}

// Checkpoints.

uint64_t fnv1a(uint64_t hash, const uint8_t* bytes, size_t count) {
    for (size_t i = 0; i < count; i++) hash = (hash ^ bytes[i]) * kFnvPrime;
    return hash;
}

// Validates the checkpoint at `path` for this core, streaming it so that checking a backup does not
// hold a second copy of the state. Fills `payload` when given.
bool readState(const std::string& path, std::vector<uint8_t>* payload = nullptr) {
    std::ifstream input(path, std::ios::binary);
    if (!input) return false;
    char magic[kMagicLength];
    uint32_t length = 0;
    input.read(magic, kMagicLength);
    input.read(reinterpret_cast<char*>(&length), 4);
    if (!input || memcmp(magic, kStateMagic, kMagicLength) || length > 4096) return false;
    std::string stored(length, '\0');
    uint64_t count = 0, expected = 0;
    input.read(stored.data(), length);
    input.read(reinterpret_cast<char*>(&count), 8);
    if (!input || stored != identity() || !count || count > kMaxStateSize) return false;
    input.read(reinterpret_cast<char*>(&expected), 8);
    if (payload) payload->resize(count);
    uint64_t actual = kFnvOffset, offset = 0;
    char chunk[32768];
    while (offset < count) {
        size_t amount = std::min<uint64_t>(sizeof(chunk), count - offset);
        input.read(chunk, amount);
        if (!input) return false;
        actual = fnv1a(actual, reinterpret_cast<const uint8_t*>(chunk), amount);
        if (payload) memcpy(payload->data() + offset, chunk, amount);
        offset += amount;
    }
    return actual == expected && input.peek() == EOF;
}

bool restoreFile(const std::string& path) {
    std::vector<uint8_t> bytes;
    return readState(path, &bytes) && api.unserialize(bytes.data(), bytes.size());
}

bool copySynced(const std::string& from, const std::string& to) {
    FILE* input = fopen(from.c_str(), "rb");
    if (!input) return false;
    FILE* output = fopen(to.c_str(), "wb");
    if (!output) {
        fclose(input);
        return false;
    }
    char chunk[32768];
    size_t count;
    bool ok = true;
    while ((count = fread(chunk, 1, sizeof(chunk), input)) > 0) {
        if (fwrite(chunk, 1, count, output) != count) {
            ok = false;
            break;
        }
    }
    ok = !ferror(input) && ok;
    fclose(input);
    ok = fflush(output) == 0 && ok;
    ok = fsync(fileno(output)) == 0 && ok;
    ok = fclose(output) == 0 && ok;
    if (!ok) unlink(to.c_str());
    return ok;
}

// The core's in-game save memory, which the host keeps at sramPath. Empty for a core without one.
std::vector<uint8_t> sram() {
    auto* data = static_cast<uint8_t*>(api.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || !size) return {};
    return {data, data + size};
}

void loadSram() {
    auto* data = static_cast<char*>(api.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    std::ifstream input(sramPath, std::ios::binary);
    if (data && size && input) input.read(data, size);
}

bool syncDirectory(const std::string& path) {
    int directory = open(path.substr(0, path.find_last_of('/')).c_str(), O_RDONLY | O_DIRECTORY);
    if (directory < 0) return false;
    bool synced = fsync(directory) == 0;
    close(directory);
    return synced;
}

// Replaces `path` with `bytes` through a synced temporary file.
bool writeSynced(const std::string& path, const std::vector<uint8_t>& bytes) {
    std::string temporary = path + ".tmp";
    FILE* file = fopen(temporary.c_str(), "wb");
    if (!file) return false;
    bool ok = fwrite(bytes.data(), 1, bytes.size(), file) == bytes.size() && fflush(file) == 0 &&
              fsync(fileno(file)) == 0;
    ok = fclose(file) == 0 && ok;
    if (!ok || rename(temporary.c_str(), path.c_str()) != 0) {
        unlink(temporary.c_str());
        return false;
    }
    return syncDirectory(path);
}

// The core's state between frames, or empty when it cannot be taken.
std::vector<uint8_t> snapshot() {
    if (!hasGame || !ready || statePath.empty()) return {};
    size_t count = api.serialize_size();
    if (!count || count > kMaxStateSize) return {};
    std::vector<uint8_t> bytes(count);
    if (!api.serialize(bytes.data(), count)) return {};
    return bytes;
}

// Writes a checkpoint off the worker thread: a synced temporary file, renamed over `path`. A valid
// previous checkpoint is first copied to `.bak`, so the primary path never goes missing and a
// damaged primary never replaces a good backup.
bool writeCheckpoint(const std::string path, const std::string identity,
                     const std::vector<uint8_t> bytes) {
    uint32_t length = identity.size();
    uint64_t size = bytes.size(), checksum = fnv1a(kFnvOffset, bytes.data(), bytes.size());
    std::string temporary = path + ".tmp";
    FILE* file = fopen(temporary.c_str(), "wb");
    if (!file) return false;
    bool ok = fwrite(kStateMagic, 1, kMagicLength, file) == kMagicLength &&
              fwrite(&length, 4, 1, file) == 1 &&
              fwrite(identity.data(), 1, length, file) == length &&
              fwrite(&size, 8, 1, file) == 1 && fwrite(&checksum, 8, 1, file) == 1 &&
              fwrite(bytes.data(), 1, size, file) == size;
    ok = ok && fflush(file) == 0 && fsync(fileno(file)) == 0;
    ok = fclose(file) == 0 && ok;
    if (!ok) {
        unlink(temporary.c_str());
        return false;
    }
    if (readState(path)) {
        std::string backup = path + ".bak", backupTemporary = backup + ".tmp";
        if (!copySynced(path, backupTemporary) ||
            rename(backupTemporary.c_str(), backup.c_str()) != 0) {
            unlink(temporary.c_str());
            return false;
        }
    }
    if (rename(temporary.c_str(), path.c_str()) != 0) {
        unlink(temporary.c_str());
        return false;
    }
    return syncDirectory(path);
}

// Writes the in-game save, then the checkpoint.
bool writeSave(const std::string path, const std::string identity, const std::vector<uint8_t> bytes,
               const std::string savePath, const std::vector<uint8_t> save) {
    return (save.empty() || writeSynced(savePath, save)) && writeCheckpoint(path, identity, bytes);
}

// Releases the core and graphics. Azahar's context_destroy uses its GPU objects and window, so GL
// resources go while the context is still current, before retro_unload_game.
void cleanup() {
    for (auto& motor : rumbleMotors) motor = 0;
    if (stream) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        stream = nullptr;
    }
    if (display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT) {
        eglMakeCurrent(display, pbuffer, pbuffer, context);
    }
    if (useVulkan) vulkan.idle();
    if (hwStarted && hw.context_destroy) hw.context_destroy();
    hwStarted = false;
    if (hasGame) api.unload_game();
    hasGame = false;
    if (initialized) api.deinit();
    initialized = false;
    if (useVulkan) {
        vulkan.cleanup();
        for (auto& output : outputs) {
            if (output.window) ANativeWindow_release(output.window);
            output = {};
        }
    }
    if (display != EGL_NO_DISPLAY) {
        if (context != EGL_NO_CONTEXT) eglMakeCurrent(display, pbuffer, pbuffer, context);
        if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
        if (texture) glDeleteTextures(1, &texture);
        if (depthBuffer) glDeleteRenderbuffers(1, &depthBuffer);
        for (auto& output : outputs) {
            if (output.surface != EGL_NO_SURFACE) eglDestroySurface(display, output.surface);
            if (output.window) ANativeWindow_release(output.window);
            output = {};
        }
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (pbuffer != EGL_NO_SURFACE) eglDestroySurface(display, pbuffer);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
    }
    display = EGL_NO_DISPLAY;
    context = EGL_NO_CONTEXT;
    pbuffer = EGL_NO_SURFACE;
    framebuffer = texture = depthBuffer = allocatedWidth = allocatedHeight = 0;
}

// Tells Android's scheduler (API 33) the emulation thread's frame budget.
struct PerformanceHint {
    void* session = nullptr;
    int (*report)(void*, int64_t) = nullptr;
    void (*close)(void*) = nullptr;

    explicit PerformanceHint(double fps) {
        void* android = dlopen("libandroid.so", RTLD_NOW);
        if (!android) return;
        auto manager = reinterpret_cast<void* (*)()>(dlsym(android, "APerformanceHint_getManager"));
        auto create = reinterpret_cast<void* (*)(void*, const int32_t*, size_t, int64_t)>(
            dlsym(android, "APerformanceHint_createSession"));
        report = reinterpret_cast<int (*)(void*, int64_t)>(
            dlsym(android, "APerformanceHint_reportActualWorkDuration"));
        close = reinterpret_cast<void (*)(void*)>(dlsym(android, "APerformanceHint_closeSession"));
        int32_t thread = gettid();
        if (manager && create && report && close) {
            if (void* hints = manager()) session = create(hints, &thread, 1, int64_t(1e9 / fps));
        }
    }

    void work(uint64_t spentMicros) {
        if (session) report(session, int64_t(spentMicros) * 1000);
    }

    ~PerformanceHint() {
        if (session) close(session);
    }
};

// Logs per-frame averages every kStatsInterval: frame rate, audio rate, core time (run, which
// includes present), sleep, the slowest frame and the last checkpoint's snapshot and write times.
struct Stats {
    uint64_t at = micros(), frames = 0, audio = 0, run = 0, present = 0, sleep = 0, worst = 0;

    void frame(uint64_t spent) {
        worst = std::max(worst, spent);
        uint64_t now = micros();
        if (now - at < uint64_t(std::chrono::microseconds(kStatsInterval).count())) return;
        double seconds = (now - at) / 1e6;
        double count = std::max<double>(1, ::frames - frames);
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "stats fps=%.1f audio=%.0fHz run=%.1fms present=%.1fms sleep=%.1fms "
                            "worst=%.0fms snapshot=%.0fms write=%.0fms",
                            (::frames - frames) / seconds, (audioFrames - audio) / seconds,
                            (runMicros - run) / count / 1e3,
                            (presentMicros - present) / count / 1e3,
                            (sleepMicros - sleep) / count / 1e3, worst / 1e3, snapshotMicros / 1e3,
                            writeMicros / 1e3);
        *this = {now, ::frames, audioFrames, runMicros, presentMicros, sleepMicros, 0};
    }
};

void loadCore(void* handle) {
#define LOAD(name)                                                                  \
    api.name = reinterpret_cast<decltype(api.name)>(dlsym(handle, "retro_" #name)); \
    require(api.name, "Missing libretro entry point: " #name)
    LOAD(api_version);
    LOAD(init);
    LOAD(deinit);
    LOAD(get_system_info);
    LOAD(get_system_av_info);
    LOAD(set_environment);
    LOAD(set_video_refresh);
    LOAD(set_audio_sample);
    LOAD(set_audio_sample_batch);
    LOAD(set_input_poll);
    LOAD(set_input_state);
    LOAD(set_controller_port_device);
    LOAD(load_game);
    LOAD(unload_game);
    LOAD(run);
    LOAD(serialize_size);
    LOAD(serialize);
    LOAD(unserialize);
    LOAD(get_memory_data);
    LOAD(get_memory_size);
#undef LOAD
    require(api.api_version() == RETRO_API_VERSION, "Unsupported libretro API version");
}

// Restore after bootstrap, once the core can serialize its state.
void restoreSession(const Launch& launch) {
    bool exists =
        access(statePath.c_str(), F_OK) == 0 || access((statePath + ".bak").c_str(), F_OK) == 0;
    if (!launch.fresh && exists) {
        bool primary = restoreFile(statePath);
        restoredBackup = !primary && restoreFile(statePath + ".bak");
        restored = primary || restoredBackup;
        if (!restored) {
            restoreBlocked = true;
            throw std::runtime_error(
                "Your previous session could not be restored. It has been kept unchanged. Return "
                "to the library to retry, or choose Start fresh from More.");
        }
    }
    ready = true;
    status("Session ready");
    // A new or deliberately fresh session gets a durable recovery point at once.
    if (launch.fresh || !exists) saveRequested = true;
}

// Runs frames until Java stops the session or a requested exit has saved. The worker snapshots
// the core between frames; the write runs on another thread, and the next checkpoint waits for it.
void loop(const Launch& launch, double fps) {
    PerformanceHint hint(fps);
    Stats stats;
    const auto interval = kDiagnosticCheckpoint;
    const auto period =
        std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(1. / fps));
    auto next = Clock::now(), checkpointDue = next + interval, lastFrame = next;
    double averageFrame = 1. / fps;
    std::future<bool> writing;
    bool writingExit = false, writingPeriodic = false;
    uint64_t writeStart = 0, writingFrame = 0;
    auto finishSave = [&](bool ok) {
        checkpointFailed = !ok;
        status(ok ? "Session ready" : "Checkpoint failed; session kept open");
        if (!ok) {
            paused = true;  // Play never continues silently after a failed write.
            return;
        }
        saved = true;
        checkpoints++;
        lastSavedFrame = writingFrame;
        if (writingPeriodic) periodicSaves++;
    };

    bool streamPaused = false;
    while (!stopping) {
        // The audio stream pauses with the game.
        if (stream && paused != streamPaused) {
            streamPaused = paused;
            if (streamPaused)
                AAudioStream_requestPause(stream);
            else
                AAudioStream_requestStart(stream);
        }
        int analog = requestedAnalog.exchange(-1);
        if (analog >= 0 && launch.coreName == "PCSX-ReARMed") {
            api.set_controller_port_device(
                0, analog ? RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_ANALOG, 1) : RETRO_DEVICE_JOYPAD);
            analogEnabled = analog != 0;
        }
        if (ready && disks.get_num_images && disks.get_image_index) {
            int index = requestedDisc.exchange(-1);
            if (index >= 0 && disks.set_eject_state && disks.set_image_index) {
                bool opened = disks.set_eject_state(true);
                bool selected = opened && disks.set_image_index(unsigned(index));
                bool closed = opened && disks.set_eject_state(false);
                discFailed = !selected || !closed;
            }
            discCount = disks.get_num_images();
            discIndex = disks.get_image_index();
        }
        updateWindows();
        if (!useVulkan) eglMakeCurrent(display, pbuffer, pbuffer, context);
        bool periodic = launch.diagnostic && ready && !paused &&
                        (useVulkan ? vulkan.visible(0) : outputs[0].surface != EGL_NO_SURFACE) &&
                        Clock::now() >= checkpointDue;
        if (writing.valid() &&
            writing.wait_for(std::chrono::seconds(0)) == std::future_status::ready) {
            bool ok = writing.get();
            writeMicros = micros() - writeStart;
            finishSave(ok);
            if (writingExit && ok) {
                exitReady = true;
                return;
            }
        }
        if (ready && !writing.valid() && (saveRequested.exchange(false) || periodic)) {
            writingExit = exitAfterSave.exchange(false);
            writingPeriodic = periodic;
            checkpointDue = Clock::now() + interval;
            uint64_t start = micros();
            auto bytes = snapshot();
            snapshotMicros = micros() - start;
            if (bytes.empty()) {
                finishSave(false);
            } else {
                writeStart = micros();
                writingFrame = frames;
                writing = std::async(std::launch::async, writeSave, statePath, identity(),
                                     std::move(bytes), sramPath, sram());
            }
        }
        // The bootstrap frames run even while paused or hidden: a Home pressed during loading
        // must never save over a session that has not been restored.
        if (ready &&
            (paused || (useVulkan ? !vulkan.visible(0) : outputs[0].surface == EGL_NO_SURFACE))) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            next = lastFrame = Clock::now();
            continue;
        }
        if (frameTime.callback) frameTime.callback(retro_usec_t(1000000. / fps));
        if (asyncAudio.callback) asyncAudio.callback();
        presentAt = std::chrono::duration_cast<std::chrono::nanoseconds>(
                        (next + period * kPresentLead).time_since_epoch())
                        .count();
        uint64_t start = micros();
        api.run();
        uint64_t spent = micros() - start;
        runMicros += spent;
        if (contextResetPending) {
            contextResetPending = false;
            allocateFramebuffer();
            if (hw.context_reset) hw.context_reset();
            hwStarted = true;
        }
        if (frames % 60 == 0) readOptionOverrides();
        hint.work(spent);
        frames++;
        // Azahar calculates its state size by taking a full snapshot.
        if (!ready && frames >= 2 && (coreName == "Azahar" || api.serialize_size() > 0)) {
            restoreSession(launch);
            checkpointDue = Clock::now() + interval;
        }
        // Pace without vsync; after a stall of over 100 ms, start counting again from now.
        next += fastForward
                    ? std::chrono::duration_cast<Clock::duration>(period / fastForwardSpeed.load())
                    : period;
        if (next > Clock::now()) {
            uint64_t sleepStart = micros();
            std::this_thread::sleep_until(next);
            sleepMicros += micros() - sleepStart;
        } else if (Clock::now() - next > std::chrono::milliseconds(100)) {
            next = Clock::now();
        }
        auto now = Clock::now();
        averageFrame +=
            .02 * (std::chrono::duration<double>(now - lastFrame).count() - averageFrame);
        speed = std::min(1. / fps / std::max(averageFrame, 1e-6), 16.);
        lastFrame = now;
        stats.frame(spent);
    }
    if (writing.valid()) finishSave(writing.get());
}

void run(const Launch launch) {
    void* handle = nullptr;
    try {
        status("Loading core…");
        handle = dlopen(launch.library.c_str(), RTLD_NOW | RTLD_LOCAL);
        const char* loadError = dlerror();
        require(handle,
                std::string("Cannot load core: ") + (loadError ? loadError : "unknown error"));
        loadCore(handle);
        retro_system_info info{};
        api.get_system_info(&info);
        {
            std::lock_guard lock(textMutex);
            coreName = info.library_name ? info.library_name : "";
            coreVersion = info.library_version ? info.library_version : "";
        }
        require(coreName == launch.coreName, "Unexpected core identity: " + coreName);
        variables = {launch.options.begin(), launch.options.end()};
        useVulkan = coreName == "Azahar" && variables["citra_graphics_api"] == "Vulkan";
        vkNegotiation = nullptr;
        api.set_environment(environment);
        api.set_video_refresh(video);
        api.set_audio_sample(sample);
        api.set_audio_sample_batch(audio);
        api.set_input_poll(poll);
        api.set_input_state(input);
        api.init();
        initialized = true;
        if (coreName != "dolphin-emu") api.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
        if (launch.probe) {
            status(coreName + " " + coreVersion + " initialized; no ROM loaded");
        } else {
            if (!useVulkan) setupGraphics();
            if (coreName == "Azahar") {
                GLint minor = 0;
                if (!useVulkan) glGetIntegerv(GL_MINOR_VERSION, &minor);
                if (!useVulkan && minor < 2) variables["citra_graphics_api"] = "Software";
#if defined(__aarch64__)
                // Under binary translation the kernel reports another machine; Azahar's JIT
                // cannot run there.
                utsname kernel{};
                if (uname(&kernel) == 0 && std::string(kernel.machine) != "aarch64" &&
                    std::string(kernel.machine) != "arm64") {
                    variables["citra_use_cpu_jit"] = "disabled";
                }
#endif
            }
            status("Opening game…");
            retro_game_info game{};
            game.path = launch.rom.c_str();
            std::vector<char> content;
            if (!info.need_fullpath) {
                std::ifstream input(launch.rom, std::ios::binary);
                content.assign(std::istreambuf_iterator<char>(input), {});
                require(!content.empty(), "Cannot read the game file");
                game.data = content.data();
                game.size = content.size();
            }
            bool loaded = api.load_game(&game);
            content = {};
            std::string detail;
            {
                std::lock_guard lock(textMutex);
                detail = lastCoreMessage;
            }
            require(loaded, detail.empty() ? coreName + " could not load this game." : detail);
            if (coreName == "dolphin-emu") api.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
            // PCSX resets its controller types while loading content.
            if (coreName == "PCSX-ReARMed")
                api.set_controller_port_device(0, RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_ANALOG, 1));
            hasGame = true;
            loadSram();
            retro_system_av_info av{};
            api.get_system_av_info(&av);
            width = av.geometry.base_width;
            height = av.geometry.base_height;
            displayAspect = coreName == "dolphin-emu" ? av.geometry.aspect_ratio : 0;
            if (useVulkan) {
                vulkan.setup(vkNegotiation);
                updateWindows();
            } else {
                allocateFramebuffer();
            }
            if (hw.context_reset) {
                hw.context_reset();
                hwStarted = true;
                hardwareContext = true;
            }
            setupAudio(av.timing.sample_rate > 0 ? av.timing.sample_rate : 32728);
            if (asyncAudio.set_state) asyncAudio.set_state(true);
            status("Restoring session…");
            loop(launch, av.timing.fps > 0 ? av.timing.fps : 60.);
            if (asyncAudio.set_state) asyncAudio.set_state(false);
        }
    } catch (const std::exception& error) {
        failed = true;
        status(error.what());
    }
    try {
        cleanup();
    } catch (const std::exception& error) {
        failed = true;
        status(error.what());
    }
    if (handle) dlclose(handle);
    running = false;
}

std::string text(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

// A flat JSON object for the report.
class Json {
public:
    Json& add(const char* name, const std::string& value) {
        key(name);
        body += '"';
        for (unsigned char c : value) {
            if (c == '"' || c == '\\') {
                body += '\\';
                body += c;
            } else if (c == '\n') {
                body += "\\n";
            } else if (c >= 32) {
                body += c;
            }
        }
        body += '"';
        return *this;
    }

    Json& add(const char* name, bool value) {
        key(name);
        body += value ? "true" : "false";
        return *this;
    }

    Json& add(const char* name, uint64_t value) {
        key(name);
        body += std::to_string(value);
        return *this;
    }

    Json& add(const char* name, double value) {
        key(name);
        body += std::to_string(value);
        return *this;
    }

    std::string str() const { return "{" + body + "}"; }

private:
    void key(const char* name) {
        if (!body.empty()) body += ',';
        body += '"';
        body += name;
        body += "\":";
    }

    std::string body;
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL Java_io_github_landern_miel_CoreHost_discardRomCache(
    JNIEnv*, jclass, jint fd, jlong offset, jlong length) {
    return posix_fadvise(fd, offset, length, POSIX_FADV_DONTNEED);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_start(
    JNIEnv* env, jclass, jstring library, jstring rom, jstring directory, jstring systemDirectory,
    jstring saveDirectory, jstring state, jstring save, jstring name, jobjectArray options,
    jboolean twoScreens, jfloat inset, jboolean diagnostic, jboolean probe, jboolean fresh) {
    if (running) return;
    if (worker.joinable()) worker.join();
    root = text(env, directory);
    systemPath = text(env, systemDirectory);
    saveDirectoryPath = text(env, saveDirectory);
    statePath = text(env, state);
    sramPath = text(env, save);
    stacked = twoScreens;
    lowerInset = std::clamp(float(inset), 0.f, .5f);
    Launch launch{text(env, library), text(env, rom), text(env, name), {},
                  bool(diagnostic),   bool(probe),    bool(fresh)};
    for (jsize i = 0; i + 1 < env->GetArrayLength(options); i += 2) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(options, i));
        auto value = static_cast<jstring>(env->GetObjectArrayElement(options, i + 1));
        launch.options.emplace_back(text(env, key), text(env, value));
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    coreName.clear();
    coreVersion.clear();
    lastCoreMessage.clear();
    loadingMessage.clear();
    loadingProgress = -1;
    frames = upperFrames = lowerFrames = audioFrames = audioDelivered = inputEvents = glErrors = 0;
    checkpoints = lastSavedFrame = periodicSaves = 0;
    audioDropped = audioUnderruns = 0;
    analogPolls = analogPeak = rumbleRequests = rumblePeak = 0;
    for (auto& motor : rumbleMotors) motor = 0;
    paused = stopping = saveRequested = exitAfterSave = false;
    ready = restored = restoredBackup = restoreBlocked = saved = checkpointFailed = failed =
        exitReady = false;
    hardwareContext = audioOutput = false;
    releaseInput();
    disks = {};
    discCount = discIndex = 0;
    requestedDisc = requestedAnalog = -1;
    analogEnabled = true;
    discFailed = false;
    fastForward = false;
    speed = 1;
    hw = {};
    frameTime = {};
    asyncAudio = {};
    format = RETRO_PIXEL_FORMAT_XRGB8888;
    width = 400;
    height = 480;
    audioFlush = true;
    running = true;
    worker = std::thread(run, std::move(launch));
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_surface(JNIEnv* env, jclass,
                                                                               jint slot,
                                                                               jobject surface) {
    if (slot < 0 || slot > 1) return;
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard lock(windowsMutex);
    if (requestedWindows[slot]) ANativeWindow_release(requestedWindows[slot]);
    requestedWindows[slot] = window;
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_analog(JNIEnv*, jclass,
                                                                              jboolean enabled) {
    requestedAnalog = enabled ? 1 : 0;
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_fastForward(JNIEnv*, jclass,
                                                                                   jboolean on,
                                                                                   jfloat maximum,
                                                                                   jboolean sound) {
    fastForwardSpeed = std::clamp(double(maximum), 1.5, 8.);
    fastForwardSound = sound;
    fastForward = on;
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_changeDisc(JNIEnv*, jclass,
                                                                                  jint index) {
    if (index >= 0 && unsigned(index) < discCount) requestedDisc = index;
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_landern_miel_CoreHost_rumble(JNIEnv*, jclass) {
    if (!running || !ready || paused || stopping || exitAfterSave || failed || exitReady) return 0;
    return std::max(rumbleMotors[0].load(), rumbleMotors[1].load());
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_pause(JNIEnv*, jclass,
                                                                             jboolean value) {
    paused = value;
    if (value) {
        releaseInput();
        for (auto& motor : rumbleMotors) motor = 0;
        audioFlush = true;
    } else {
        checkpointFailed = false;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_save(JNIEnv*, jclass,
                                                                            jboolean exit) {
    if (exit) exitAfterSave = true;
    saveRequested = true;
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_stop(JNIEnv*, jclass) {
    // A save requested by onPause still finishes when the activity is destroyed.
    if (exitAfterSave || saveRequested) {
        exitAfterSave = true;
        saveRequested = true;
    } else {
        stopping = true;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_button(JNIEnv*, jclass,
                                                                              jint id,
                                                                              jboolean down) {
    if (id < 0 || id > 15) return;
    if (down && !paused) {
        buttons.fetch_or(1u << id);
    } else {
        buttons.fetch_and(~(1u << id));
    }
    inputEvents++;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_landern_miel_CoreHost_axes(JNIEnv*, jclass, jfloat x, jfloat y, jfloat z, jfloat rz,
                                          jfloat hatX, jfloat hatY, jfloat left, jfloat right) {
    if (paused) return;
    float values[] = {x, y, z, rz};
    for (int i = 0; i < 4; i++) axes[i] = int16_t(std::clamp(values[i], -1.f, 1.f) * 32767);
    hats = (hatX < -.5f ? 1u << RETRO_DEVICE_ID_JOYPAD_LEFT : 0) |
           (hatX > .5f ? 1u << RETRO_DEVICE_ID_JOYPAD_RIGHT : 0) |
           (hatY < -.5f ? 1u << RETRO_DEVICE_ID_JOYPAD_UP : 0) |
           (hatY > .5f ? 1u << RETRO_DEVICE_ID_JOYPAD_DOWN : 0);
    inputEvents++;
    triggerButtons = (left > .5f ? 1u << RETRO_DEVICE_ID_JOYPAD_L2 : 0) |
                     (right > .5f ? 1u << RETRO_DEVICE_ID_JOYPAD_R2 : 0);
    triggers[0] = int16_t(std::clamp(left, 0.f, 1.f) * 32767);
    triggers[1] = int16_t(std::clamp(right, 0.f, 1.f) * 32767);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_landern_miel_CoreHost_touch(JNIEnv*, jclass,
                                                                             jfloat x, jfloat y,
                                                                             jboolean down) {
    pointerX = int16_t(std::clamp(x, -1.f, 1.f) * 32767);
    pointerY = int16_t(std::clamp(y, -1.f, 1.f) * 32767);
    pointerDown = down && !paused;
    inputEvents++;
}

extern "C" JNIEXPORT jstring JNICALL Java_io_github_landern_miel_CoreHost_report(JNIEnv* env,
                                                                                 jclass) {
    Json report;
    {
        std::lock_guard lock(textMutex);
        report.add("message", message).add("core", coreName).add("version", coreVersion);
        report.add("loadingMessage", loadingMessage).add("loadingProgress", loadingProgress);
    }
    report.add("running", running.load())
        .add("failed", failed.load())
        .add("paused", paused.load())
        .add("restored", restored.load())
        .add("saved", saved.load())
        .add("exitReady", exitReady.load())
        .add("dual", dual.load())
        .add("frames", frames.load())
        .add("upperFrames", upperFrames.load())
        .add("lowerFrames", lowerFrames.load())
        .add("audioFrames", audioFrames.load())
        .add("inputEvents", inputEvents.load())
        .add("analogEnabled", analogEnabled.load())
        .add("fastForward", fastForward.load())
        .add("speed", speed.load())
        .add("analogPolls", analogPolls.load())
        .add("analogPeak", analogPeak.load())
        .add("rumbleRequests", rumbleRequests.load())
        .add("rumblePeak", rumblePeak.load())
        .add("ready", ready.load())
        .add("discCount", uint64_t(discCount.load()))
        .add("discIndex", uint64_t(discIndex.load()))
        .add("discFailed", discFailed.load())
        .add("checkpointFailed", checkpointFailed.load())
        .add("restoreBlocked", restoreBlocked.load())
        .add("checkpoints", checkpoints.load())
        .add("lastSavedFrame", lastSavedFrame.load())
        .add("periodicSaves", periodicSaves.load())
        .add("hardware", hardwareContext.load())
        .add("audioOutput", audioOutput.load())
        .add("restoredBackup", restoredBackup.load())
        .add("audioDelivered", audioDelivered.load())
        .add("audioDropped", audioDropped.load())
        .add("audioUnderruns", audioUnderruns.load())
        .add("glErrors", glErrors.load())
        .add("runMicros", runMicros.load())
        .add("presentMicros", presentMicros.load())
        .add("sleepMicros", sleepMicros.load())
        .add("snapshotMicros", snapshotMicros.load())
        .add("writeMicros", writeMicros.load());
    return env->NewStringUTF(report.str().c_str());
}
