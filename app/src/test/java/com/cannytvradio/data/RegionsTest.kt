package com.cannytvradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real spellings from the station directory must land on the right official region. */
class RegionsTest {
    private fun de(raw: String) = Regions.code("DE", raw)

    @Test fun germanStates() {
        listOf("Bavaria", "Bayern", "Bayern, München, AAC 128", "munich", "Franken", "Würzburg").forEach { assertEquals(it, "DE-BY", de(it)) }
        listOf("North Rhine-Westphalia", "NRW", "Nordrhein-Westfalen", "north-rhine-westphalia", "north rhine westphalia",
            "Deutschland und NRW", "Nordrhein-Westfalen - Bochum", "Nordrhein Westfalen ", "Cologne", "Düsseldorf", "Deutschland / NRW /")
            .forEach { assertEquals(it, "DE-NW", de(it)) }
        assertEquals("DE-ST", de("Sachsen-Anhalt"))
        assertEquals("DE-ST", de("Saxony-Anhalt"))
        assertEquals("DE-ST", de("Magdeburg, Sachsen-Anhalt"))
        assertEquals("DE-SN", de("Saxony"))
        assertEquals("DE-SN", de("Leipzig"))
        assertEquals("DE-NI", de("Niedersachcen"))
        assertEquals("DE-NI", de("Niedersachsen (Ostfriesland)"))
        assertEquals("DE-SH", de("Schleswig-Holtstein"))
        assertEquals("DE-BW", de("Baden Wuertemberg"))
        assertEquals("DE-BW", de("BW"))
        assertEquals("DE-RP", de("RLP"))
        assertEquals("DE-RP", de("Rhineland-Palatinate"))
        assertEquals("DE-TH", de("Thüringen - Mitte (Jena/Erfurt/Weimar/Remda)"))
        assertEquals("DE-HE", de("Hessen, Frankfurt, HLS  192 kbits"))
        assertEquals("DE-SL", de("Saarland (Germany)"))
        assertEquals("DE-MV", de("Mecklenburg Vorpommern "))
    }

    @Test fun noiseIsNotARegion() {
        listOf("Germany", "Deutschland", "Deutschland (Germany)", "").forEach { assertNull(it, de(it)) }
    }

    @Test fun austriaAndSwitzerland() {
        assertEquals("AT-9", Regions.code("AT", "Vienna"))
        assertEquals("AT-9", Regions.code("AT", "Wien"))
        assertEquals("AT-4", Regions.code("AT", "Upper Austria"))
        assertEquals("AT-6", Regions.code("AT", "Styria"))
        assertEquals("CH-GE", Regions.code("CH", "Geneva"))
        assertEquals("CH-VD", Regions.code("CH", "Canton of Vaud"))
        assertEquals("CH-BE", Regions.code("CH", "Kanton Bern"))
        assertEquals("CH-BL", Regions.code("CH", "Basel-Landschaft"))
        assertEquals("CH-BS", Regions.code("CH", "Basel"))
    }

    @Test fun namesFollowTheLanguage() {
        assertEquals("Nordrhein-Westfalen", Regions.label("DE", "North Rhine-Westphalia", "de"))
        assertEquals("North Rhine-Westphalia", Regions.label("DE", "NRW", "en"))
        assertEquals("Bayern", Regions.label("DE", "Bavaria", "de"))
        assertEquals("Wien", Regions.label("AT", "Vienna", "de"))
    }

    @Test fun otherCountriesKeepCleanNamesAndDropNoise() {
        assertEquals("California", Regions.label("US", "California", "de"))
        assertNull(Regions.label("US", "USA, 128kbps", "de"))
        assertNull(Regions.label("GB", "Germany", "de"))
    }
}
