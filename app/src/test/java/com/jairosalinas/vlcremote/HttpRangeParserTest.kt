package com.jairosalinas.vlcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRangeParserTest {
    @Test fun nullHeaderMeansFullResponse() {
        assertNull(HttpRangeParser.parse(null, 1000))
    }

    @Test fun parsesClosedRange() {
        assertEquals(0L..99L, HttpRangeParser.parse("bytes=0-99", 1000))
    }

    @Test fun parsesOpenEndedRange() {
        assertEquals(100L..999L, HttpRangeParser.parse("bytes=100-", 1000))
    }

    @Test fun parsesSuffixRange() {
        assertEquals(500L..999L, HttpRangeParser.parse("bytes=-500", 1000))
    }

    @Test fun clampsEndToFileSize() {
        assertEquals(900L..999L, HttpRangeParser.parse("bytes=900-5000", 1000))
    }

    @Test fun rejectsStartBeyondEndOfFile() {
        assertNull(HttpRangeParser.parse("bytes=1000-", 1000))
    }

    @Test fun rejectsMalformedRange() {
        assertNull(HttpRangeParser.parse("bytes=abc-def", 1000))
    }

    @Test fun rejectsBackwardsRange() {
        assertNull(HttpRangeParser.parse("bytes=900-100", 1000))
    }
}
