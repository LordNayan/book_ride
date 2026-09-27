package com.screensaathi.rapido

/** A deliberately small vocabulary for the first ride preview. */
data class RapidoIntent(val destination: String, val vehicle: Vehicle) {
    enum class Vehicle(val label: String) { AUTO("Auto"), BIKE("Bike"), CAB("Cab") }

    companion object {
        fun parse(spoken: String): RapidoIntent? {
            val request = spoken.trim().replace(Regex("\\s+"), " ")
            if (request.isEmpty()) return null
            val lower = request.lowercase()
            val rideWords = Regex("rapido|रैपिडो|जाना|जानी|चलना|चाहिए|chahiye|jana|ride|auto|bike|cab")
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
                .replace(Regex("(?i)(?: जाना है| जाना| जानी है| चलना है| जाना चाहती हूँ| jaana hai| jana hai| जाना चाहता हूँ| जाना चाहिये| ride chahiye| के लिए)$"), "")
                .trim(' ', '.', '?', '।', ',', '!', 'ँ')
            if (destination.length < 3 || destination.length > 80) return null
            if (Regex("(?i)^(auto|bike|cab|taxi|कार|ऑटो|बाइक|कैब)$").matches(destination)) return null
            if (Regex("(?i)^(auto|bike|cab|taxi|ऑटो|बाइक|कैब).*(बुला दो|book|chahiye|चाहिए)$")
                    .matches(destination)) return null
            return RapidoIntent(destination, vehicle)
        }
    }
}
