// Elite Tuner X - Vulkan driver probe + transfer-bandwidth benchmark.
// Works with the system Vulkan driver (libvulkan.so) or with a custom driver
// (e.g. Mesa Turnip) loaded in-process through libadrenotools.
#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <vector>

#define VK_NO_PROTOTYPES 1
#include <vulkan/vulkan.h>

#ifdef ETX_ADRENOTOOLS
#include <adrenotools/driver.h>
#endif

#include "shaders_spv.h"

namespace {

std::string esc(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof b, "\\u%04x", c);
                    o += b;
                } else {
                    o += static_cast<char>(c);
                }
        }
    }
    return o;
}

std::string q(const std::string &s) { return "\"" + esc(s) + "\""; }

std::string ver(uint32_t v) {
    return std::to_string(VK_API_VERSION_MAJOR(v)) + "." + std::to_string(VK_API_VERSION_MINOR(v)) + "." +
           std::to_string(VK_API_VERSION_PATCH(v));
}

std::string err(const std::string &m) { return "{\"ok\":false,\"error\":" + q(m) + "}"; }

std::string num(double d) {
    char b[64];
    snprintf(b, sizeof b, "%.4f", d);
    return b;
}

struct Loader {
    void *handle = nullptr;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    std::string error;

    bool open(const char *dir, const char *lib, const char *hookDir) {
        if (dir == nullptr || *dir == 0) {
            handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
            if (!handle) {
                const char *e = dlerror();
                error = std::string("dlopen libvulkan.so failed: ") + (e ? e : "?");
                return false;
            }
        } else {
#ifdef ETX_ADRENOTOOLS
            handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hookDir, dir, lib,
                                                nullptr, nullptr);
            if (!handle) {
                const char *e = dlerror();
                error = std::string("adrenotools could not load custom driver: ") + (e ? e : "unknown error");
                return false;
            }
#else
            (void) lib;
            (void) hookDir;
            error = "custom driver loading (libadrenotools) is not compiled in";
            return false;
#endif
        }
        gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(handle, "vkGetInstanceProcAddr"));
        if (!gipa) {
            error = "vkGetInstanceProcAddr not exported by driver";
            return false;
        }
        return true;
    }
};

struct Instance {
    VkInstance inst = VK_NULL_HANDLE;
    uint32_t apiVersion = VK_API_VERSION_1_0;
    PFN_vkDestroyInstance destroy = nullptr;

    bool create(PFN_vkGetInstanceProcAddr gipa, std::string &error) {
        auto enumVer = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
        if (enumVer) enumVer(&apiVersion);
        auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(gipa(VK_NULL_HANDLE, "vkCreateInstance"));
        if (!createInstance) {
            error = "vkCreateInstance missing";
            return false;
        }
        std::vector<const char *> exts;
        if (apiVersion < VK_API_VERSION_1_1) {
            auto enumExt = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
                gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
            uint32_t n = 0;
            if (enumExt && enumExt(nullptr, &n, nullptr) == VK_SUCCESS) {
                std::vector<VkExtensionProperties> v(n);
                enumExt(nullptr, &n, v.data());
                for (auto &e : v)
                    if (!strcmp(e.extensionName, VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME))
                        exts.push_back(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
            }
        }
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "EliteTunerX";
        app.pEngineName = "EliteTunerX";
        app.apiVersion = apiVersion;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ci.pApplicationInfo = &app;
        ci.enabledExtensionCount = static_cast<uint32_t>(exts.size());
        ci.ppEnabledExtensionNames = exts.empty() ? nullptr : exts.data();
        VkResult r = createInstance(&ci, nullptr, &inst);
        if (r != VK_SUCCESS) {
            error = "vkCreateInstance failed: VkResult " + std::to_string(static_cast<int>(r));
            inst = VK_NULL_HANDLE;
            return false;
        }
        destroy = reinterpret_cast<PFN_vkDestroyInstance>(gipa(inst, "vkDestroyInstance"));
        return true;
    }

    ~Instance() {
        if (inst != VK_NULL_HANDLE && destroy) destroy(inst, nullptr);
    }
};

const char *devType(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
        default: return "other";
    }
}

void linkTo(void **&tail, void *s) {
    *tail = s;
    tail = reinterpret_cast<void **>(&reinterpret_cast<VkBaseOutStructure *>(s)->pNext);
}

