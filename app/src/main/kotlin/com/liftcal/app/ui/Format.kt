package com.liftcal.app.ui

import com.liftcal.engine.ExerciseCatalog
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun kcal(v: Double?): String = v?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "-"

fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun exerciseName(id: String): String =
    if (ExerciseCatalog.contains(id)) ExerciseCatalog[id].name else id

fun num(v: Double?): String = v?.let {
    if (it == Math.floor(it)) it.toLong().toString() else String.format(Locale.getDefault(), "%.1f", it)
} ?: ""
