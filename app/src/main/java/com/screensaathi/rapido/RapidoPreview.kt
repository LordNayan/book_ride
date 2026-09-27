package com.screensaathi.rapido

import android.graphics.Rect
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot

/**
 * A fail-closed Rapido-only state machine. It never emits a booking or payment
 * action. Screen labels are evidence, not instructions from the app.
 */
class RapidoPreview(val request: RapidoIntent) {
    enum class Stage { HOME, SEARCH, RESULTS, VEHICLE, FARE, DONE }
    var stage = Stage.HOME
        private set

    sealed class Decision {
        data object Wait : Decision()
        data class Stop(val reason: String) : Decision()
        data class Tap(val label: String, val next: Stage) : Decision()
        data class Type(val bounds: Rect, val value: String, val next: Stage) : Decision()
        data class Preview(val message: String) : Decision()
    }

    fun advanced(to: Stage) { stage = to }

    fun inspect(screen: ScreenSnapshot): Decision {
        if (screen.packageName != RAPIDO_PACKAGE || !screen.settled || screen.elements.isEmpty()) {
            return Decision.Wait
        }
        return when (stage) {
            Stage.HOME -> inspectHome(screen)
            Stage.SEARCH -> inspectSearch(screen)
            Stage.RESULTS -> inspectResults(screen)
            Stage.VEHICLE -> inspectVehicle(screen)
            Stage.FARE -> inspectFare(screen)
            Stage.DONE -> Decision.Stop("Preview already completed")
        }
    }

    private fun inspectHome(screen: ScreenSnapshot): Decision {
        val fields = screen.elements.filter { e ->
            e.matchesAny("Where to?", "Where to", "Enter drop location", "Drop location",
                "Enter destination", "Search destination", "कहाँ जाना है", "कहां जाना है", "ड्रॉप लोकेशन")
        }.distinctBy { it.text.lowercase() }
        if (fields.size > 1) return Decision.Stop("More than one destination field is visible")
        val field = fields.singleOrNull() ?: return Decision.Wait
        return if (field.editable) Decision.Type(field.bounds, request.destination, Stage.RESULTS)
        else Decision.Tap(field.text, Stage.SEARCH)
    }

    private fun inspectSearch(screen: ScreenSnapshot): Decision {
        val fields = screen.elements.filter { it.editable }
        val destinationFields = fields.filter { it.matchesAny(
            "Where to?", "Where to", "Enter drop location", "Drop location",
            "Enter destination", "Search destination", "कहाँ जाना है", "कहां जाना है", "ड्रॉप लोकेशन") }
        val chosen = when {
            destinationFields.size == 1 -> destinationFields.single()
            destinationFields.isEmpty() && fields.size == 1 -> fields.single()
            fields.isEmpty() -> return Decision.Wait
            else -> return Decision.Stop("Cannot identify one destination input")
        }
        return Decision.Type(chosen.bounds, request.destination, Stage.RESULTS)
    }

    private fun inspectResults(screen: ScreenSnapshot): Decision {
        val wanted = normalize(request.destination)
        val results = screen.elements.filter { e ->
            !e.editable && e.text.isNotBlank() &&
                normalize(e.text).startsWith("$wanted,")
        }.distinctBy { normalize(it.text) }
        if (results.size > 1) return Decision.Stop("Several destinations match; choose one in Rapido")
        val result = results.singleOrNull() ?: return Decision.Wait
        return Decision.Tap(result.text, Stage.VEHICLE)
    }

    private fun inspectVehicle(screen: ScreenSnapshot): Decision {
        val name = request.vehicle.label
        val matches = screen.elements.filter { e ->
            val t = normalize(e.text)
            t == name.lowercase() || t == "rapido ${name.lowercase()}" ||
                t == "${name.lowercase()} ride"
        }.distinctBy { normalize(it.text) }
        if (matches.size > 1) return Decision.Stop("Several $name options are visible")
        val tile = matches.singleOrNull() ?: return Decision.Wait
        return Decision.Tap(tile.text, Stage.FARE)
    }

    private fun inspectFare(screen: ScreenSnapshot): Decision {
        val labels = screen.elements.map { it.text }.filter { it.isNotBlank() }
        val destinationShown = labels.any { normalize(it).contains(normalize(request.destination)) }
        val vehicleShown = labels.any { normalize(it) == request.vehicle.label.lowercase() ||
            normalize(it) == "rapido ${request.vehicle.label.lowercase()}" }
        val bookShown = labels.any { Regex("(?i)\\bbook\\b|बुक").containsMatchIn(it) }
        if (!destinationShown || !vehicleShown || !bookShown) return Decision.Wait
        val prices = labels.flatMap { FARE.findAll(it).map { m -> m.value }.toList() }.distinct()
        if (prices.size > 1) return Decision.Stop("Several fares are visible; please check Rapido")
        val fare = prices.singleOrNull() ?: return Decision.Wait
        stage = Stage.DONE
        return Decision.Preview(
            "रैपिडो में ${request.destination} के लिए ${request.vehicle.label} का किराया $fare दिख रहा है। " +
                "पिकअप पिन और पूरी जगह ऐप में जाँच लें। मैंने बुकिंग नहीं की है।"
        )
    }

    private fun ScreenElement.matchesAny(vararg labels: String): Boolean =
        labels.any { normalize(text) == normalize(it) }

    private fun normalize(text: String): String = text.trim().lowercase()
        .replace(Regex("[?।]"), "").replace(Regex("\\s+"), " ")

    companion object {
        const val RAPIDO_PACKAGE = "com.rapido.passenger"
        private val FARE = Regex("₹\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
    }
}
