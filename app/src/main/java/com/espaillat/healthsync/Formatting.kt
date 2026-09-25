package com.espaillat.healthsync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DISPLAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Shared "last synced" display format, used by both MainActivity and the widget. */
fun Instant.toDisplayString(): String = DISPLAY_FORMAT.format(this)