std::string probeImpl(const char *dir, const char *lib, const char *hook) {
    Loader L;
    if (!L.open(dir, lib, hook)) return err(L.error);
    Instance I;
    std::string e;
    if (!I.create(L.gipa, e)) return err(e);
    PFN_vkGetInstanceProcAddr gipa = L.gipa;
    VkInstance inst = I.inst;

#define LI(name) auto name = reinterpret_cast<PFN_##name>(gipa(inst, #name))
    LI(vkEnumeratePhysicalDevices);
    LI(vkGetPhysicalDeviceProperties);
    LI(vkEnumerateDeviceExtensionProperties);
    LI(vkGetPhysicalDeviceFeatures);
    LI(vkGetPhysicalDeviceMemoryProperties);
    if (!vkEnumeratePhysicalDevices || !vkGetPhysicalDeviceProperties || !vkEnumerateDeviceExtensionProperties ||
        !vkGetPhysicalDeviceFeatures || !vkGetPhysicalDeviceMemoryProperties)
        return err("driver is missing core Vulkan 1.0 entry points");
    auto getFeat2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(gipa(inst, "vkGetPhysicalDeviceFeatures2"));
    if (!getFeat2) getFeat2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(gipa(inst, "vkGetPhysicalDeviceFeatures2KHR"));
    auto getProp2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(gipa(inst, "vkGetPhysicalDeviceProperties2"));
    if (!getProp2) getProp2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(gipa(inst, "vkGetPhysicalDeviceProperties2KHR"));

    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    std::vector<VkPhysicalDevice> pds(n);
    if (n) vkEnumeratePhysicalDevices(inst, &n, pds.data());

    std::string out = "{\"ok\":true,\"instanceVersion\":" + q(ver(I.apiVersion)) + ",\"devices\":[";
    for (uint32_t di = 0; di < n; ++di) {
        VkPhysicalDevice pd = pds[di];
        VkPhysicalDeviceProperties p{};
        vkGetPhysicalDeviceProperties(pd, &p);
        uint32_t effApi = std::min(p.apiVersion, I.apiVersion);

        uint32_t ne = 0;
        vkEnumerateDeviceExtensionProperties(pd, nullptr, &ne, nullptr);
        std::vector<VkExtensionProperties> ev(ne);
        if (ne) vkEnumerateDeviceExtensionProperties(pd, nullptr, &ne, ev.data());
        std::set<std::string> exts;
        for (auto &x : ev) exts.insert(x.extensionName);
        auto has = [&](const char *name) { return exts.count(name) > 0; };

        // ---------- features ----------
        VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
        VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
        VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDeviceRobustness2FeaturesEXT rb2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT};
        VkPhysicalDeviceTransformFeedbackFeaturesEXT xfb{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TRANSFORM_FEEDBACK_FEATURES_EXT};
        VkPhysicalDeviceCustomBorderColorFeaturesEXT cbc{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_CUSTOM_BORDER_COLOR_FEATURES_EXT};
        VkPhysicalDeviceDepthClipEnableFeaturesEXT dce{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT};
#ifdef VK_EXT_mutable_descriptor_type
        VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT mdt{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MUTABLE_DESCRIPTOR_TYPE_FEATURES_EXT};
#endif
#ifdef VK_EXT_graphics_pipeline_library
        VkPhysicalDeviceGraphicsPipelineLibraryFeaturesEXT gpl{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GRAPHICS_PIPELINE_LIBRARY_FEATURES_EXT};
#endif
#ifdef VK_KHR_maintenance5
        VkPhysicalDeviceMaintenance5FeaturesKHR m5{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES_KHR};
#endif
        bool l11 = false, l13 = false, lrb2 = false, lxfb = false, lcbc = false, ldce = false;
        bool lmdt = false, lgpl = false, lm5 = false;
        if (getFeat2) {
            void **tail = &f2.pNext;
            if (effApi >= VK_API_VERSION_1_2) { linkTo(tail, &f11); linkTo(tail, &f12); l11 = true; }
            if (effApi >= VK_API_VERSION_1_3) { linkTo(tail, &f13); l13 = true; }
            if (has(VK_EXT_ROBUSTNESS_2_EXTENSION_NAME)) { linkTo(tail, &rb2); lrb2 = true; }
            if (has(VK_EXT_TRANSFORM_FEEDBACK_EXTENSION_NAME)) { linkTo(tail, &xfb); lxfb = true; }
            if (has(VK_EXT_CUSTOM_BORDER_COLOR_EXTENSION_NAME)) { linkTo(tail, &cbc); lcbc = true; }
            if (has(VK_EXT_DEPTH_CLIP_ENABLE_EXTENSION_NAME)) { linkTo(tail, &dce); ldce = true; }
#ifdef VK_EXT_mutable_descriptor_type
            if (has("VK_EXT_mutable_descriptor_type") || has("VK_VALVE_mutable_descriptor_type")) { linkTo(tail, &mdt); lmdt = true; }
#endif
#ifdef VK_EXT_graphics_pipeline_library
            if (has("VK_EXT_graphics_pipeline_library")) { linkTo(tail, &gpl); lgpl = true; }
#endif
#ifdef VK_KHR_maintenance5
            if (has("VK_KHR_maintenance5")) { linkTo(tail, &m5); lm5 = true; }
#endif
            getFeat2(pd, &f2);
        } else {
            vkGetPhysicalDeviceFeatures(pd, &f2.features);
        }

        std::string fj;
        bool firstF = true;
        auto F = [&](const char *k, VkBool32 v) {
            if (!firstF) fj += ",";
            firstF = false;
            fj += q(k) + ":" + (v ? "true" : "false");
        };
#define CF(x) F("core." #x, f2.features.x)
        CF(robustBufferAccess); CF(fullDrawIndexUint32); CF(imageCubeArray); CF(independentBlend);
        CF(geometryShader); CF(tessellationShader); CF(sampleRateShading); CF(dualSrcBlend); CF(logicOp);
        CF(multiDrawIndirect); CF(drawIndirectFirstInstance); CF(depthClamp); CF(depthBiasClamp);
        CF(fillModeNonSolid); CF(depthBounds); CF(wideLines); CF(largePoints); CF(multiViewport);
        CF(samplerAnisotropy); CF(textureCompressionETC2); CF(textureCompressionASTC_LDR); CF(textureCompressionBC);
        CF(occlusionQueryPrecise); CF(pipelineStatisticsQuery); CF(vertexPipelineStoresAndAtomics);
        CF(fragmentStoresAndAtomics); CF(shaderImageGatherExtended); CF(shaderStorageImageExtendedFormats);
        CF(shaderStorageImageReadWithoutFormat); CF(shaderStorageImageWriteWithoutFormat);
        CF(shaderClipDistance); CF(shaderCullDistance); CF(shaderFloat64); CF(shaderInt64); CF(shaderInt16);
        CF(variableMultisampleRate); CF(sparseBinding);
#undef CF
        if (l11) {
#define F11(x) F("v11." #x, f11.x)
            F11(multiview); F11(storageBuffer16BitAccess); F11(shaderDrawParameters); F11(samplerYcbcrConversion);
            F11(variablePointers);
#undef F11
#define F12(x) F("v12." #x, f12.x)
            F12(samplerMirrorClampToEdge); F12(drawIndirectCount); F12(storageBuffer8BitAccess); F12(shaderFloat16);
            F12(shaderInt8); F12(descriptorIndexing); F12(shaderSampledImageArrayNonUniformIndexing);
            F12(shaderStorageBufferArrayNonUniformIndexing); F12(shaderStorageImageArrayNonUniformIndexing);
            F12(shaderUniformBufferArrayNonUniformIndexing);
            F12(descriptorBindingSampledImageUpdateAfterBind); F12(descriptorBindingStorageImageUpdateAfterBind);
            F12(descriptorBindingStorageBufferUpdateAfterBind); F12(descriptorBindingUniformBufferUpdateAfterBind);
            F12(descriptorBindingUniformTexelBufferUpdateAfterBind); F12(descriptorBindingStorageTexelBufferUpdateAfterBind);
            F12(descriptorBindingUpdateUnusedWhilePending); F12(descriptorBindingPartiallyBound);
            F12(descriptorBindingVariableDescriptorCount); F12(runtimeDescriptorArray); F12(samplerFilterMinmax);
            F12(scalarBlockLayout); F12(imagelessFramebuffer); F12(uniformBufferStandardLayout);
            F12(shaderSubgroupExtendedTypes); F12(separateDepthStencilLayouts); F12(hostQueryReset);
            F12(timelineSemaphore); F12(bufferDeviceAddress); F12(vulkanMemoryModel);
            F12(vulkanMemoryModelDeviceScope); F12(shaderOutputViewportIndex); F12(shaderOutputLayer);
            F12(subgroupBroadcastDynamicId);
#undef F12
        }
        if (l13) {
#define F13(x) F("v13." #x, f13.x)
            F13(robustImageAccess); F13(inlineUniformBlock); F13(pipelineCreationCacheControl); F13(privateData);
            F13(shaderDemoteToHelperInvocation); F13(shaderTerminateInvocation); F13(subgroupSizeControl);
            F13(computeFullSubgroups); F13(synchronization2); F13(textureCompressionASTC_HDR);
            F13(shaderZeroInitializeWorkgroupMemory); F13(dynamicRendering); F13(shaderIntegerDotProduct);
            F13(maintenance4);
#undef F13
        }
        if (lrb2) { F("rb2.robustBufferAccess2", rb2.robustBufferAccess2); F("rb2.robustImageAccess2", rb2.robustImageAccess2); F("rb2.nullDescriptor", rb2.nullDescriptor); }
        if (lxfb) { F("xfb.transformFeedback", xfb.transformFeedback); F("xfb.geometryStreams", xfb.geometryStreams); }
        if (lcbc) { F("cbc.customBorderColors", cbc.customBorderColors); F("cbc.customBorderColorWithoutFormat", cbc.customBorderColorWithoutFormat); }
        if (ldce) F("dce.depthClipEnable", dce.depthClipEnable);
#ifdef VK_EXT_mutable_descriptor_type
        if (lmdt) F("mdt.mutableDescriptorType", mdt.mutableDescriptorType);
#endif
#ifdef VK_EXT_graphics_pipeline_library
        if (lgpl) F("gpl.graphicsPipelineLibrary", gpl.graphicsPipelineLibrary);
#endif
#ifdef VK_KHR_maintenance5
        if (lm5) F("m5.maintenance5", m5.maintenance5);
#endif
        (void) lmdt; (void) lgpl; (void) lm5;

        // ---------- driver properties ----------
        std::string driverName, driverInfo;
        int driverId = 0;
        std::string conformance;
        if (getProp2 && (effApi >= VK_API_VERSION_1_2 || has(VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME))) {
            VkPhysicalDeviceDriverProperties dp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
            VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
            p2.pNext = &dp;
            getProp2(pd, &p2);
            driverName = dp.driverName;
            driverInfo = dp.driverInfo;
            driverId = static_cast<int>(dp.driverID);
            conformance = std::to_string(dp.conformanceVersion.major) + "." + std::to_string(dp.conformanceVersion.minor) +
                          "." + std::to_string(dp.conformanceVersion.subminor) + "." + std::to_string(dp.conformanceVersion.patch);
        }

        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        std::string heaps;
        for (uint32_t h = 0; h < mp.memoryHeapCount; ++h) {
            if (h) heaps += ",";
            heaps += "{\"mb\":" + std::to_string(mp.memoryHeaps[h].size / (1024 * 1024)) + ",\"deviceLocal\":" +
                     ((mp.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) ? "true" : "false") + "}";
        }

        const VkPhysicalDeviceLimits &lim = p.limits;
        std::string lj = "{";
        lj += "\"maxImageDimension2D\":" + std::to_string(lim.maxImageDimension2D);
        lj += ",\"maxBoundDescriptorSets\":" + std::to_string(lim.maxBoundDescriptorSets);
        lj += ",\"maxPerStageDescriptorSamplers\":" + std::to_string(lim.maxPerStageDescriptorSamplers);
        lj += ",\"maxPerStageDescriptorUniformBuffers\":" + std::to_string(lim.maxPerStageDescriptorUniformBuffers);
        lj += ",\"maxPerStageDescriptorStorageBuffers\":" + std::to_string(lim.maxPerStageDescriptorStorageBuffers);
        lj += ",\"maxPerStageDescriptorSampledImages\":" + std::to_string(lim.maxPerStageDescriptorSampledImages);
        lj += ",\"maxPerStageDescriptorStorageImages\":" + std::to_string(lim.maxPerStageDescriptorStorageImages);
        lj += ",\"maxPushConstantsSize\":" + std::to_string(lim.maxPushConstantsSize);
        lj += ",\"maxComputeSharedMemorySize\":" + std::to_string(lim.maxComputeSharedMemorySize);
        lj += ",\"maxComputeWorkGroupInvocations\":" + std::to_string(lim.maxComputeWorkGroupInvocations);
        lj += ",\"maxViewports\":" + std::to_string(lim.maxViewports);
        lj += ",\"maxColorAttachments\":" + std::to_string(lim.maxColorAttachments);
        lj += ",\"maxSamplerAnisotropy\":" + num(lim.maxSamplerAnisotropy);
        lj += ",\"timestampPeriod\":" + num(lim.timestampPeriod);
        lj += "}";

        std::string ej;
        bool firstE = true;
        for (auto &x : exts) {
            if (!firstE) ej += ",";
            firstE = false;
            ej += q(x);
        }

        if (di) out += ",";
        out += "{\"name\":" + q(p.deviceName) + ",\"type\":" + q(devType(p.deviceType)) +
               ",\"apiVersion\":" + q(ver(p.apiVersion)) + ",\"effectiveApi\":" + q(ver(effApi)) +
               ",\"apiMinor\":" + std::to_string(VK_API_VERSION_MINOR(effApi)) +
               ",\"driverVersionRaw\":" + std::to_string(p.driverVersion) +
               ",\"driverVersion\":" + q(ver(p.driverVersion)) + ",\"vendorId\":" + std::to_string(p.vendorID) +
               ",\"deviceId\":" + std::to_string(p.deviceID) + ",\"driverId\":" + std::to_string(driverId) +
               ",\"driverName\":" + q(driverName) + ",\"driverInfo\":" + q(driverInfo) +
               ",\"conformance\":" + q(conformance) + ",\"heaps\":[" + heaps + "],\"limits\":" + lj +
               ",\"features\":{" + fj + "},\"extensions\":[" + ej + "]}";
    }
    out += "]}";
