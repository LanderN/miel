#pragma once

#define VK_USE_PLATFORM_ANDROID_KHR
#include <android/native_window.h>

#include <mutex>
#include <vector>

#include "libretro_vulkan.h"

// Worker-owned Vulkan presentation for a libretro core using the same graphics queue.
class VulkanVideo {
public:
    void setup(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);
    void window(int slot, ANativeWindow* window);
    bool visible(int slot) const {
        return outputs[slot].window && outputs[slot].swapchain != VK_NULL_HANDLE;
    }
    unsigned present(unsigned width, unsigned height, bool stacked, float lowerInset,
                     int64_t presentAt);
    void wait();
    void idle();
    void cleanup();
    retro_hw_render_interface_vulkan interface{};

private:
    struct Output {
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        VkExtent2D extent{};
        VkFormat format = VK_FORMAT_UNDEFINED;
        VkSurfaceTransformFlagBitsKHR transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        VkSemaphore acquired = VK_NULL_HANDLE;
        std::vector<VkImage> images;
        std::vector<VkSemaphore> complete;
        ANativeWindow* window = nullptr;  // Owned by the host.
    } outputs[2];
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool submitted = false;
    bool negotiated = false;
    bool displayTiming = false;
    const retro_hw_render_context_negotiation_interface_vulkan* negotiation = nullptr;
    const retro_vulkan_image* image = nullptr;
    std::vector<VkSemaphore> waits;
    VkSemaphore signal = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> coreCommands;
    std::mutex queueMutex;

    void destroyOutput(Output& output);
    void createSwapchain(Output& output);
    static void setImage(void*, const retro_vulkan_image*, uint32_t, const VkSemaphore*, uint32_t);
};
