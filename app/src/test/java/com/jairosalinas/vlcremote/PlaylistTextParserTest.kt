package com.jairosalinas.vlcremote

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTextParserTest {
    @Test fun ignoresM3uCommentsAndBlankLines() {
        val result = PlaylistTextParser.parse(
            sequenceOf("#EXTM3U", "", "  http://example.test/a.ts  ", "#EXTINF:-1,Canal", "http://example.test/b.ts")
        )
        assertEquals(listOf("http://example.test/a.ts", "http://example.test/b.ts"), result)
    }

    @Test fun parsesPlsFileEntriesAndIgnoresMetadata() {
        val result = PlaylistTextParser.parse(
            sequenceOf(
                "[playlist]",
                "File1=http://example.test/a",
                "Title1=A",
                "Length1=-1",
                "File2=http://example.test/b",
                "NumberOfEntries=2",
                "Version=2"
            )
        )
        assertEquals(listOf("http://example.test/a", "http://example.test/b"), result)
    }

    @Test fun preservesLocalAndNetworkPaths() {
        val result = PlaylistTextParser.parse(sequenceOf("D:\\Media\\movie.mkv", "smb://server/media/song.mp3"))
        assertEquals(listOf("D:\\Media\\movie.mkv", "smb://server/media/song.mp3"), result)
    }
}