#undef LI
    return out;
}

std::string benchImpl(const char *dir, const char *lib, const char *hook, int sizeMb, int iters) {
    Loader L;
    if (!L.open(dir, lib, hook)) return err(L.error);
    Instance I;
    std::string e;
    if (!I.create(L.gipa, e)) return err(e);
    PFN_vkGetInstanceProcAddr gipa = L.gipa;
    VkInstance inst = I.inst;

#define LI(name) auto name = reinterpret_cast<PFN_##name>(gipa(inst, #name))
    LI(vkEnumeratePhysicalDevices);
    LI(vkGetPhysicalDeviceProperties);
    LI(vkGetPhysicalDeviceMemoryProperties);
    LI(vkGetPhysicalDeviceQueueFamilyProperties);
    LI(vkCreateDevice);
    LI(vkGetDeviceProcAddr);
#undef LI
    if (!vkEnumeratePhysicalDevices || !vkCreateDevice || !vkGetDeviceProcAddr || !vkGetPhysicalDeviceQueueFamilyProperties)
        return err("driver is missing core entry points");

    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    if (n == 0) return err("no Vulkan physical device");
    std::vector<VkPhysicalDevice> pds(n);
    vkEnumeratePhysicalDevices(inst, &n, pds.data());
    VkPhysicalDevice pd = pds[0];
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(pd, &props);

    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, nullptr);
    std::vector<VkQueueFamilyProperties> qf(nq);
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf.data());
    int qfi = -1;
    for (uint32_t i = 0; i < nq; ++i)
        if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { qfi = static_cast<int>(i); break; }
    if (qfi < 0)
        for (uint32_t i = 0; i < nq; ++i)
            if (qf[i].queueFlags & (VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT)) { qfi = static_cast<int>(i); break; }
    if (qfi < 0) return err("no usable queue family");

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = static_cast<uint32_t>(qfi);
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    VkDevice dev = VK_NULL_HANDLE;
    VkResult r = vkCreateDevice(pd, &dci, nullptr, &dev);
    if (r != VK_SUCCESS) return err("vkCreateDevice failed: VkResult " + std::to_string(static_cast<int>(r)));

