package com.cannytvradio.data

/**
 * Clean region names for Germany, Austria and Switzerland.
 *
 * The station directory's "state" field is typed in by hand: the same state shows up as
 * "Bavaria", "Bayern", "Bayern, München, AAC 128" or just a city. This table maps every spelling
 * we have seen (English, German, abbreviations, common typos, big cities) to the official region,
 * which is then shown in the app's language. Other countries keep the directory's own names.
 */
object Regions {
    private class Entry(val code: String, val de: String, val en: String, aliases: List<String>) {
        val aliases = (aliases + de + en).map { Station.normalize(it) }.distinct()
    }

    private fun r(code: String, de: String, en: String, vararg aliases: String) = Entry(code, de, en, aliases.toList())

    private val table: Map<String, List<Entry>> = mapOf(
        "DE" to listOf(
            r("DE-BW", "Baden-Württemberg", "Baden-Württemberg", "baden wurttemberg", "baden wuertemberg", "baden wurtemberg", "bw", "stuttgart", "mannheim", "karlsruhe", "freiburg", "heidelberg", "ulm"),
            r("DE-BY", "Bayern", "Bavaria", "by", "muenchen", "munchen", "munich", "nuernberg", "nurnberg", "nuremberg", "wuerzburg", "wurzburg", "augsburg", "regensburg", "ingolstadt", "franken", "oberbayern", "niederbayern"),
            r("DE-BE", "Berlin", "Berlin"),
            r("DE-BB", "Brandenburg", "Brandenburg", "potsdam", "cottbus"),
            r("DE-HB", "Bremen", "Bremen", "bremerhaven"),
            r("DE-HH", "Hamburg", "Hamburg"),
            r("DE-HE", "Hessen", "Hesse", "frankfurt", "kassel", "wiesbaden", "darmstadt", "offenbach"),
            r("DE-MV", "Mecklenburg-Vorpommern", "Mecklenburg-Western Pomerania", "mecklenburg", "vorpommern", "mv", "rostock", "schwerin"),
            r("DE-NI", "Niedersachsen", "Lower Saxony", "niedersachcen", "hannover", "hanover", "braunschweig", "osnabrueck", "osnabruck", "oldenburg", "ostfriesland", "goettingen", "gottingen"),
            r("DE-NW", "Nordrhein-Westfalen", "North Rhine-Westphalia", "nrw", "nordrhein westfalen", "north rhine westphalia", "koeln", "koln", "cologne", "duesseldorf", "dusseldorf", "dortmund", "essen", "bochum", "bonn", "muenster", "munster", "duisburg", "wuppertal", "bielefeld", "aachen", "ruhrgebiet", "ruhr", "sauerland"),
            r("DE-RP", "Rheinland-Pfalz", "Rhineland-Palatinate", "rlp", "mainz", "koblenz", "trier", "kaiserslautern", "ludwigshafen"),
            r("DE-SL", "Saarland", "Saarland", "saarbruecken", "saarbrucken"),
            r("DE-SN", "Sachsen", "Saxony", "saxonia", "saxon", "sn", "leipzig", "dresden", "chemnitz", "zwickau"),
            r("DE-ST", "Sachsen-Anhalt", "Saxony-Anhalt", "magdeburg", "halle"),
            r("DE-SH", "Schleswig-Holstein", "Schleswig-Holstein", "schleswig holtstein", "kiel", "luebeck", "lubeck", "flensburg"),
            r("DE-TH", "Thüringen", "Thuringia", "thuringen", "erfurt", "jena", "weimar", "gera"),
        ),
        "AT" to listOf(
            r("AT-1", "Burgenland", "Burgenland", "eisenstadt"),
            r("AT-2", "Kärnten", "Carinthia", "karnten", "klagenfurt", "villach"),
            r("AT-3", "Niederösterreich", "Lower Austria", "niederosterreich", "st poelten", "sankt poelten"),
            r("AT-4", "Oberösterreich", "Upper Austria", "oberosterreich", "linz", "wels", "steyr"),
            r("AT-5", "Salzburg", "Salzburg"),
            r("AT-6", "Steiermark", "Styria", "graz"),
            r("AT-7", "Tirol", "Tyrol", "innsbruck"),
            r("AT-8", "Vorarlberg", "Vorarlberg", "bregenz", "dornbirn", "feldkirch"),
            r("AT-9", "Wien", "Vienna", "wieden"),
        ),
        "CH" to listOf(
            r("CH-ZH", "Zürich", "Zurich", "zuerich", "winterthur"),
            r("CH-BE", "Bern", "Bern", "berne", "kanton bern", "thun", "biel"),
            r("CH-LU", "Luzern", "Lucerne", "lucerne"),
            r("CH-UR", "Uri", "Uri"),
            r("CH-SZ", "Schwyz", "Schwyz"),
            r("CH-OW", "Obwalden", "Obwalden"),
            r("CH-NW", "Nidwalden", "Nidwalden"),
            r("CH-GL", "Glarus", "Glarus"),
            r("CH-ZG", "Zug", "Zug"),
            r("CH-FR", "Freiburg", "Fribourg", "fribourg"),
            r("CH-SO", "Solothurn", "Solothurn"),
            r("CH-BS", "Basel-Stadt", "Basel-Stadt", "basel"),
            r("CH-BL", "Basel-Landschaft", "Basel-Landschaft", "baselland"),
            r("CH-SH", "Schaffhausen", "Schaffhausen"),
            r("CH-AR", "Appenzell", "Appenzell"),
            r("CH-SG", "St. Gallen", "St. Gallen", "sankt gallen", "st gallen"),
            r("CH-GR", "Graubünden", "Grisons", "graubunden", "grison", "chur"),
            r("CH-AG", "Aargau", "Aargau", "aarau"),
            r("CH-TG", "Thurgau", "Thurgau"),
            r("CH-TI", "Tessin", "Ticino", "lugano"),
            r("CH-VD", "Waadt", "Vaud", "canton of vaud", "lausanne"),
            r("CH-VS", "Wallis", "Valais", "monthey", "sion"),
            r("CH-NE", "Neuenburg", "Neuchâtel", "neuchatel", "ne"),
            r("CH-GE", "Genf", "Geneva", "geneve", "genf"),
            r("CH-JU", "Jura", "Jura"),
        ),
    )

