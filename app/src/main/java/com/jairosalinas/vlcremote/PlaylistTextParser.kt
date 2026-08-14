package com.jairosalinas.vlcremote

object PlaylistTextParser {
    fun parse(lines: Sequence<String>): List<String> = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { value ->
            val equals = value.indexOf('=')
            if (equals > 0 && value.substring(0, equals).matches(Regex("(?i)File\\d+"))) {
                value.substring(equals + 1).trim()
            } else {
                value
            }
        }
        .filter { it.isNotEmpty() }
        .toList()
}