#define LD(name) auto name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(dev, #name))
    LD(vkDestroyDevice); LD(vkGetDeviceQueue); LD(vkCreateBuffer); LD(vkDestroyBuffer);
    LD(vkGetBufferMemoryRequirements); LD(vkAllocateMemory); LD(vkFreeMemory); LD(vkBindBufferMemory);
    LD(vkCreateCommandPool); LD(vkDestroyCommandPool); LD(vkAllocateCommandBuffers); LD(vkBeginCommandBuffer);
    LD(vkEndCommandBuffer); LD(vkResetCommandBuffer); LD(vkCmdCopyBuffer); LD(vkCmdFillBuffer);
    LD(vkCmdPipelineBarrier); LD(vkQueueSubmit); LD(vkCreateFence); LD(vkDestroyFence); LD(vkWaitForFences);
    LD(vkResetFences);
#undef LD

    VkQueue queue = VK_NULL_HANDLE;
    vkGetDeviceQueue(dev, static_cast<uint32_t>(qfi), 0, &queue);

    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);

    VkDeviceSize size = static_cast<VkDeviceSize>(std::max(16, sizeMb)) * 1024 * 1024;
    VkDeviceSize largestLocal = 0;
    for (uint32_t h = 0; h < mp.memoryHeapCount; ++h)
        if (mp.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)
            largestLocal = std::max(largestLocal, mp.memoryHeaps[h].size);
    if (largestLocal > 0) size = std::min(size, largestLocal / 8);
    size &= ~static_cast<VkDeviceSize>(0xFFFF);

    VkBuffer bufs[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory mems[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    std::string failure;
    for (int i = 0; i < 2 && failure.empty(); ++i) {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = size;
        bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(dev, &bci, nullptr, &bufs[i]) != VK_SUCCESS) { failure = "vkCreateBuffer failed"; break; }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(dev, bufs[i], &req);
        int type = -1;
        for (uint32_t t = 0; t < mp.memoryTypeCount; ++t)
            if ((req.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { type = static_cast<int>(t); break; }
        if (type < 0)
            for (uint32_t t = 0; t < mp.memoryTypeCount; ++t)
                if (req.memoryTypeBits & (1u << t)) { type = static_cast<int>(t); break; }
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(type);
        if (type < 0 || vkAllocateMemory(dev, &mai, nullptr, &mems[i]) != VK_SUCCESS) { failure = "vkAllocateMemory failed"; break; }
        vkBindBufferMemory(dev, bufs[i], mems[i], 0);
    }

    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    double copyMs = 0, fillMs = 0;
    if (failure.empty()) {
        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pci.queueFamilyIndex = static_cast<uint32_t>(qfi);
        vkCreateCommandPool(dev, &pci, nullptr, &pool);
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = pool;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = 1;
        vkAllocateCommandBuffers(dev, &cai, &cmd);
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        vkCreateFence(dev, &fci, nullptr, &fence);

        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;

        auto record = [&](bool copy, int count) {
            vkResetCommandBuffer(cmd, 0);
            VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vkBeginCommandBuffer(cmd, &bi);
            for (int i = 0; i < count; ++i) {
                if (copy) {
                    VkBufferCopy region{0, 0, size};
                    vkCmdCopyBuffer(cmd, bufs[0], bufs[1], 1, &region);
                } else {
                    vkCmdFillBuffer(cmd, bufs[1], 0, VK_WHOLE_SIZE, 0x5A5A5A5Au + static_cast<uint32_t>(i));
                }
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1,
                                     &barrier, 0, nullptr, 0, nullptr);
            }
            vkEndCommandBuffer(cmd);
        };
        auto submitWait = [&]() -> double {
            vkResetFences(dev, 1, &fence);
            VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cmd;
            auto t0 = std::chrono::steady_clock::now();
            if (vkQueueSubmit(queue, 1, &si, fence) != VK_SUCCESS) return -1;
            if (vkWaitForFences(dev, 1, &fence, VK_TRUE, 30ull * 1000 * 1000 * 1000) != VK_SUCCESS) return -1;
            auto t1 = std::chrono::steady_clock::now();
            return std::chrono::duration<double, std::milli>(t1 - t0).count();
        };

        // initialise source buffer
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(cmd, &bi);
        vkCmdFillBuffer(cmd, bufs[0], 0, VK_WHOLE_SIZE, 0x12345678u);
        vkEndCommandBuffer(cmd);
        if (submitWait() < 0) failure = "queue submit failed (device lost?)";

        if (failure.empty()) {
            record(true, iters);
            submitWait();  // warm-up / clock ramp
            double best = 1e30;
            for (int k = 0; k < 3; ++k) {
                record(true, iters);
                double t = submitWait();
                if (t < 0) { failure = "copy submit failed"; break; }
                best = std::min(best, t);
            }
            copyMs = best;
        }
        if (failure.empty()) {
            double best = 1e30;
            for (int k = 0; k < 3; ++k) {
                record(false, iters);
                double t = submitWait();
                if (t < 0) { failure = "fill submit failed"; break; }
                best = std::min(best, t);
            }
            fillMs = best;
        }
    }

    if (fence) vkDestroyFence(dev, fence, nullptr);
    if (pool) vkDestroyCommandPool(dev, pool, nullptr);
    for (int i = 0; i < 2; ++i) {
        if (bufs[i]) vkDestroyBuffer(dev, bufs[i], nullptr);
        if (mems[i]) vkFreeMemory(dev, mems[i], nullptr);
    }
    vkDestroyDevice(dev, nullptr);
    if (!failure.empty()) return err(failure);

    double bytes = static_cast<double>(size) * iters;
    double copyGBs = bytes * 2.0 / (copyMs / 1000.0) / 1e9;  // read + write traffic
    double fillGBs = bytes / (fillMs / 1000.0) / 1e9;
    return "{\"ok\":true,\"device\":" + q(props.deviceName) + ",\"driverVersion\":" + q(ver(props.driverVersion)) +
           ",\"bufferMb\":" + std::to_string(size / (1024 * 1024)) + ",\"iters\":" + std::to_string(iters) +
           ",\"copyMs\":" + num(copyMs) + ",\"fillMs\":" + num(fillMs) + ",\"copyGBs\":" + num(copyGBs) +
           ",\"fillGBs\":" + num(fillGBs) + "}";
}


// ---------------------------------------------------------------------------------------------
// Compute benchmark: FP32 FMA throughput (GFLOPS) and shader memory bandwidth (GB/s).
// Timed with GPU timestamp queries when the queue supports them, otherwise wall clock.
std::string computeImpl(const char *dir, const char *lib, const char *hook, int seconds) {
    Loader L;
    if (!L.open(dir, lib, hook)) return err(L.error);
    Instance I;
    std::string e;
    if (!I.create(L.gipa, e)) return err(e);
    PFN_vkGetInstanceProcAddr gipa = L.gipa;
    VkInstance inst = I.inst;
#define LI(name) auto name = reinterpret_cast<PFN_##name>(gipa(inst, #name))
    LI(vkEnumeratePhysicalDevices);
    LI(vkGetPhysicalDeviceProperties);
    LI(vkGetPhysicalDeviceMemoryProperties);
    LI(vkGetPhysicalDeviceQueueFamilyProperties);
    LI(vkCreateDevice);
    LI(vkGetDeviceProcAddr);
#undef LI
    if (!vkEnumeratePhysicalDevices || !vkCreateDevice || !vkGetDeviceProcAddr) return err("driver is missing core entry points");
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    if (n == 0) return err("no Vulkan physical device");
    std::vector<VkPhysicalDevice> pds(n);
    vkEnumeratePhysicalDevices(inst, &n, pds.data());
    VkPhysicalDevice pd = pds[0];
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(pd, &props);

    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, nullptr);
    std::vector<VkQueueFamilyProperties> qf(nq);
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf.data());
    int qfi = -1;
    for (uint32_t i = 0; i < nq; ++i)
        if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { qfi = static_cast<int>(i); break; }
    if (qfi < 0) return err("no compute queue");
    bool useTs = qf[qfi].timestampValidBits > 0 && props.limits.timestampPeriod > 0;

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = static_cast<uint32_t>(qfi);
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    VkDevice dev = VK_NULL_HANDLE;
    VkResult r = vkCreateDevice(pd, &dci, nullptr, &dev);
    if (r != VK_SUCCESS) return err("vkCreateDevice failed: VkResult " + std::to_string(static_cast<int>(r)));

