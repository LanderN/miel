#include "vulkan.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>

namespace {
void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS)
        throw std::runtime_error(std::string(operation) + " (Vulkan " + std::to_string(result) +
                                 ")");
}

void barrier(VkCommandBuffer command, VkImage image, VkImageLayout from, VkImageLayout to,
             VkAccessFlags source, VkAccessFlags destination) {
    VkImageMemoryBarrier transition{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    transition.srcAccessMask = source;
    transition.dstAccessMask = destination;
    transition.oldLayout = from;
    transition.newLayout = to;
    transition.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    transition.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    transition.image = image;
    transition.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &transition);
}
}  // namespace

void VulkanVideo::setup(const retro_hw_render_context_negotiation_interface_vulkan* requested) {
    negotiation = requested;
    if (!negotiation || !negotiation->create_device)
        throw std::runtime_error("Core did not provide Vulkan device negotiation");
    VkApplicationInfo fallback{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    fallback.pApplicationName = "Miel";
    fallback.apiVersion = VK_API_VERSION_1_1;
    const auto* app =
        negotiation->get_application_info ? negotiation->get_application_info() : nullptr;
    const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    create.pApplicationInfo = app ? app : &fallback;
    create.enabledExtensionCount = 2;
    create.ppEnabledExtensionNames = extensions;
    check(vkCreateInstance(&create, nullptr, &interface.instance), "Create Vulkan instance");
    uint32_t count = 0;
    check(vkEnumeratePhysicalDevices(interface.instance, &count, nullptr), "Enumerate Vulkan GPUs");
    if (!count) throw std::runtime_error("No Vulkan GPU available");
    std::vector<VkPhysicalDevice> devices(count);
    check(vkEnumeratePhysicalDevices(interface.instance, &count, devices.data()),
          "Read Vulkan GPUs");
    retro_vulkan_context context{};
    const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    if (!negotiation->create_device(&context, interface.instance, devices.front(), VK_NULL_HANDLE,
                                    vkGetInstanceProcAddr, deviceExtensions, 1, nullptr, 0,
                                    nullptr))
        throw std::runtime_error("Core could not create Vulkan device");
    negotiated = true;
    interface.gpu = context.gpu;
    interface.device = context.device;
    interface.queue = context.queue;
    interface.queue_index = context.queue_family_index;
    interface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    interface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    interface.handle = this;
    interface.get_device_proc_addr = vkGetDeviceProcAddr;
    interface.get_instance_proc_addr = vkGetInstanceProcAddr;
    interface.set_image = setImage;
    // Azahar shares one output image between its frame slots; one sync index owns that image.
    interface.get_sync_index = [](void*) -> uint32_t { return 0; };
    interface.get_sync_index_mask = [](void*) -> uint32_t { return 1; };
    interface.wait_sync_index = [](void* handle) { static_cast<VulkanVideo*>(handle)->wait(); };
    interface.lock_queue = [](void* handle) {
        static_cast<VulkanVideo*>(handle)->queueMutex.lock();
    };
    interface.unlock_queue = [](void* handle) {
        static_cast<VulkanVideo*>(handle)->queueMutex.unlock();
    };
    interface.set_command_buffers = [](void* handle, uint32_t count,
                                       const VkCommandBuffer* commands) {
        auto& buffers = static_cast<VulkanVideo*>(handle)->coreCommands;
        buffers.clear();
        if (count) buffers.assign(commands, commands + count);
    };
    interface.set_signal_semaphore = [](void* handle, VkSemaphore semaphore) {
        static_cast<VulkanVideo*>(handle)->signal = semaphore;
    };
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = interface.queue_index;
    check(vkCreateCommandPool(interface.device, &poolInfo, nullptr, &pool),
          "Create Vulkan command pool");
    VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocate.commandPool = pool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    check(vkAllocateCommandBuffers(interface.device, &allocate, &command),
          "Allocate Vulkan command buffer");
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    check(vkCreateFence(interface.device, &fenceInfo, nullptr, &fence),
          "Create Vulkan frame fence");
}

void VulkanVideo::setImage(void* handle, const retro_vulkan_image* next, uint32_t count,
                           const VkSemaphore* semaphores, uint32_t family) {
    auto& video = *static_cast<VulkanVideo*>(handle);
    if (family != VK_QUEUE_FAMILY_IGNORED && family != video.interface.queue_index)
        throw std::runtime_error("Vulkan core output uses another queue family");
    video.image = next;
    video.waits.clear();
    if (count) video.waits.assign(semaphores, semaphores + count);
}

void VulkanVideo::wait() {
    if (!submitted) return;
    check(vkWaitForFences(interface.device, 1, &fence, VK_TRUE, UINT64_MAX),
          "Wait for Vulkan frame");
    submitted = false;
}

// Teardown continues even when a lost device cannot signal the frame fence.
void VulkanVideo::idle() {
    if (!interface.device) return;
    std::lock_guard lock(queueMutex);
    vkDeviceWaitIdle(interface.device);
}

void VulkanVideo::destroyOutput(Output& output) {
    if (output.acquired) vkDestroySemaphore(interface.device, output.acquired, nullptr);
    for (auto semaphore : output.complete) vkDestroySemaphore(interface.device, semaphore, nullptr);
    if (output.swapchain) vkDestroySwapchainKHR(interface.device, output.swapchain, nullptr);
    if (output.surface) vkDestroySurfaceKHR(interface.instance, output.surface, nullptr);
    output = {};
}

void VulkanVideo::window(int slot, ANativeWindow* next) {
    auto& output = outputs[slot];
    if (output.window == next && output.swapchain) return;
    if (interface.device) {
        std::lock_guard lock(queueMutex);
        check(vkDeviceWaitIdle(interface.device), "Wait for Vulkan surface replacement");
    }
    destroyOutput(output);
    output.window = next;
    if (!next) return;
    VkAndroidSurfaceCreateInfoKHR surface{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    surface.window = next;
    check(vkCreateAndroidSurfaceKHR(interface.instance, &surface, nullptr, &output.surface),
          "Create Vulkan Android surface");
    createSwapchain(output);
}

void VulkanVideo::createSwapchain(Output& output) {
    VkBool32 supported = false;
    check(vkGetPhysicalDeviceSurfaceSupportKHR(interface.gpu, interface.queue_index, output.surface,
                                               &supported),
          "Check Vulkan presentation queue");
    if (!supported) throw std::runtime_error("Vulkan queue cannot present to this display");
    VkSurfaceCapabilitiesKHR capabilities{};
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(interface.gpu, output.surface, &capabilities),
          "Read Vulkan surface capabilities");
    if (!(capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT))
        throw std::runtime_error("Vulkan display does not support image transfers");
    uint32_t count = 0;
    check(vkGetPhysicalDeviceSurfaceFormatsKHR(interface.gpu, output.surface, &count, nullptr),
          "Read Vulkan surface formats");
    std::vector<VkSurfaceFormatKHR> formats(count);
    check(
        vkGetPhysicalDeviceSurfaceFormatsKHR(interface.gpu, output.surface, &count, formats.data()),
        "Read Vulkan surface formats");
    auto selected = std::find_if(formats.begin(), formats.end(), [](auto format) {
        return format.format == VK_FORMAT_R8G8B8A8_UNORM ||
               format.format == VK_FORMAT_B8G8R8A8_UNORM;
    });
    if (selected == formats.end()) throw std::runtime_error("No Vulkan RGBA display format");
    VkFormatProperties properties{};
    vkGetPhysicalDeviceFormatProperties(interface.gpu, selected->format, &properties);
    if (!(properties.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT))
        throw std::runtime_error("Vulkan display format cannot receive image blits");
    output.format = selected->format;
    output.extent = capabilities.currentExtent;
    if (output.extent.width == UINT32_MAX) {
        output.extent.width =
            std::clamp(uint32_t(ANativeWindow_getWidth(output.window)),
                       capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
        output.extent.height =
            std::clamp(uint32_t(ANativeWindow_getHeight(output.window)),
                       capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    }
    output.transform = capabilities.currentTransform;
    // Let Android's compositor apply rotation to the untransformed buffer.
    if (capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        output.transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    if (output.transform != VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        throw std::runtime_error("Vulkan display requires an unsupported surface transform");
    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount) imageCount = std::min(imageCount, capabilities.maxImageCount);
    VkSwapchainCreateInfoKHR create{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    create.surface = output.surface;
    create.minImageCount = imageCount;
    create.imageFormat = selected->format;
    create.imageColorSpace = selected->colorSpace;
    create.imageExtent = output.extent;
    create.imageArrayLayers = 1;
    create.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    create.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    create.preTransform = output.transform;
    create.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (!(capabilities.supportedCompositeAlpha & create.compositeAlpha))
        create.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    create.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t modeCount = 0;
    check(vkGetPhysicalDeviceSurfacePresentModesKHR(interface.gpu, output.surface, &modeCount,
                                                    nullptr),
          "Read Vulkan present modes");
    std::vector<VkPresentModeKHR> modes(modeCount);
    check(vkGetPhysicalDeviceSurfacePresentModesKHR(interface.gpu, output.surface, &modeCount,
                                                    modes.data()),
          "Read Vulkan present modes");
    for (auto mode : {VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR}) {
        if (std::find(modes.begin(), modes.end(), mode) != modes.end()) {
            create.presentMode = mode;
            break;
        }
    }
    create.clipped = true;
    check(vkCreateSwapchainKHR(interface.device, &create, nullptr, &output.swapchain),
          "Create Vulkan swapchain");
    check(vkGetSwapchainImagesKHR(interface.device, output.swapchain, &count, nullptr),
          "Count Vulkan swapchain images");
    output.images.resize(count);
    check(vkGetSwapchainImagesKHR(interface.device, output.swapchain, &count, output.images.data()),
          "Read Vulkan swapchain images");
    VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    check(vkCreateSemaphore(interface.device, &semaphore, nullptr, &output.acquired),
          "Create Vulkan acquire semaphore");
    output.complete.resize(count);
    for (auto& complete : output.complete)
        check(vkCreateSemaphore(interface.device, &semaphore, nullptr, &complete),
              "Create Vulkan present semaphore");
}

unsigned VulkanVideo::present(unsigned width, unsigned height, bool stacked, float lowerInset) {
    if (!image) return 0;
    wait();
    VkFormatProperties properties{};
    vkGetPhysicalDeviceFormatProperties(interface.gpu, image->create_info.format, &properties);
    if (!(properties.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT))
        throw std::runtime_error("Vulkan core output cannot be blitted");
    check(vkResetCommandBuffer(command, 0), "Reset Vulkan frame commands");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(vkBeginCommandBuffer(command, &begin), "Begin Vulkan frame commands");
    const auto source = image->create_info.image;
    barrier(command, source, image->image_layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    std::vector<VkSemaphore> acquire = coreCommands.empty() ? waits : std::vector<VkSemaphore>{};
    std::vector<VkSemaphore> signals;
    std::vector<VkSwapchainKHR> swapchains;
    std::vector<uint32_t> indices;
    unsigned presented = 0;
    for (int slot = 0; slot < 2; slot++) {
        auto& output = outputs[slot];
        if (!output.swapchain || (!stacked && slot == 0)) continue;
        uint32_t index = 0;
        VkResult result = vkAcquireNextImageKHR(interface.device, output.swapchain, UINT64_MAX,
                                                output.acquired, VK_NULL_HANDLE, &index);
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_ERROR_SURFACE_LOST_KHR) {
            auto* next = output.window;
            output.window = nullptr;
            window(slot, next);
            continue;
        }
        if (result != VK_SUBOPTIMAL_KHR) check(result, "Acquire Vulkan display image");
        acquire.push_back(output.acquired);
        signals.push_back(output.complete[index]);
        swapchains.push_back(output.swapchain);
        indices.push_back(index);
        auto destination = output.images[index];
        barrier(command, destination, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
        VkClearColorValue black{{0, 0, 0, 1}};
        VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdClearColorImage(command, destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1,
                             &range);
        barrier(command, destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT);
        int inset = slot == 0 && stacked ? std::lround(width * lowerInset) : 0;
        int x0 = inset, x1 = int(width) - inset;
        int y0 = stacked && slot == 0 ? int(height / 2) : 0;
        int y1 = stacked && slot == 1 ? int(height / 2) : int(height);
        float scale = std::min(float(output.extent.width) / (x1 - x0),
                               float(output.extent.height) / (y1 - y0));
        int dw = std::lround((x1 - x0) * scale), dh = std::lround((y1 - y0) * scale);
        int dx = (int(output.extent.width) - dw) / 2, dy = (int(output.extent.height) - dh) / 2;
        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,
                               image->create_info.subresourceRange.baseMipLevel,
                               image->create_info.subresourceRange.baseArrayLayer, 1};
        blit.srcOffsets[0] = {x0, y0, 0};
        blit.srcOffsets[1] = {x1, y1, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstOffsets[0] = {dx, dy, 0};
        blit.dstOffsets[1] = {dx + dw, dy + dh, 1};
        vkCmdBlitImage(command, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination,
                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
        barrier(command, destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        presented |= 1u << slot;
    }
    barrier(command, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image->image_layout,
            VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    check(vkEndCommandBuffer(command), "End Vulkan frame commands");
    std::vector<VkPipelineStageFlags> stages(acquire.size(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    if (signal) signals.push_back(signal);
    coreCommands.push_back(command);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = acquire.size();
    submit.pWaitSemaphores = acquire.data();
    submit.pWaitDstStageMask = stages.data();
    submit.commandBufferCount = coreCommands.size();
    submit.pCommandBuffers = coreCommands.data();
    submit.signalSemaphoreCount = signals.size();
    submit.pSignalSemaphores = signals.data();
    check(vkResetFences(interface.device, 1, &fence), "Reset Vulkan frame fence");
    std::lock_guard lock(queueMutex);
    check(vkQueueSubmit(interface.queue, 1, &submit, fence), "Submit Vulkan frame");
    submitted = true;
    if (!swapchains.empty()) {
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount = swapchains.size();
        present.pWaitSemaphores = signals.data();
        present.swapchainCount = swapchains.size();
        present.pSwapchains = swapchains.data();
        present.pImageIndices = indices.data();
        std::vector<VkResult> results(swapchains.size());
        present.pResults = results.data();
        VkResult result = vkQueuePresentKHR(interface.queue, &present);
        if (result != VK_SUBOPTIMAL_KHR && result != VK_ERROR_OUT_OF_DATE_KHR &&
            result != VK_ERROR_SURFACE_LOST_KHR)
            check(result, "Present Vulkan displays");
        size_t at = 0;
        for (int slot = 0; slot < 2; slot++) {
            if (!(presented & (1u << slot))) continue;
            if (results[at] == VK_ERROR_OUT_OF_DATE_KHR || results[at] == VK_ERROR_SURFACE_LOST_KHR)
                outputs[slot].window = nullptr;
            at++;
        }
    }
    waits.clear();
    coreCommands.clear();
    signal = VK_NULL_HANDLE;
    return presented;
}

void VulkanVideo::cleanup() {
    if (interface.device) {
        std::lock_guard lock(queueMutex);
        vkDeviceWaitIdle(interface.device);
        for (auto& output : outputs) destroyOutput(output);
        if (fence) vkDestroyFence(interface.device, fence, nullptr);
        if (pool) vkDestroyCommandPool(interface.device, pool, nullptr);
        if (negotiated && negotiation->destroy_device) negotiation->destroy_device();
        vkDestroyDevice(interface.device, nullptr);
    }
    if (interface.instance) vkDestroyInstance(interface.instance, nullptr);
    interface = {};
    pool = VK_NULL_HANDLE;
    command = VK_NULL_HANDLE;
    fence = VK_NULL_HANDLE;
    submitted = negotiated = false;
    negotiation = nullptr;
    image = nullptr;
    waits.clear();
    coreCommands.clear();
    signal = VK_NULL_HANDLE;
}
