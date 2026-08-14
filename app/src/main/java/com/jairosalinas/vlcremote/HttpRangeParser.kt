package com.jairosalinas.vlcremote

object HttpRangeParser {
    fun parse(header: String?, size: Long): LongRange? {
        if (header == null || size <= 0L) return null
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substringAfter('=').substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null

        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        return try {
            if (left.isEmpty()) {
                val suffix = right.toLong()
                if (suffix <= 0L) return null
                val start = (size - suffix).coerceAtLeast(0L)
                start..(size - 1L)
            } else {
                val start = left.toLong()
                if (start < 0L || start >= size) return null
                val requestedEnd = if (right.isEmpty()) size - 1L else right.toLong()
                val end = requestedEnd.coerceAtMost(size - 1L)
                if (end < start) null else start..end
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
