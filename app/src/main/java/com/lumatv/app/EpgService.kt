package com.lumatv.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale

object EpgService {
    fun load(url: String, channels: List<MediaEntry>): Map<String, Schedule> {
        if (url.isBlank()) return emptyMap()
        val ids = channels.map { it.epgId }.filter { it.isNotBlank() }.toHashSet()
        if (ids.isEmpty()) return emptyMap()
        val (connection, stream) = Net.open(url, 35_000)
        try {
            stream.use { input ->
                val xml = Xml.newPullParser()
                xml.setInput(input, "UTF-8")
                val now = System.currentTimeMillis()
                val current = mutableMapOf<String, Programme>()
                val next = mutableMapOf<String, Programme>()
                var visited = 0
                var event = xml.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && xml.name == "programme") {
                        visited++
                        if (visited > 350_000) break
                        val channel = xml.getAttributeValue(null, "channel") ?: ""
                        if (channel in ids) {
                            val from = parseTime(xml.getAttributeValue(null, "start") ?: "")
                            val to = parseTime(xml.getAttributeValue(null, "stop") ?: "")
                            var text = ""
                            var inner = xml.next()
                            while (!(inner == XmlPullParser.END_TAG && xml.name == "programme") && inner != XmlPullParser.END_DOCUMENT) {
                                if (inner == XmlPullParser.START_TAG && xml.name == "title") {
                                    text = xml.nextText().trim()
                                }
                                inner = xml.next()
                            }
                            if (from > 0 && to > from && text.isNotBlank()) {
                                val programme = Programme(text, from, to)
                                if (from <= now && to > now) current[channel] = programme
                                else if (from > now && (next[channel]?.starts ?: Long.MAX_VALUE) > from) next[channel] = programme
                            }
                        }
                    }
                    event = xml.next()
                }
                return ids.mapNotNull { id ->
                    if (current[id] != null || next[id] != null) id to Schedule(current[id], next[id]) else null
                }.toMap()
            }
        } finally { connection.disconnect() }
    }

    private fun parseTime(value: String): Long {
        val v = value.trim().replace(Regex("\\s+"), " ")
        val pattern = when {
            Regex("^\\d{14} [+-]\\d{4}$").matches(v) -> "yyyyMMddHHmmss Z"
            Regex("^\\d{12} [+-]\\d{4}$").matches(v) -> "yyyyMMddHHmm Z"
            Regex("^\\d{14}$").matches(v) -> "yyyyMMddHHmmss"
            else -> return 0L
        }
        return runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(v)?.time ?: 0L
        }.getOrDefault(0L)
    }
}
