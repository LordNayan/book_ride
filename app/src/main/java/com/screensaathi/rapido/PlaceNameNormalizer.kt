package com.screensaathi.rapido

import android.icu.text.Transliterator
import java.util.Locale

/** Produces a place-only Latin search query for Rapido's English location search. */
object PlaceNameNormalizer {
    private val knownPlaces = mapOf(
        "विजयनगर" to "Vijay Nagar",
        "विजय नगर" to "Vijay Nagar",
        "vijaynagar" to "Vijay Nagar",
        "vijay nagar" to "Vijay Nagar",
        "राजवाड़ा" to "Rajwada",
        "राजवाडा" to "Rajwada",
        "रजवाड़ा" to "Rajwada",
        "रजवाडा" to "Rajwada",
        "rajwada" to "Rajwada",
        "पलासिया" to "Palasia",
        "palasia" to "Palasia",
        "पुरानी पलासिया" to "Old Palasia",
        "old palasia" to "Old Palasia",
        "नई पलासिया" to "New Palasia",
        "new palasia" to "New Palasia",
        "भंवरकुआं" to "Bhanwarkuan",
        "भवरकुआं" to "Bhanwarkuan",
        "bhanwarkuan" to "Bhanwarkuan",
        "bhawarkuan" to "Bhanwarkuan",
        "गीता भवन" to "Geeta Bhawan",
        "geeta bhawan" to "Geeta Bhawan",
        "gita bhawan" to "Geeta Bhawan",
        "सपना संगीता" to "Sapna Sangeeta",
        "sapna sangeeta" to "Sapna Sangeeta",
        "सुदामा नगर" to "Sudama Nagar",
        "sudama nagar" to "Sudama Nagar",
        "खजराना" to "Khajrana",
        "khajrana" to "Khajrana",
        "रेस कोर्स रोड" to "Race Course Road",
        "race course road" to "Race Course Road",
        "race course" to "Race Course Road",
        "सरवटे" to "Sarwate Bus Stand",
        "सरवटे बस स्टैंड" to "Sarwate Bus Stand",
        "sarwate" to "Sarwate Bus Stand",
        "sarwate bus stand" to "Sarwate Bus Stand",
        "राजेंद्र नगर" to "Rajendra Nagar",
        "rajendra nagar" to "Rajendra Nagar",
        "नवलखा" to "Navlakha",
        "navlakha" to "Navlakha",
        "मालवा मिल" to "Malwa Mill",
        "malwa mill" to "Malwa Mill",
        "तिलक नगर" to "Tilak Nagar",
        "tilak nagar" to "Tilak Nagar",
        "अन्नपूर्णा" to "Annapurna",
        "annapurna" to "Annapurna",
        "निपानिया" to "Nipania",
        "nipania" to "Nipania",
        "बंगाली चौराहा" to "Bengali Square",
        "bengali square" to "Bengali Square",
        "bengali chauraha" to "Bengali Square",
        "मुसाखेड़ी" to "Musakhedi",
        "मुसाखेडी" to "Musakhedi",
        "musakhedi" to "Musakhedi",
        "देवास नाका" to "Dewas Naka",
        "dewas naka" to "Dewas Naka",
        "महू नाका" to "Mhow Naka",
        "mhow naka" to "Mhow Naka",
        "छप्पन दुकान" to "Chappan Dukan",
        "56 दुकान" to "Chappan Dukan",
        "chappan dukan" to "Chappan Dukan",
        "56 dukan" to "Chappan Dukan",
        "इंदौर रेलवे स्टेशन" to "Indore Railway Station",
        "रेलवे स्टेशन" to "Indore Railway Station",
        "indore railway station" to "Indore Railway Station",
        "railway station" to "Indore Railway Station",
        "परदेशीपुरा" to "Pardeshipura",
        "pardeshipura" to "Pardeshipura",
        "राऊ" to "Rau",
        "rau" to "Rau",
        "सुपर कॉरिडोर" to "Super Corridor",
        "super corridor" to "Super Corridor",
        "कनाड़िया रोड" to "Kanadia Road",
        "कनाडिया रोड" to "Kanadia Road",
        "kanadia road" to "Kanadia Road",
        "बिचौली मर्दाना" to "Bicholi Mardana",
        "bicholi mardana" to "Bicholi Mardana",
        "बिचौली हप्सी" to "Bicholi Hapsi",
        "bicholi hapsi" to "Bicholi Hapsi",
        "पिपलियाहाना" to "Pipliyahana",
        "pipliyahana" to "Pipliyahana",
        "नेहरू नगर" to "Nehru Nagar",
        "nehru nagar" to "Nehru Nagar",
        "गुमास्ता नगर" to "Gumasta Nagar",
        "gumasta nagar" to "Gumasta Nagar",
        "एयरपोर्ट रोड" to "Airport Road",
        "airport road" to "Airport Road",
        "एबी रोड" to "AB Road",
        "ab road" to "AB Road",
    )
    private val devanagari = Regex("[\\u0900-\\u097F]")
    private val latinPlace = Regex("[A-Za-z0-9][A-Za-z0-9 .,'&/()-]*")
    private val requestWords = Regex("(?i)(?:\\b(?:jana|jaana|chalna|chahiye|hh|book|ride|rapido)\\b|जाना|जानी|चलना|चाहिए|रैपिडो|बुला)")
    private val commonWordCorrections = mapOf(
        "roda" to "Road", "raod" to "Road", "rod" to "Road",
        "strret" to "Street", "streat" to "Street",
        "avnue" to "Avenue",
    )

    fun englishPlace(raw: String): String? {
        val place = raw.trim().replace(Regex("\\s+"), " ")
            .trim(' ', '.', '?', '।', ',', '!')
        if (place.length !in 3..80 || requestWords.containsMatchIn(place)) return null
        knownPlaces[place.lowercase(Locale.ROOT)]?.let { return it }
        val english = if (devanagari.containsMatchIn(place)) {
            try {
                Transliterator.getInstance("Devanagari-Latin; Latin-ASCII").transliterate(place)
            } catch (_: RuntimeException) {
                return null
            }
        } else place
        val query = english.trim().replace(Regex("\\s+"), " ")
            .split(' ').joinToString(" ") { commonWordCorrections[it.lowercase(Locale.ROOT)] ?: it }
        return query.takeIf { isEnglishPlace(it) }
    }

    fun isEnglishPlace(value: String): Boolean =
        value.length in 3..80 && latinPlace.matches(value) &&
            !requestWords.containsMatchIn(value)
}
