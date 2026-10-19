// Deterministic host diagnostic, never used to emulate a ROM.
#include <GLES3/gl3.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include "libretro.h"
static retro_environment_t environment;
static retro_video_refresh_t video;
static retro_audio_sample_batch_t audio;
static retro_input_poll_t poll;
static retro_input_state_t input;
static uint64_t frame;
static bool hardware = false;
static retro_hw_render_callback hw{};
static retro_rumble_interface rumble{};
static std::vector<uint32_t> pixels(400 * 480);
extern "C" {
RETRO_API unsigned retro_api_version() { return RETRO_API_VERSION; }
RETRO_API void retro_set_environment(retro_environment_t cb) { environment = cb; }
RETRO_API void retro_set_video_refresh(retro_video_refresh_t cb) { video = cb; }
RETRO_API void retro_set_audio_sample(retro_audio_sample_t) {}
RETRO_API void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio = cb; }
RETRO_API void retro_set_input_poll(retro_input_poll_t cb) { poll = cb; }
RETRO_API void retro_set_input_state(retro_input_state_t cb) { input = cb; }
RETRO_API void retro_set_controller_port_device(unsigned, unsigned) {}
RETRO_API void retro_init() {}
RETRO_API void retro_deinit() {}
RETRO_API void retro_get_system_info(retro_system_info* info) {
    *info = {"miel test core", "1", "test", true, false};
}
RETRO_API void retro_get_system_av_info(retro_system_av_info* info) {
    *info = {{400, 480, 400, 480, 400.f / 480.f}, {60., 48000.}};
}
RETRO_API bool retro_load_game(const retro_game_info* game) {
    frame = 0;
    rumble = {};
    if (game && game->path && std::strstr(game->path, "-rumble") != nullptr &&
        !environment(RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE, &rumble))
        return false;
    retro_pixel_format format = RETRO_PIXEL_FORMAT_XRGB8888;
    if (!environment(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format)) return false;
    hardware = game && game->path && std::strstr(game->path, "-hw") != nullptr;
    if (hardware) {
        hw = {};
        hw.context_type = RETRO_HW_CONTEXT_OPENGLES3;
        hw.version_major = 3;
        hw.version_minor = 0;
        hw.bottom_left_origin = true;
        hw.context_reset = []() {};
        return environment(RETRO_ENVIRONMENT_SET_HW_RENDER, &hw);
    }
    return true;
}
RETRO_API void retro_unload_game() {}
RETRO_API void retro_run() {
    poll();
    bool pressed = input(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A) != 0;
    if (rumble.set_rumble_state) {
        bool weak = input(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B) != 0;
        rumble.set_rumble_state(0, RETRO_RUMBLE_STRONG, pressed ? 65535 : 0);
        rumble.set_rumble_state(0, RETRO_RUMBLE_WEAK, weak ? 32768 : 0);
    }
    bool touch = input(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_PRESSED) != 0;
    for (int y = 0; y < 480; y++)
        for (int x = 0; x < 400; x++) {
            uint32_t color = y < 240 ? 0x28554b : 0x4b3c59;
            if (y < 240 && x >= int(frame % 320) && x < int(frame % 320) + 60 && y > 60 && y < 180)
                color = 0x78dfb7;
            if (y >= 240 && x >= 40 && x < 360)
                color = pressed ? 0x78dfb7 : touch ? 0xefb7c9 : 0x8399cf;
            pixels[y * 400 + x] = color;
        }
    if (hardware) {
        glBindFramebuffer(GL_FRAMEBUFFER, hw.get_current_framebuffer());
        glDisable(GL_SCISSOR_TEST);
        glClearColor(.16, .33, .29, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(40, 0, 320, 240);
        glClearColor(pressed ? .47f : .51f, pressed ? .87f : .60f, pressed ? .72f : .81f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glScissor(frame % 320, 300, 60, 120);
        glClearColor(.47, .87, .72, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        video(RETRO_HW_FRAME_BUFFER_VALID, 400, 480, 0);
    } else
        video(pixels.data(), 400, 480, 400 * 4);
    int16_t samples[1600];
    for (int i = 0; i < 800; i++) {
        auto value = int16_t(std::sin((frame * 800 + i) * 440. * 6.283185307 / 48000.) * 1000);
        samples[2 * i] = samples[2 * i + 1] = value;
    }
    audio(samples, 800);
    frame++;
}
RETRO_API void retro_reset() { frame = 0; }
RETRO_API size_t retro_serialize_size() { return sizeof(frame); }
RETRO_API bool retro_serialize(void* data, size_t size) {
    if (size != sizeof(frame)) return false;
    memcpy(data, &frame, size);
    return true;
}
RETRO_API bool retro_unserialize(const void* data, size_t size) {
    if (size != sizeof(frame)) return false;
    memcpy(&frame, data, size);
    return true;
}
// In-game save memory that holds the last frame number.
RETRO_API void* retro_get_memory_data(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM ? &frame : nullptr;
}
RETRO_API size_t retro_get_memory_size(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM ? sizeof(frame) : 0;
}
}
