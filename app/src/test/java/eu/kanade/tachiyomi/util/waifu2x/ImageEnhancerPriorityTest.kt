package eu.kanade.tachiyomi.util.waifu2x

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImageEnhancerPriorityTest {

    @BeforeEach
    fun resetQueueState() {
        ImageEnhancer.reset(initialPageIndex = 5)
    }

    private fun request(pageIndex: Int, priority: Int, seq: Int) = ImageEnhancer.EnhanceRequest(
        context = mockk(relaxed = true),
        mangaId = 1L,
        chapterId = 1L,
        pageIndex = pageIndex,
        pageVariant = "",
        dataProvider = { null },
        priority = priority,
        generation = 0,
        seq = seq,
    )

    @Test
    fun targetPageAlwaysSortsBeforeNonTargetPages() {
        val target = request(pageIndex = 5, priority = 0, seq = 0)
        val nearby = request(pageIndex = 6, priority = 1, seq = 1)

        assertEquals(-1, target.compareTo(nearby).coerceIn(-1, 1))
    }

    @Test
    fun amongEqualPriorityRequestsCloserToTargetSortsFirst() {
        val near = request(pageIndex = 6, priority = 0, seq = 0)
        val far = request(pageIndex = 9, priority = 0, seq = 1)

        assertEquals(-1, near.compareTo(far).coerceIn(-1, 1))
    }
}
