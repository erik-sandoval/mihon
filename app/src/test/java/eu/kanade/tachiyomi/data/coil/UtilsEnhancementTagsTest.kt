package eu.kanade.tachiyomi.data.coil

import android.content.Context
import coil3.request.ImageRequest
import coil3.request.Options
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The enhancement tags are written onto an [ImageRequest.Builder] but read back off [Options]
 * (matching [customDecoder]'s existing reader-side, since that's what the real pipeline needs:
 * a Coil [coil3.decode.Decoder.Factory] receives [Options], not the original [ImageRequest]).
 * Coil itself carries a request's `extras` forward into the [Options] built for that request, so
 * building an [Options] from the built [ImageRequest]'s `extras` here reproduces that hand-off.
 */
class UtilsEnhancementTagsTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun enhancedAndMangaIdTagsRoundTripThroughBuilder() {
        val request = ImageRequest.Builder(context)
            .data("dummy")
            .enhanced(true)
            .mangaId(42L)
            .chapterId(7L)
            .pageIndex(3)
            .pageVariant("double")
            .build()

        val options = Options(context = context, extras = request.extras)

        assertTrue(options.isEnhanced())
        assertEquals(42L, options.mangaIdOrNull())
        assertEquals(7L, options.chapterIdOrNull())
        assertEquals(3, options.pageIndexOrNull())
        assertEquals("double", options.pageVariantOrNull())
    }

    @Test
    fun tagsAreAbsentByDefaultWhenNotSetOnBuilder() {
        val request = ImageRequest.Builder(context)
            .data("dummy")
            .build()

        val options = Options(context = context, extras = request.extras)

        assertTrue(!options.isEnhanced())
        assertNull(options.mangaIdOrNull())
        assertNull(options.chapterIdOrNull())
        assertNull(options.pageIndexOrNull())
        assertNull(options.pageVariantOrNull())
    }
}
