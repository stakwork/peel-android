package com.sphinx.peel.engine

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/// The chats-list date label for a conversation's last message: the short
/// local time when it was sent today, the short local date otherwise, null
/// when there is no timestamp. Pure, with `now`/`zone`/`locale` injected.
fun dateLabel(timestampMillis: Long?, now: Instant, zone: ZoneId, locale: Locale): String? {
    if (timestampMillis == null) return null
    val sent = Instant.ofEpochMilli(timestampMillis).atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val style = if (sent.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    }
    return style.withLocale(locale).format(sent)
}
