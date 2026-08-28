package eu.kanade.tachiyomi.ui.reader.viewer.panel

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelFlagExporterTest {

    @Test
    fun stemIncludesSeriesChapterAndPageButNotReason() {
        // Reason-less and timestamp-less on purpose: flagging the same page under a *different*
        // reason later must resolve to this exact same stem, or it writes a duplicate image
        // instead of updating the one entry for that page — see toggleReason below.
        val name = panelFlagPageStem("Blue Lock", "Chapter 2", pageNumber = 59)
        assertTrue(name.contains("Blue Lock"))
        assertTrue(name.contains("Chapter 2"))
        assertTrue(name.contains("p59"))
    }

    @Test
    fun stemIsStableAcrossCallsForTheSamePage() {
        // Re-flagging the same page (whatever the reason) has to resolve to the exact same
        // filename stem so it overwrites in place, and removing the last reason has to be able to
        // find those exact files again to delete them.
        val first = panelFlagPageStem("Blue Lock", "Chapter 2", pageNumber = 38)
        val second = panelFlagPageStem("Blue Lock", "Chapter 2", pageNumber = 38)
        assertEquals(first, second)
    }

    @Test
    fun stemDiffersByPage() {
        val page59 = panelFlagPageStem("Blue Lock", "Chapter 2", pageNumber = 59)
        val page60 = panelFlagPageStem("Blue Lock", "Chapter 2", pageNumber = 60)
        assertFalse(page59 == page60)
    }

    @Test
    fun stemHasNoPathSeparatorsEvenWhenTitleOrChapterContainThem() {
        // The stem becomes a filename (page.jpg / detection.json share it) — a "/" in the manga
        // title or chapter name (both free text from the source) must never split it into a path.
        val name = panelFlagPageStem("Vol.1/Special", "Ch.2/3", pageNumber = 1)
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
    }

    @Test
    fun togglingAnAbsentReasonAddsIt() {
        val result = toggleReason(emptySet(), PanelFlagReason.BAD_DETECTION)
        assertEquals(setOf(PanelFlagReason.BAD_DETECTION), result)
    }

    @Test
    fun togglingAPresentReasonRemovesIt() {
        val result = toggleReason(setOf(PanelFlagReason.BAD_DETECTION), PanelFlagReason.BAD_DETECTION)
        assertTrue(result.isEmpty())
    }

    @Test
    fun togglingASecondReasonAddsItAlongsideTheFirst() {
        // The real bug this whole toggle exists to fix: flagging a page as WRONG_ORDER after it
        // was already flagged BAD_DETECTION must not lose the first reason or need a second file.
        val afterFirst = toggleReason(emptySet(), PanelFlagReason.BAD_DETECTION)
        val afterSecond = toggleReason(afterFirst, PanelFlagReason.WRONG_ORDER)
        assertEquals(setOf(PanelFlagReason.BAD_DETECTION, PanelFlagReason.WRONG_ORDER), afterSecond)
    }

    @Test
    fun togglingOneOfTwoReasonsOffLeavesTheOther() {
        val both = setOf(PanelFlagReason.BAD_DETECTION, PanelFlagReason.WRONG_ORDER)
        val result = toggleReason(both, PanelFlagReason.BAD_DETECTION)
        assertEquals(setOf(PanelFlagReason.WRONG_ORDER), result)
    }

    @Test
    fun payloadJsonRoundTripsScoredBoxesAndMultipleReasons() {
        val payload = PanelFlagPayload(
            mangaTitle = "Blue Lock",
            chapterName = "Chapter 2",
            chapterId = 42L,
            pageIndex = 58,
            pageNumber = 59,
            direction = "RTL",
            reasons = setOf(PanelFlagReason.MISSED_TEXT, PanelFlagReason.WRONG_ORDER),
            timestampMs = 123L,
            detectorVersion = 53,
            imageWidth = 562,
            imageHeight = 800,
            rawDetections = listOf(
                ScoredBox(PanelRect(0.1f, 0.1f, 0.2f, 0.2f), score = 0.09f, cls = YoloPanelDecoder.TEXT_CLASS),
            ),
            finalPanels = listOf(PanelRect(0f, 0f, 1f, 1f)),
        )
        val json = Json.encodeToString(PanelFlagPayload.serializer(), payload)
        val decoded = Json.decodeFromString(PanelFlagPayload.serializer(), json)

        assertEquals(1, decoded.rawDetections.size)
        assertEquals(0.09f, decoded.rawDetections.single().score)
        assertEquals(YoloPanelDecoder.TEXT_CLASS, decoded.rawDetections.single().cls)
        assertEquals(setOf(PanelFlagReason.MISSED_TEXT, PanelFlagReason.WRONG_ORDER), decoded.reasons)
        assertEquals(payload, decoded)
    }
}
