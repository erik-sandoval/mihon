package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WaifuxBackendResolutionTest {

    @Test
    fun requestingNpuBackendFallsBackToVulkanWhenUnsupported() {
        // model = 3 is not among the NPU-compatible models in this port.
        // (model = 2 was the brief's original suggestion, but it's MODEL_REAL_ESRGAN_ANIME,
        // which IS explicitly NPU-compatible per isQualcommNpuModelSupported at scale 2 -
        // verified by reading the ported implementation.)
        val resolved = Waifu2x.resolveProcessingBackend(
            requestedBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
            model = 3,
            scale = 2,
        )
        assertEquals(Waifu2x.PROCESSING_BACKEND_VULKAN, resolved)
    }

    @Test
    fun requestingVulkanBackendStaysVulkan() {
        val resolved = Waifu2x.resolveProcessingBackend(
            requestedBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
            model = 0,
            scale = 2,
        )
        assertEquals(Waifu2x.PROCESSING_BACKEND_VULKAN, resolved)
    }
}
