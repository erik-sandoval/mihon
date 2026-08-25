package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageEnhancementCacheTest {

    @Test
    fun configHashIncludesPipelineVersion() {
        val hash = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, model = 0)
        assertTrue(hash.contains("_pv1"), "expected config hash to embed pipeline version, was: $hash")
    }

    @Test
    fun differentSettingsProduceDifferentHashes() {
        val hashA = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, model = 0)
        val hashB = ImageEnhancementCache.getConfigHash(noise = 3, scale = 2, model = 0)
        assertTrue(hashA != hashB)
    }
}
