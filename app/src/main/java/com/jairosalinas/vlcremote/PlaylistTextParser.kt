package com.jairosalinas.vlcremote

object PlaylistTextParser {
    private val plsFile = Regex("(?i)^File\\d+$")
    private val plsMetadata = Regex("(?i)^(Title|Length)\\d+$")

    fun parse(lines: Sequence<String>): List<String> = buildList {
        for (raw in lines) {
            val value = raw.trim()
            if (value.isEmpty() || value.startsWith("#")) continue
            if (value.equals("[playlist]", ignoreCase = true)) continue

            val equals = value.indexOf('=')
            if (equals > 0) {
                val key = value.substring(0, equals).trim()
                val item = value.substring(equals + 1).trim()
                when {
                    plsFile.matches(key) && item.isNotEmpty() -> add(item)
                    plsMetadata.matches(key) -> Unit
                    key.equals("NumberOfEntries", ignoreCase = true) -> Unit
                    key.equals("Version", ignoreCase = true) -> Unit
                    else -> add(value)
                }
            } else {
                add(value)
            }
        }
    }
}
