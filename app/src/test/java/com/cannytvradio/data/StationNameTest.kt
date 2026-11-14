package com.cannytvradio.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Station names from the directory, cleaned of technical extras. */
class StationNameTest {
    @Test fun technicalExtrasAreRemoved() {
        assertEquals("FM4", Station.cleanName("FM4 | ORF | HQ"))
        assertEquals("Deutschlandfunk", Station.cleanName("Deutschlandfunk | DLF | MP3 128k"))
        assertEquals("Radio Paradise Main Mix (EU)", Station.cleanName("Radio Paradise Main Mix (EU) 320k AAC"))
        assertEquals("Bayern 3", Station.cleanName("Bayern 3"))
        assertEquals("Rock Antenne - Punk Rock", Station.cleanName("Rock Antenne - Punk Rock"))
        assertEquals("SWR3", Station.cleanName("SWR3"))
        assertEquals("1LIVE", Station.cleanName("1LIVE"))
        assertEquals("Hitradio Ö3", Station.cleanName("Hitradio Ö3 (HQ)"))
        assertEquals("DEEJAY REMIXE", Station.cleanName("_DEEJAY REMIXE_"))
        assertEquals("TOP 100 CHARTS", Station.cleanName("# TOP 100 CHARTS"))
    }
}