#define LD(name) auto name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(dev, #name))
    LD(vkDestroyDevice); LD(vkGetDeviceQueue); LD(vkCreateBuffer); LD(vkDestroyBuffer);
    LD(vkGetBufferMemoryRequirements); LD(vkAllocateMemory); LD(vkFreeMemory); LD(vkBindBufferMemory);
    LD(vkCreateCommandPool); LD(vkDestroyCommandPool); LD(vkAllocateCommandBuffers); LD(vkBeginCommandBuffer);
    LD(vkEndCommandBuffer); LD(vkResetCommandBuffer); LD(vkCmdPipelineBarrier); LD(vkQueueSubmit);
    LD(vkCreateFence); LD(vkDestroyFence); LD(vkWaitForFences); LD(vkResetFences);
    LD(vkCreateShaderModule); LD(vkDestroyShaderModule); LD(vkCreateDescriptorSetLayout);
    LD(vkDestroyDescriptorSetLayout); LD(vkCreatePipelineLayout); LD(vkDestroyPipelineLayout);
    LD(vkCreateComputePipelines); LD(vkDestroyPipeline); LD(vkCreateDescriptorPool); LD(vkDestroyDescriptorPool);
    LD(vkAllocateDescriptorSets); LD(vkUpdateDescriptorSets); LD(vkCmdBindPipeline); LD(vkCmdBindDescriptorSets);
    LD(vkCmdPushConstants); LD(vkCmdDispatch); LD(vkCreateQueryPool); LD(vkDestroyQueryPool);
    LD(vkCmdResetQueryPool); LD(vkCmdWriteTimestamp); LD(vkGetQueryPoolResults); LD(vkCmdFillBuffer);
