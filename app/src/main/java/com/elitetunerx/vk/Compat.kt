package com.elitetunerx.vk

import org.json.JSONObject

data class VkDevice(
    val name: String, val apiVersion: String, val apiMinor: Int, val driverVersion: String, val driverId: Int,
    val driverName: String, val driverInfo: String, val conformance: String, val vendorId: Int, val deviceId: Int,
    val extensions: Set<String>, val features: Map<String, Boolean>, val limits: Map<String, Double>, val heapsMb: List<Long>,
) {
    fun f(k: String) = features[k] == true
    fun e(k: String) = k in extensions

    val driverKind: String
        get() = when (driverId) {
            8 -> "Qualcomm proprietary (Adreno)"
            18 -> "Mesa Turnip (open source)"
            else -> if (driverName.isNotEmpty()) driverName else "unknown (id $driverId)"
        }
}

data class VkReport(val ok: Boolean, val error: String?, val instanceVersion: String, val devices: List<VkDevice>) {
    companion object {
        fun parse(json: String): VkReport {
            val o = runCatching { JSONObject(json) }.getOrElse { return VkReport(false, "bad JSON: $it", "", emptyList()) }
            if (!o.optBoolean("ok")) return VkReport(false, o.optString("error"), "", emptyList())
            val arr = o.optJSONArray("devices")
            val devs = (0 until (arr?.length() ?: 0)).map { i ->
                val d = arr!!.getJSONObject(i)
                val fo = d.optJSONObject("features") ?: JSONObject()
                val lo = d.optJSONObject("limits") ?: JSONObject()
                val ea = d.optJSONArray("extensions")
                val ha = d.optJSONArray("heaps")
                VkDevice(
                    d.optString("name"), d.optString("effectiveApi"), d.optInt("apiMinor"), d.optString("driverVersion"),
                    d.optInt("driverId"), d.optString("driverName"), d.optString("driverInfo"), d.optString("conformance"),
                    d.optInt("vendorId"), d.optInt("deviceId"),
                    (0 until (ea?.length() ?: 0)).map { ea!!.getString(it) }.toSet(),
                    fo.keys().asSequence().associateWith { fo.optBoolean(it) },
                    lo.keys().asSequence().associateWith { lo.optDouble(it) },
                    (0 until (ha?.length() ?: 0)).map { ha!!.getJSONObject(it).optLong("mb") },
                )
            }
            return VkReport(true, null, o.optString("instanceVersion"), devs)
        }
    }
}

enum class Level { REQUIRED, GAMES, RECOMMENDED }
data class Check(val label: String, val pass: Boolean, val level: Level)
data class LayerVerdict(val layer: String, val note: String, val checks: List<Check>) {
    val requiredOk get() = checks.filter { it.level == Level.REQUIRED }.all { it.pass }
    val gamesOk get() = checks.filter { it.level == Level.GAMES }.all { it.pass }
    val verdict: String
        get() = when {
            !requiredOk -> "ไม่รองรับ — ขาด requirement หลัก"
            !gamesOk -> "ใช้ได้ แต่บางเกมอาจรันไม่ได้/ภาพเพี้ยน"
            else -> "รองรับเต็ม"
        }
}

/**
 * Checks the Vulkan capabilities the translation layers rely on.
 * REQUIRED = the layer refuses to start without it.
 * GAMES = needed for Direct3D 11 feature level 11_0 class games (most games).
 * RECOMMENDED = performance/compatibility extras.
 * The device is queried live; nothing here is assumed.
 */
object Compat {
    private fun api(d: VkDevice, minor: Int) = Check("Vulkan 1.$minor (device ${d.apiVersion})", d.apiMinor >= minor, Level.REQUIRED)

    private fun d3d11Games(d: VkDevice) = listOf(
        Check("geometryShader", d.f("core.geometryShader"), Level.GAMES),
        Check("tessellationShader", d.f("core.tessellationShader"), Level.GAMES),
        Check("imageCubeArray", d.f("core.imageCubeArray"), Level.GAMES),
        Check("independentBlend", d.f("core.independentBlend"), Level.GAMES),
        Check("multiViewport", d.f("core.multiViewport"), Level.GAMES),
        Check("depthClamp / depthBiasClamp", d.f("core.depthClamp") && d.f("core.depthBiasClamp"), Level.GAMES),
        Check("fillModeNonSolid", d.f("core.fillModeNonSolid"), Level.GAMES),
        Check("sampleRateShading", d.f("core.sampleRateShading"), Level.GAMES),
        Check("shaderClipDistance / CullDistance", d.f("core.shaderClipDistance") && d.f("core.shaderCullDistance"), Level.GAMES),
        Check("textureCompressionBC (DXT/BC textures)", d.f("core.textureCompressionBC"), Level.GAMES),
        Check("fragmentStoresAndAtomics", d.f("core.fragmentStoresAndAtomics"), Level.GAMES),
        Check("occlusionQueryPrecise", d.f("core.occlusionQueryPrecise"), Level.GAMES),
    )