    private val byCode = table.values.flatten().associateBy { it.code }

    /** Words that mean "the whole country", not a region. */
    private val countryWords = setOf(
        "deutschland", "germany", "deutschland germany", "oesterreich", "osterreich", "austria", "schweiz", "switzerland",
        "suisse", "svizzera", "german swiss",
    )

    fun hasTable(countryCode: String) = countryCode.uppercase() in table

    /**
     * The official region for a typed-in name, or null. Exact spellings win; otherwise the longest
     * known name inside the text ("Bayern, München, AAC" -> Bayern). Two-letter abbreviations only
     * count when they stand alone, so "ne" in a sentence can't turn into a canton.
     */
    fun code(countryCode: String, raw: String): String? {
        val entries = table[countryCode.uppercase()] ?: return null
        val n = Station.normalize(raw)
        if (n.isEmpty()) return null
        entries.firstOrNull { e -> e.aliases.any { it == n } }?.let { return it.code }
        val padded = " $n "
        return entries
            .flatMap { e -> e.aliases.filter { it.length >= 3 }.map { it to e } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (alias, _) -> padded.contains(" $alias ") }
            ?.second?.code
    }

    fun name(code: String, language: String): String? = byCode[code]?.let { if (language == "de") it.de else it.en }

    /**
     * What to show for a station's region: the official name in the app's language, the directory's
     * text when it looks like a real place, or null when it's noise (bitrates, "Germany", lists).
     */
    fun label(countryCode: String, raw: String, language: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        code(countryCode, text)?.let { return name(it, language) }
        if (hasTable(countryCode)) return null
        val n = Station.normalize(text)
        val noisy = n in countryWords || text.any { it.isDigit() } || "," in text || "/" in text || text.length > 32
        return if (noisy) null else text
    }
}