#undef LD

    VkQueue queue = VK_NULL_HANDLE;
    vkGetDeviceQueue(dev, static_cast<uint32_t>(qfi), 0, &queue);
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);

    const uint32_t groupSize = 256;
    const uint32_t copyGroups = 16384;                       // 4M invocations
    const VkDeviceSize copyBytes = VkDeviceSize(copyGroups) * groupSize * 16;  // 64 MB per buffer
    const uint32_t fmaGroups = 4096;                         // 1M invocations
    const uint32_t fmaIters = 256;
    const VkDeviceSize fmaBytes = VkDeviceSize(fmaGroups) * groupSize * 16;

    std::string failure;
    VkBuffer bufs[3] = {};
    VkDeviceMemory mems[3] = {};
    VkDeviceSize sizes[3] = {copyBytes, copyBytes, fmaBytes};
    for (int i = 0; i < 3 && failure.empty(); ++i) {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = sizes[i];
        bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(dev, &bci, nullptr, &bufs[i]) != VK_SUCCESS) { failure = "vkCreateBuffer failed"; break; }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(dev, bufs[i], &req);
        int type = -1;
        for (uint32_t t = 0; t < mp.memoryTypeCount; ++t)
            if ((req.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { type = static_cast<int>(t); break; }
        if (type < 0)
            for (uint32_t t = 0; t < mp.memoryTypeCount; ++t)
                if (req.memoryTypeBits & (1u << t)) { type = static_cast<int>(t); break; }
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(type < 0 ? 0 : type);
        if (type < 0 || vkAllocateMemory(dev, &mai, nullptr, &mems[i]) != VK_SUCCESS) { failure = "vkAllocateMemory failed"; break; }
        vkBindBufferMemory(dev, bufs[i], mems[i], 0);
    }

    VkShaderModule modFma = VK_NULL_HANDLE, modCopy = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkPipeline pipeFma = VK_NULL_HANDLE, pipeCopy = VK_NULL_HANDLE;
    VkDescriptorPool dpool = VK_NULL_HANDLE;
    VkDescriptorSet setFma = VK_NULL_HANDLE, setCopy = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool qp = VK_NULL_HANDLE;

    if (failure.empty()) {
        VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smi.codeSize = sizeof(kSpv_fma32);
        smi.pCode = kSpv_fma32;
        if (vkCreateShaderModule(dev, &smi, nullptr, &modFma) != VK_SUCCESS) failure = "shader module (fma) failed";
        smi.codeSize = sizeof(kSpv_copy);
        smi.pCode = kSpv_copy;
        if (failure.empty() && vkCreateShaderModule(dev, &smi, nullptr, &modCopy) != VK_SUCCESS) failure = "shader module (copy) failed";
    }
    if (failure.empty()) {
        VkDescriptorSetLayoutBinding b[2]{};
        for (uint32_t i = 0; i < 2; ++i) {
            b[i].binding = i;
            b[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            b[i].descriptorCount = 1;
            b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        li.bindingCount = 2;
        li.pBindings = b;
        vkCreateDescriptorSetLayout(dev, &li, nullptr, &dsl);
        VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, 8};
        VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pli.setLayoutCount = 1;
        pli.pSetLayouts = &dsl;
        pli.pushConstantRangeCount = 1;
        pli.pPushConstantRanges = &pcr;
        vkCreatePipelineLayout(dev, &pli, nullptr, &pl);

        VkComputePipelineCreateInfo ci[2]{};
        VkShaderModule mods[2] = {modFma, modCopy};
        for (int i = 0; i < 2; ++i) {
            ci[i].sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            ci[i].stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            ci[i].stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            ci[i].stage.module = mods[i];
            ci[i].stage.pName = "main";
            ci[i].layout = pl;
        }
        VkPipeline pipes[2] = {};
        if (vkCreateComputePipelines(dev, VK_NULL_HANDLE, 2, ci, nullptr, pipes) != VK_SUCCESS) failure = "compute pipeline creation failed";
        pipeFma = pipes[0];
        pipeCopy = pipes[1];
    }
    if (failure.empty()) {
        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4};
        VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pci.maxSets = 2;
        pci.poolSizeCount = 1;
        pci.pPoolSizes = &ps;
        vkCreateDescriptorPool(dev, &pci, nullptr, &dpool);
        VkDescriptorSetLayout layouts[2] = {dsl, dsl};
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        ai.descriptorPool = dpool;
        ai.descriptorSetCount = 2;
        ai.pSetLayouts = layouts;
        VkDescriptorSet sets[2] = {};
        if (vkAllocateDescriptorSets(dev, &ai, sets) != VK_SUCCESS) failure = "descriptor allocation failed";
        setFma = sets[0];
        setCopy = sets[1];
        VkDescriptorBufferInfo bi[4] = {{bufs[2], 0, VK_WHOLE_SIZE}, {bufs[1], 0, VK_WHOLE_SIZE},
                                        {bufs[0], 0, VK_WHOLE_SIZE}, {bufs[1], 0, VK_WHOLE_SIZE}};
        VkWriteDescriptorSet w[4]{};
        VkDescriptorSet tgt[4] = {setFma, setFma, setCopy, setCopy};
        for (int i = 0; i < 4; ++i) {
            w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w[i].dstSet = tgt[i];
            w[i].dstBinding = static_cast<uint32_t>(i % 2);
            w[i].descriptorCount = 1;
            w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            w[i].pBufferInfo = &bi[i];
        }
        if (failure.empty()) vkUpdateDescriptorSets(dev, 4, w, 0, nullptr);
    }
    if (failure.empty()) {
        VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cpi.queueFamilyIndex = static_cast<uint32_t>(qfi);
        vkCreateCommandPool(dev, &cpi, nullptr, &pool);
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = pool;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = 1;
        vkAllocateCommandBuffers(dev, &cai, &cmd);
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        vkCreateFence(dev, &fci, nullptr, &fence);
        if (useTs) {
            VkQueryPoolCreateInfo qpi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
            qpi.queryType = VK_QUERY_TYPE_TIMESTAMP;
            qpi.queryCount = 2;
            if (vkCreateQueryPool(dev, &qpi, nullptr, &qp) != VK_SUCCESS) useTs = false;
        }
    }

    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;

    // Runs `reps` dispatches of one pipeline, returns GPU (or wall) milliseconds, <0 on failure.
    auto run = [&](bool fma, int reps) -> double {
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        if (useTs) {
            vkCmdResetQueryPool(cmd, qp, 0, 2);
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp, 0);
        }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, fma ? pipeFma : pipeCopy);
        VkDescriptorSet s = fma ? setFma : setCopy;
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 1, &s, 0, nullptr);
        struct { float seed; uint32_t iters; } pc{0.25f, fmaIters};
        vkCmdPushConstants(cmd, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, &pc);
        for (int i = 0; i < reps; ++i) {
            vkCmdDispatch(cmd, fma ? fmaGroups : copyGroups, 1, 1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        }
        if (useTs) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp, 1);
        vkEndCommandBuffer(cmd);
        vkResetFences(dev, 1, &fence);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        auto t0 = std::chrono::steady_clock::now();
        if (vkQueueSubmit(queue, 1, &si, fence) != VK_SUCCESS) return -1;
        if (vkWaitForFences(dev, 1, &fence, VK_TRUE, 30ull * 1000 * 1000 * 1000) != VK_SUCCESS) return -1;
        double wall = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (useTs) {
            uint64_t ts[2] = {0, 0};
            if (vkGetQueryPoolResults(dev, qp, 0, 2, sizeof ts, ts, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS && ts[1] > ts[0]) {
                uint64_t mask = qf[qfi].timestampValidBits >= 64 ? ~0ull : ((1ull << qf[qfi].timestampValidBits) - 1);
                double gpu = static_cast<double>((ts[1] - ts[0]) & mask) * props.limits.timestampPeriod / 1e6;
                if (gpu > 0) return gpu;
            }
        }
        return wall;
    };

    double fmaMs = 0, copyMs = 0;
    int fmaReps = 8, copyReps = 8;
    std::vector<double> sustained;
    if (failure.empty()) {
        // initialise source buffer
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(cmd, &bi);
        vkCmdFillBuffer(cmd, bufs[0], 0, VK_WHOLE_SIZE, 0x3F800000u);
        vkEndCommandBuffer(cmd);
        vkResetFences(dev, 1, &fence);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkQueueSubmit(queue, 1, &si, fence);
        vkWaitForFences(dev, 1, &fence, VK_TRUE, 10ull * 1000 * 1000 * 1000);

        // calibrate so each submission takes ~100 ms (avoids GPU watchdog, keeps timing accurate)
        double t = run(true, 1);
        if (t < 0) failure = "compute submit failed (device lost?)";
        else fmaReps = std::max(1, std::min(400, static_cast<int>(100.0 / std::max(t, 0.05))));
        if (failure.empty()) {
            t = run(false, 1);
            if (t < 0) failure = "copy submit failed";
            else copyReps = std::max(1, std::min(400, static_cast<int>(100.0 / std::max(t, 0.05))));
        }
        if (failure.empty()) {
            double best = 1e30;
            for (int k = 0; k < 5 && failure.empty(); ++k) {
                double x = run(true, fmaReps);
                if (x < 0) failure = "fma run failed"; else best = std::min(best, x);
            }
            fmaMs = best;
        }
        if (failure.empty()) {
            double best = 1e30;
            for (int k = 0; k < 5 && failure.empty(); ++k) {
                double x = run(false, copyReps);
                if (x < 0) failure = "copy run failed"; else best = std::min(best, x);
            }
            copyMs = best;
        }
        // sustained: keep the ALU loaded for `seconds`, one sample per ~second
        if (failure.empty() && seconds > 0) {
            auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
            while (std::chrono::steady_clock::now() < end && failure.empty()) {
                double acc = 0;
                int cnt = 0;
                auto segEnd = std::chrono::steady_clock::now() + std::chrono::milliseconds(1000);
                while (std::chrono::steady_clock::now() < segEnd) {
                    double x = run(true, fmaReps);
                    if (x < 0) { failure = "sustained run failed"; break; }
                    acc += x; ++cnt;
                }
                if (cnt > 0) {
                    double flop = double(fmaGroups) * groupSize * fmaIters * 64.0 * fmaReps * cnt;
                    sustained.push_back(flop / (acc / 1000.0) / 1e9);
                }
            }
        }
    }

    if (qp) vkDestroyQueryPool(dev, qp, nullptr);
    if (fence) vkDestroyFence(dev, fence, nullptr);
    if (pool) vkDestroyCommandPool(dev, pool, nullptr);
    if (dpool) vkDestroyDescriptorPool(dev, dpool, nullptr);
    if (pipeFma) vkDestroyPipeline(dev, pipeFma, nullptr);
    if (pipeCopy) vkDestroyPipeline(dev, pipeCopy, nullptr);
    if (pl) vkDestroyPipelineLayout(dev, pl, nullptr);
    if (dsl) vkDestroyDescriptorSetLayout(dev, dsl, nullptr);
    if (modFma) vkDestroyShaderModule(dev, modFma, nullptr);
    if (modCopy) vkDestroyShaderModule(dev, modCopy, nullptr);
    for (int i = 0; i < 3; ++i) {
        if (bufs[i]) vkDestroyBuffer(dev, bufs[i], nullptr);
        if (mems[i]) vkFreeMemory(dev, mems[i], nullptr);
    }
    vkDestroyDevice(dev, nullptr);
    if (!failure.empty()) return err(failure);

    // 8 vec4 FMAs per loop iteration = 32 scalar FMA = 64 FLOP
    double flops = double(fmaGroups) * groupSize * fmaIters * 64.0 * fmaReps;
    double gflops = flops / (fmaMs / 1000.0) / 1e9;
    double bytes = double(copyBytes) * 2.0 * copyReps;  // read + write
    double gbs = bytes / (copyMs / 1000.0) / 1e9;
    std::string sj;
    for (size_t i = 0; i < sustained.size(); ++i) { if (i) sj += ","; sj += num(sustained[i]); }
    double smin = sustained.empty() ? 0 : *std::min_element(sustained.begin(), sustained.end());
    double smax = sustained.empty() ? 0 : *std::max_element(sustained.begin(), sustained.end());
    return "{\"ok\":true,\"device\":" + q(props.deviceName) + ",\"driverVersion\":" + q(ver(props.driverVersion)) +
           ",\"timer\":" + q(useTs ? "gpu-timestamps" : "wall-clock") + ",\"fp32Gflops\":" + num(gflops) +
           ",\"computeGBs\":" + num(gbs) + ",\"fmaMs\":" + num(fmaMs) + ",\"copyMs\":" + num(copyMs) +
           ",\"sustained\":[" + sj + "],\"stability\":" + num(smax > 0 ? smin / smax * 100.0 : 0) + "}";
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_elitetunerx_vk_VkNative_probe(JNIEnv *env, jobject, jstring dir, jstring lib, jstring hook) {
    std::string d = jstr(env, dir), l = jstr(env, lib), h = jstr(env, hook);
    std::string r = probeImpl(d.empty() ? nullptr : d.c_str(), l.c_str(), h.c_str());
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_elitetunerx_vk_VkNative_bandwidth(JNIEnv *env, jobject, jstring dir, jstring lib, jstring hook, jint mb,
                                           jint iters) {
    std::string d = jstr(env, dir), l = jstr(env, lib), h = jstr(env, hook);
    std::string r = benchImpl(d.empty() ? nullptr : d.c_str(), l.c_str(), h.c_str(), mb, iters);
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_elitetunerx_vk_VkNative_compute(JNIEnv *env, jobject, jstring dir, jstring lib, jstring hook, jint seconds) {
    std::string d = jstr(env, dir), l = jstr(env, lib), h = jstr(env, hook);
    std::string r = computeImpl(d.empty() ? nullptr : d.c_str(), l.c_str(), h.c_str(), seconds);
    return env->NewStringUTF(r.c_str());
}
