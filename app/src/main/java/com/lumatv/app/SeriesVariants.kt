package com.lumatv.app

import java.util.Locale

/**
 * An edition is not interchangeable with another just because it belongs to
 * the same programme. Keep the display title intact, including language tags.
 */
object SeriesVariants {
    private val tag = Regex("""(?i)(?:EN|ENG|ENGLISH|FR|FRA|FRENCH|DE|DEU|GER|GERMAN|ES|ESP|SPANISH|IT|ITA|ITALIAN|PT|POR|PORTUGUESE|NL|DUTCH|PL|POLISH|TR|TURKISH|AR|ARABIC|RU|RUSSIAN|UK|US|JP|JPN|JAPANESE|KR|KOR|KOREAN|HI|HINDI|VOSTFR|MULTI|DUAL(?:[ -]?AUDIO)?|SUB(?:S|BED|TITLED)?|DUB(?:S|BED)?|CC|HEVC|HDR|4K|UHD|FHD|HD|1080P|720P|[A-Z]{2}(?:[ -]?(?:SUBS?|DUB(?:BED)?|CC)))(?:[ +/,&-]+(?:EN|ENG|FR|FRA|DE|GER|ES|ESP|IT|PT|SUBS?|DUB(?:BED)?|CC|DUAL|VOSTFR|4K|HD|1080P))*)""")
    private val wrapped = Regex("""^\s*([\[(])([^\])\]]{1,40})([\])])""")
    private val trailing = Regex("""([\[(])([^\])\]]{1,40})([\])])\s*$""")
    private val seasonSuffix = Regex("""(?i)^\s*[-:|]?\s*([\[(])([^\])\]]{1,40})([\])])""")
    private val whitespace = Regex("""\s+""")

    private fun isEditionLabel(value: String): Boolean {
        val token = value.trim()
        return token.isNotEmpty() && tag.matches(token)
    }

    /** Keep [EN] / (French Subs) after S01E02, but never add the episode name. */
    fun episodeSeriesTitle(fullTitle: String, episodeStart: Int, episodeEndExclusive: Int): String {
        val base = fullTitle.substring(0, episodeStart).trim().trimEnd('-', ':', '.', ' ')
            .ifBlank { fullTitle }
        var suffix = fullTitle.substring(episodeEndExclusive)
        val labels = mutableListOf<String>()
        repeat(3) {
            val match = seasonSuffix.find(suffix) ?: return@repeat
            if (match.range.first != 0 || !isEditionLabel(match.groupValues[2])) return@repeat
            labels.add(match.groupValues[0].trim().trimStart('-', ':', '|', ' '))
            suffix = suffix.substring(match.range.last + 1)
        }
        return (listOf(base) + labels).joinToString(" ").trim()
    }

    /** Group editions for navigation while never merging their actual MediaEntry IDs. */
    fun canonicalTitle(title: String): String {
        var clean = title.trim()
        repeat(4) {
            val prefix = wrapped.find(clean)
            if (prefix != null && isEditionLabel(prefix.groupValues[2])) {
                clean = clean.substring(prefix.range.last + 1).trimStart(' ', '-', ':', '|')
            }
            val suffix = trailing.find(clean)
            if (suffix != null && isEditionLabel(suffix.groupValues[2])) {
                clean = clean.substring(0, suffix.range.first).trimEnd(' ', '-', ':', '|')
            }
        }
        return clean.lowercase(Locale.ROOT).replace(whitespace, " ").trim()
    }
}
