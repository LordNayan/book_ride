package com.screensaathi.rapido

/** A deliberately small vocabulary for the first ride preview. */
data class RapidoIntent(
    val destination: String,
    val vehicle: Vehicle,
    /** Null means the current pickup shown on Rapido Home. */
    val pickupLocation: String? = null,
) {
    enum class Vehicle(val label: String, val spokenName: String) {
        AUTO("Auto", "ऑटो"), BIKE("Bike", "बाइक"), CAB("Cab Daily", "कैब")
    }
    enum class PickupChoice { CURRENT, OTHER }

    companion object {
        fun bookingVehicle(spoken: String): Vehicle? {
            val answer = spoken.lowercase()
            val matches = listOfNotNull(
                Vehicle.BIKE.takeIf { Regex("\\b(bike|motorcycle)\\b|बाइक|मोटरसाइकिल").containsMatchIn(answer) },
                Vehicle.AUTO.takeIf { Regex("\\bauto\\b|ऑटो").containsMatchIn(answer) },
                Vehicle.CAB.takeIf { Regex("\\b(cab|taxi|car)\\b|कैब|टैक्सी|कार").containsMatchIn(answer) },
            )
            return matches.singleOrNull()
        }

        /** An explicit spoken "yes"/"no" to a book/cancel question. Null if unclear. */
        fun confirmation(spoken: String): Boolean? {
            val answer = spoken.trim().lowercase()
            val yes = Regex("हाँ|हां|जी हाँ|जी हां|ठीक है|बुक कर दो|yes|haan|theek hai|book kar do")
            val no = Regex("नहीं|नहि|no|nahi|nahin")
            return when {
                yes.containsMatchIn(answer) && !no.containsMatchIn(answer) -> true
                no.containsMatchIn(answer) && !yes.containsMatchIn(answer) -> false
                else -> null
            }
        }

        /** An explicit spoken request to abandon the ride flow, at any prompt. */
        fun isCancel(spoken: String): Boolean {
            val answer = spoken.trim().lowercase()
            return Regex("रुको|रुक जाओ|कैंसल|कैंसिल|रद्द|बंद करो|छोड़ो|रहने दो|cancel|stop|ruko")
                .containsMatchIn(answer)
        }

        fun pickupChoice(spoken: String): PickupChoice? {
            val answer = spoken.trim().lowercase()
            val current = Regex("यहीं|यही|वर्तमान|अभी की|मेरी लोकेशन|मौजूदा|करंट|current|here|इसी जगह")
            val other = Regex("दूसरी|दूसरा|दूसरे|अलग|कहीं और|अन्य|other|another|different")
            return when {
                current.containsMatchIn(answer) && !other.containsMatchIn(answer) -> PickupChoice.CURRENT
                other.containsMatchIn(answer) && !current.containsMatchIn(answer) -> PickupChoice.OTHER
                else -> null
            }
        }

        fun pickupPlace(spoken: String): String? {
            val place = spoken.trim().replace(Regex("(?i)^(?:(?:पिकअप|मुझे|मेरे लिए) |pickup (?:from )?)+"), "")
                .replace(Regex("(?i)(?: से(?: लेना| चलना| पिकअप)?| se| from)$"), "")
                .trim(' ', '.', '?', '।', ',', '!')
            if (pickupChoice(place) != null) return null
            return PlaceNameNormalizer.englishPlace(place)
        }

        fun parse(spoken: String): RapidoIntent? {
            val request = spoken.trim().replace(Regex("\\s+"), " ")
            if (request.isEmpty()) return null
            val lower = request.lowercase()
            val rideWords = Regex("rapido|रैपिडो|जाना|जानी|जना|चलना|चाहिए|chahiye|jana|ride|auto|bike|cab")
            if (!rideWords.containsMatchIn(lower) && request.split(' ').size > 3) return null

            val vehicle = when {
                Regex("\\b(bike|motorcycle)\\b|बाइक|मोटरसाइकिल").containsMatchIn(lower) -> Vehicle.BIKE
                Regex("\\b(cab|taxi|car)\\b|कैब|टैक्सी|कार").containsMatchIn(lower) -> Vehicle.CAB
                else -> Vehicle.AUTO
            }
            val destination = request
                .replace(Regex("(?i)^(?:मुझे |मेरे लिए |please |rapido (?:par|pe|में) |रैपिडो (?:पर|पे|में) )+"), "")
                .replace(Regex("(?i)^(?:to |के लिए )"), "")
                .replace(Regex("(?i)(?: के लिए| ke liye)? (?:auto|bike|cab|taxi|car|ऑटो|बाइक|कैब|टैक्सी|कार)(?: चाहिए| chahiye| बुला दो| book (?:kar do|karo|please))?$"), "")
                .replace(Regex("(?i)(?: जाना चाहती हूँ| जाना चाहता हूँ| जाना चाहिये| (?:जाना|जानी|जना|चलना)(?: (?:है|हे|हैं|hai|hh|h|he))?| (?:jana|jaana|chalna)(?: (?:hai|hh|h|he))?| ride chahiye| के लिए)$"), "")
                .trim(' ', '.', '?', '।', ',', '!', 'ँ')
            val englishDestination = PlaceNameNormalizer.englishPlace(destination) ?: return null
            if (Regex("(?i)^(auto|bike|cab|taxi|car)$").matches(englishDestination)) return null
            return RapidoIntent(englishDestination, vehicle)
        }
    }
}
