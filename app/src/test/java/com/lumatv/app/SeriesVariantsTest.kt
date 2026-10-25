package com.lumatv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeriesVariantsTest {
    @Test fun retainsLanguageAfterEpisodeNumber() {
        val en = "Scorpion S01E01 (EN)"
        val fr = "Scorpion S01E01 (FR SUB)"
        val pattern = Regex("""(?i)S\d{2}E\d{2}""")
        val e = pattern.find(en)!!
        val f = pattern.find(fr)!!
        assertEquals("Scorpion (EN)", SeriesVariants.episodeSeriesTitle(en, e.range.first, e.range.last + 1))
        assertEquals("Scorpion (FR SUB)", SeriesVariants.episodeSeriesTitle(fr, f.range.first, f.range.last + 1))
    }

    @Test fun episodeNameDoesNotBecomeASeparateSeries() {
        val title = "Scorpion S01E01 - The Pilot"
        val match = Regex("""S\d{2}E\d{2}""").find(title)!!
        assertEquals("Scorpion", SeriesVariants.episodeSeriesTitle(title, match.range.first, match.range.last + 1))
    }

    @Test fun findsOtherLanguageVersionsWithoutMergingEditions() {
        assertEquals("scorpion", SeriesVariants.canonicalTitle("(EN) Scorpion"))
        assertEquals("scorpion", SeriesVariants.canonicalTitle("Scorpion (FR SUB)"))
        assertEquals("scorpion", SeriesVariants.canonicalTitle("[DE] Scorpion"))
        assertEquals("scorpion", SeriesVariants.canonicalTitle("Scorpion"))
        assertNotEquals(SeriesVariants.canonicalTitle("Scorpion"), SeriesVariants.canonicalTitle("Breaking Bad"))
    }

    @Test fun keepsMeaningfulParenthesizedNames() {
        assertEquals("the office", SeriesVariants.canonicalTitle("The Office (US)"))
        assertEquals("scorpion (2014)", SeriesVariants.canonicalTitle("Scorpion (2014)"))
    }
}