    private fun extras(d: VkDevice) = listOf(
        Check("VK_EXT_transform_feedback (stream output)", d.f("xfb.transformFeedback"), Level.RECOMMENDED),
        Check("VK_EXT_depth_clip_enable", d.f("dce.depthClipEnable"), Level.RECOMMENDED),
        Check("VK_EXT_custom_border_color", d.f("cbc.customBorderColors"), Level.RECOMMENDED),
        Check("VK_EXT_graphics_pipeline_library (less shader stutter)", d.f("gpl.graphicsPipelineLibrary"), Level.RECOMMENDED),
        Check("VK_EXT_extended_dynamic_state3", d.e("VK_EXT_extended_dynamic_state3"), Level.RECOMMENDED),
        Check("VK_KHR_maintenance5", d.f("m5.maintenance5"), Level.RECOMMENDED),
        Check("shaderFloat64", d.f("core.shaderFloat64"), Level.RECOMMENDED),
    )

    fun evaluate(d: VkDevice): List<LayerVerdict> {
        val robust2 = Check(
            "VK_EXT_robustness2 (robustBufferAccess2 + nullDescriptor)",
            d.f("rb2.robustBufferAccess2") && d.f("rb2.nullDescriptor"), Level.REQUIRED
        )
        val dxvk1 = LayerVerdict(
            "DXVK 1.10.x (legacy, D3D9/10/11)", "สำหรับไดรเวอร์เก่าที่ยังไม่ถึง Vulkan 1.3",
            listOf(api(d, 1)) + d3d11Games(d) + extras(d)
        )
        val dxvk2 = LayerVerdict(
            "DXVK 2.x (D3D9/10/11)", "DXVK 2.0+ ต้องการ Vulkan 1.3 + robustness2",
            listOf(
                api(d, 3), robust2,
                Check("dynamicRendering", d.f("v13.dynamicRendering"), Level.REQUIRED),
                Check("synchronization2", d.f("v13.synchronization2"), Level.REQUIRED),
                Check("shaderDrawParameters", d.f("v11.shaderDrawParameters"), Level.REQUIRED),
            ) + d3d11Games(d) + extras(d)
        )
        val d8vk = LayerVerdict(
            "D8VK (D3D8, รวมใน DXVK 2.4+)", "ใช้ requirement เดียวกับ DXVK 2.x",
            listOf(api(d, 3), robust2, Check("dynamicRendering", d.f("v13.dynamicRendering"), Level.REQUIRED)) + extras(d)
        )
        val vkd3d = LayerVerdict(
            "VKD3D-Proton (D3D12)", "D3D12 ต้องการ descriptor indexing ระดับสูง",
            listOf(
                api(d, 3), robust2,
                Check("timelineSemaphore", d.f("v12.timelineSemaphore"), Level.REQUIRED),
                Check("bufferDeviceAddress", d.f("v12.bufferDeviceAddress"), Level.REQUIRED),
                Check("descriptorIndexing + runtimeDescriptorArray", d.f("v12.descriptorIndexing") && d.f("v12.runtimeDescriptorArray"), Level.REQUIRED),
                Check("update-after-bind (sampled/storage image/buffer)",
                    d.f("v12.descriptorBindingSampledImageUpdateAfterBind") && d.f("v12.descriptorBindingStorageImageUpdateAfterBind") &&
                        d.f("v12.descriptorBindingStorageBufferUpdateAfterBind"), Level.REQUIRED),
                Check("descriptorBindingPartiallyBound + VariableDescriptorCount",
                    d.f("v12.descriptorBindingPartiallyBound") && d.f("v12.descriptorBindingVariableDescriptorCount"), Level.REQUIRED),
                Check("VK_KHR_push_descriptor", d.e("VK_KHR_push_descriptor"), Level.REQUIRED),
                Check("VK_EXT_mutable_descriptor_type", d.f("mdt.mutableDescriptorType"), Level.GAMES),
                Check("shaderInt64", d.f("core.shaderInt64"), Level.GAMES),
                Check("shaderFloat16 / shaderInt16 (SM 6.2)", d.f("v12.shaderFloat16") && d.f("core.shaderInt16"), Level.GAMES),
                Check("shaderDemoteToHelperInvocation", d.f("v13.shaderDemoteToHelperInvocation"), Level.GAMES),
            ) + d3d11Games(d).filter { it.label.startsWith("geometry") || it.label.startsWith("tessellation") || it.label.startsWith("textureCompressionBC") } + extras(d)
        )
        return listOf(dxvk1, dxvk2, d8vk, vkd3d)
    }
}
