package com.lumatv.app

import java.util.Locale

/**
 * Preserve each series edition's original language/subtitle label.
 * Canonical names are used only to display related editions together.
 */
object SeriesVariants {
    private val languageWords = setOf(
        "EN", "ENG", "ENGLISH", "UK", "US", "FR", "FRA", "FRENCH",
        "DE", "DEU", "GER", "GERMAN", "ES", "ESP", "SPANISH",
        "IT", "ITA", "ITALIAN", "PT", "POR", "PORTUGUESE",
        "NL", "DUTCH", "PL", "POLISH", "TR", "TURKISH",
        "AR", "ARABIC", "RU", "RUSSIAN", "JP", "JPN", "JAPANESE",
        "KR", "KOR", "KOREAN", "HI", "HINDI", "VOSTFR", "MULTI",
        "DUAL", "AUDIO", "SUB", "SUBS", "SUBBED", "SUBTITLED",
        "DUB", "DUBS", "DUBBED", "CC", "HEVC", "HDR", "4K",
        "UHD", "FHD", "HD", "1080P", "720P"
    )
    private val leading = Regex("""^\s*(?:\(|\[)([^)\]]{1,40})(?:\)|\])""")
    private val trailing = Regex("""(?:\(|\[)([^)\]]{1,40})(?:\)|\])\s*$""")
    private val afterEpisode = Regex("""^\s*[-:|]?\s*(?:\(|\[)([^)\]]{1,40})(?:\)|\])""")
    private val splitTokens = Regex("""[\s+/,&|\-_]+""")
    private val whitespace = Regex("""\s+""")

    private fun isEditionLabel(value: String): Boolean {
        val words = value.uppercase(Locale.ROOT).trim().split(splitTokens)
            .filter(String::isNotBlank)
        return words.isNotEmpty() && words.size <= 6 && words.all { it in languageWords }
    }

    /** Keep [EN] or (French Subs) after S01E02; exclude episode-specific titles. */
    fun episodeSeriesTitle(fullTitle: String, episodeStart: Int, episodeEndExclusive: Int): String {
        val base = fullTitle.substring(0, episodeStart).trim().trimEnd('-', ':', '.', ' ')
            .ifBlank { fullTitle }
        var suffix = fullTitle.substring(episodeEndExclusive)
        val editionTags = mutableListOf<String>()
        repeat(3) {
            val match = afterEpisode.find(suffix)
            if (match != null && match.range.first == 0 && isEditionLabel(match.groupValues[1])) {
                editionTags.add(match.value.trim().trimStart('-', ':', '|', ' '))
                suffix = suffix.substring(match.range.last + 1)
            }
        }
        return (listOf(base) + editionTags).joinToString(" ").trim()
    }

    /** Only group titles for the language selector; never merge their entries. */
    fun canonicalTitle(title: String): String {
        var value = title.trim()
        repeat(4) {
            val first = leading.find(value)
            if (first != null && isEditionLabel(first.groupValues[1])) {
                value = value.substring(first.range.last + 1).trimStart(' ', '-', ':', '|')
            }
            val last = trailing.find(value)
            if (last != null && isEditionLabel(last.groupValues[1])) {
                value = value.substring(0, last.range.first).trimEnd(' ', '-', ':', '|')
            }
        }
        return value.lowercase(Locale.ROOT).replace(whitespace, " ").trim()
    }
}
