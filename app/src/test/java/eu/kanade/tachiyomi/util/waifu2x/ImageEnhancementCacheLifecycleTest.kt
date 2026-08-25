package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

class ImageEnhancementCacheLifecycleTest {

    @TempDir
    lateinit var tempDir: File

    private fun fakeContext(): Context = mockk(relaxed = true) {
        every { cacheDir } returns tempDir
    }

    @Test
    fun uncachedPageIsNeitherCachedNorSkipped() {
        val context = fakeContext()
        ImageEnhancementCache.init(context)
        val hash = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, model = 0)

        assertFalse(ImageEnhancementCache.isCached(1L, 1L, 0, hash, ""))
        assertFalse(ImageEnhancementCache.isSkipped(1L, 1L, 0, hash, ""))
    }
}
