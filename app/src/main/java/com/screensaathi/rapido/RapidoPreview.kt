package com.screensaathi.rapido

import android.graphics.Rect
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot

/**
 * A Rapido-only state machine. It emits a booking action only after a fresh,
 * explicit spoken vehicle choice. Screen labels are evidence, not instructions.
 */
class RapidoPreview(val request: RapidoIntent) {
    enum class Stage { HOME, PICKUP, PICKUP_SEARCH, PICKUP_RESULTS, BOOKING_FOR, SEARCH, RESULTS, MANUAL_RESULT,
        VEHICLE, FARE, AWAIT_CHOICE, BOOK_SELECT, BOOK_READY, DONE }
    var stage = Stage.HOME
        private set
    private var destinationSelected = false
    private var homePickupAddress: String? = null
    private var quotedFares: Map<RapidoIntent.Vehicle, String> = emptyMap()
    private var chosenVehicle: RapidoIntent.Vehicle? = null
    private var actualCabLabel: String? = null
    private var unmatchedResultReads = 0
    private var manualNext: Stage? = null
    private var resultSignature: String? = null
    private var stableResultReads = 0
    private var homeReadsAfterTap = 0

    sealed class Decision {
        data object Wait : Decision()
        data class Stop(val reason: String) : Decision()
        data class Tap(val label: String, val bounds: Rect, val next: Stage) : Decision()
        data class Type(val bounds: Rect, val value: String, val next: Stage,
            val fieldId: String = "drop_text") : Decision()
        data class Preview(val message: String) : Decision()
        data class ManualSelection(val next: Stage, val ambiguous: Boolean = false) : Decision()
        data class Book(val vehicle: RapidoIntent.Vehicle, val actualLabel: String, val fare: String,
            val bounds: Rect) : Decision()
    }

    fun advanced(to: Stage) {
        if (stage == Stage.RESULTS && to == Stage.VEHICLE) destinationSelected = true
        if (to == Stage.RESULTS || to == Stage.PICKUP_RESULTS ||
            to == Stage.VEHICLE || to == Stage.BOOKING_FOR) unmatchedResultReads = 0
        if (to == Stage.RESULTS || to == Stage.PICKUP_RESULTS) {
            resultSignature = null
            stableResultReads = 0
        }
        stage = to
    }

    fun manualSelectionOnTimeout(): Decision.ManualSelection? = when (stage) {
        Stage.PICKUP -> Decision.ManualSelection(Stage.PICKUP)
        Stage.RESULTS, Stage.VEHICLE -> Decision.ManualSelection(Stage.VEHICLE)
        Stage.PICKUP_RESULTS, Stage.BOOKING_FOR -> Decision.ManualSelection(Stage.BOOKING_FOR)
        else -> null
    }

    fun awaitManualSelection(next: Stage) {
        require(stage == Stage.PICKUP || stage == Stage.RESULTS || stage == Stage.PICKUP_RESULTS ||
            stage == Stage.VEHICLE || stage == Stage.BOOKING_FOR)
        require(next == Stage.PICKUP || next == Stage.VEHICLE || next == Stage.BOOKING_FOR)
        manualNext = next
        stage = Stage.MANUAL_RESULT
    }

    /** Called only after the user spoke one unambiguous vehicle name. */
    fun choose(vehicle: RapidoIntent.Vehicle): Boolean {
        if (stage != Stage.AWAIT_CHOICE || quotedFares[vehicle] == null) return false
        chosenVehicle = vehicle
        stage = Stage.BOOK_SELECT
        return true
    }

    fun inspect(screen: ScreenSnapshot): Decision {
        if (screen.packageName != RAPIDO_PACKAGE || !screen.settled || screen.elements.isEmpty()) {
            return Decision.Wait
        }
        return when (stage) {
            Stage.HOME -> inspectHome(screen)
            Stage.PICKUP -> inspectPickup(screen)
            Stage.PICKUP_SEARCH -> inspectPickupSearch(screen)
            Stage.PICKUP_RESULTS -> inspectPickupResults(screen)
            Stage.BOOKING_FOR -> inspectBookingFor(screen)
            Stage.SEARCH -> inspectSearch(screen)
            Stage.RESULTS -> inspectResults(screen)
            Stage.MANUAL_RESULT -> inspectManualResult(screen)
            Stage.VEHICLE -> inspectVehicle(screen)
            Stage.FARE -> inspectFare(screen)
            Stage.AWAIT_CHOICE -> Decision.Wait
            Stage.BOOK_SELECT -> inspectBookSelect(screen)
            Stage.BOOK_READY -> inspectBookReady(screen)
            Stage.DONE -> Decision.Stop("Preview already completed")
        }
    }

    private fun inspectHome(screen: ScreenSnapshot): Decision {
        val fields = screen.elements.filter { e ->
            e.matchesAny("Where to?", "Where to", "Where do you want to go?", "Enter drop location", "Drop location",
                "Enter destination", "Search destination", "कहाँ जाना है", "कहां जाना है", "ड्रॉप लोकेशन")
        }.distinctBy { it.bounds }
        if (fields.size > 1) return Decision.Stop("More than one destination field is visible")
        val field = fields.singleOrNull() ?: return Decision.Wait
        if (request.pickupLocation == null) {
            val addresses = screen.elements.filter {
                it.className == "TextView" && it.bounds.bottom < field.bounds.top &&
                    it.text.count { c -> c == ',' } >= 2
            }.distinctBy { it.text }
            if (addresses.size != 1) return Decision.Stop("Cannot verify current pickup on Home")
            homePickupAddress = addresses.single().text
        }
        return if (field.editable) Decision.Stop("Unexpected editable Home destination")
        else Decision.Tap(field.text, field.bounds, Stage.PICKUP)
    }

    private fun inspectPickup(screen: ScreenSnapshot): Decision {
        if (screen.elements.none { it.text == "Pickup and Drop Screen" }) {
            // A completed accessibility gesture does not prove Rapido navigated.
            // Re-tapping Home can close the newly opening pickup/drop screen.
            // Ask for one manual tap if Home is still visible after settling.
            if (screen.elements.none { it.matchesAny("Where do you want to go?",
                    "Where to?", "Where to") }) {
                homeReadsAfterTap = 0
                return Decision.Wait
            }
            if (++homeReadsAfterTap < 10) return Decision.Wait
            homeReadsAfterTap = 0
            return Decision.ManualSelection(Stage.PICKUP)
        }
        homeReadsAfterTap = 0
        val pickup = screen.elements.filter { it.resourceId == "pickup_text" }
        if (pickup.size != 1) return Decision.Stop("Cannot identify pickup field")
        val field = pickup.single()
        val selected = field.text.removePrefix("Pickup Location is ").removeSuffix(". Double tap to change")
        if (request.pickupLocation == null) {
            if (normalize(selected) != normalize(homePickupAddress.orEmpty())) {
                return Decision.Stop("Current pickup differs from Rapido Home")
            }
            stage = Stage.SEARCH
            return inspectSearch(screen)
        }
        if (!field.text.startsWith("Pickup Location is ")) return Decision.Wait
        return Decision.Tap(field.text, field.bounds, Stage.PICKUP_SEARCH)
    }

    private fun inspectPickupSearch(screen: ScreenSnapshot): Decision {
        if (screen.elements.none { it.text == "PICKUP location Select on map" }) return Decision.Wait
        val place = request.pickupLocation ?: return Decision.Stop("Alternate pickup is missing")
        val fields = screen.elements.filter {
            it.resourceId == "pickup_text" &&
                (it.text.startsWith("Pickup Location is ") ||
                    it.text.startsWith("Enter Pickup location Input Field."))
        }
        if (fields.size != 1) return Decision.Stop("Cannot identify Pickup input")
        return Decision.Type(fields.single().bounds, place, Stage.PICKUP_RESULTS, "pickup_text")
    }

    private fun inspectPickupResults(screen: ScreenSnapshot): Decision {
        // A person may tap a result while we are still reading the list.
        if (screen.elements.any { it.resourceId == "bfse_title" &&
                it.text == "Booking for someone else?" }) {
            stage = Stage.BOOKING_FOR
            return inspectBookingFor(screen)
        }
        val place = request.pickupLocation ?: return Decision.Stop("Alternate pickup is missing")
        return inspectExactLocationResult(screen, "pickup_text", "Pickup Location is ", place,
            Stage.BOOKING_FOR)
    }

    private fun inspectBookingFor(screen: ScreenSnapshot): Decision {
        if (screen.elements.none { it.resourceId == "bfse_title" && it.text == "Booking for someone else?" }) {
            return if (screen.elements.any { it.text == "PICKUP location Select on map" } &&
                screen.elements.any { it.text.matches(Regex("Item \\d+ of \\d+")) }) {
                unmatchedResult(Stage.BOOKING_FOR)
            } else Decision.Wait
        }
        val choice = screen.elements.filter { it.text == "No, booking for me" }.distinctBy { it.bounds }
        if (choice.size != 1) return Decision.Stop("Cannot identify booking-for-self option")
        return Decision.Tap(choice.single().text, choice.single().bounds, Stage.SEARCH)
    }

    private fun inspectSearch(screen: ScreenSnapshot): Decision {
        if (homePickupAddress != null || request.pickupLocation != null) {
            val expectedPickup = request.pickupLocation ?: homePickupAddress.orEmpty()
            val actualPickup = screen.elements.singleOrNull { it.resourceId == "pickup_text" }
                ?.text.orEmpty()
            if (!normalize(actualPickup).startsWith(normalize("Pickup Location is $expectedPickup"))) {
                return Decision.Wait
            }
        }
        val rapidoDrop = screen.elements.filter {
            it.resourceId == "drop_text" &&
                it.text.startsWith("Enter Drop location Input Field.", ignoreCase = true)
        }
        if (rapidoDrop.size > 1) return Decision.Stop("Several Drop inputs are visible")
        rapidoDrop.singleOrNull()?.let { field ->
            if (screen.elements.none { it.text.startsWith("Pickup Location is ") }) {
                return Decision.Stop("Pickup location is not set")
            }
            return Decision.Type(field.bounds, request.destination, Stage.RESULTS)
        }
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
        // Accept a manual row tap even before we have asked for help.
        if (screen.elements.any { it.text == "Choose your ride Screen" }) {
            destinationSelected = true
            stage = Stage.FARE
            return inspectFare(screen)
        }
        val wanted = normalize(request.destination)
        val queryShown = screen.elements.any {
            it.resourceId == "drop_text" && normalize(it.text).contains("drop location is $wanted")
        }
        if (queryShown) {
            return inspectExactLocationResult(screen, "drop_text", "Drop Location is ",
                request.destination, Stage.VEHICLE)
        }
        // A title alone cannot establish which city Rapido will select.
        return if (screen.elements.any { it.text.matches(RESULT_ROW_LABEL) })
            unmatchedResult(Stage.VEHICLE) else Decision.Wait
    }

    private fun inspectExactLocationResult(screen: ScreenSnapshot, fieldId: String,
        prefix: String, place: String, next: Stage): Decision {
        val queryShown = screen.elements.any {
            it.resourceId == fieldId && normalize(it.text).startsWith(normalize(prefix + place))
        }
        if (!queryShown) return Decision.Wait
        val rows = screen.elements.filter { it.text.matches(RESULT_ROW_LABEL) }
            .sortedBy { it.text.substringAfter("Item ").substringBefore(" of").toIntOrNull() ?: Int.MAX_VALUE }
        val wanted = normalizeTitle(place)
        val candidates = rows.map { row ->
            val lines = screen.elements.filter {
                it.className == "TextView" && it.text.isNotBlank() &&
                    containsCenter(row.bounds, it.bounds)
            }.sortedWith(compareBy<ScreenElement> { it.bounds.top }.thenBy { it.bounds.left })
            val title = lines.firstOrNull()
            val address = title?.let { heading -> lines.firstOrNull {
                it.bounds.top >= heading.bounds.bottom &&
                    it.bounds.left >= heading.bounds.left - 60
            } }
            Triple(row, title, address)
        }
        val exact = candidates.mapNotNull { (row, title, address) ->
            if (title == null || address == null || !INDORE_ADDRESS.containsMatchIn(address.text)) {
                return@mapNotNull null
            }
            if (normalizeTitle(title.text) == wanted) LocationResult(row, title, address) else null
        }
        if (exact.isEmpty()) return if (rows.isEmpty()) Decision.Wait else unmatchedResult(next)
        // Include every visible row in the stability check so a second exact
        // title loading into a placeholder cannot be overlooked.
        val signature = rows.joinToString("|") { row ->
            val candidate = candidates[rows.indexOf(row)]
            "${row.text}:${candidate.second?.text}:${candidate.third?.text}:" +
                "${candidate.second?.bounds?.toShortString()}"
        }
        if (resultSignature != signature) {
            resultSignature = signature
            stableResultReads = 1
            return Decision.Wait
        }
        if (++stableResultReads < 2) return Decision.Wait
        if (exact.size > 1) return Decision.ManualSelection(next, ambiguous = true)
        val chosen = exact.single()
        // Rapido briefly inserts unnamed placeholder rows while its search
        // results load. A match below one can shift upward before a gesture
        // arrives, causing the tap to hit a different row.
        val chosenIndex = rows.indexOf(chosen.row)
        if (candidates.take(chosenIndex).any { it.second == null || it.third == null }) return Decision.Wait
        unmatchedResultReads = 0
        // Tap the title itself: a row-centre gesture can land in blank space
        // on Rapido's search-result card without opening that location.
        return Decision.Tap(chosen.title.text, chosen.title.bounds, next)
    }

    private data class LocationResult(
        val row: ScreenElement,
        val title: ScreenElement,
        val address: ScreenElement,
    )

    private fun unmatchedResult(next: Stage, ambiguous: Boolean = false): Decision {
        unmatchedResultReads++
        val limit = if (stage == Stage.VEHICLE || stage == Stage.BOOKING_FOR) 12 else 5
        return if (unmatchedResultReads >= limit) Decision.ManualSelection(next, ambiguous) else Decision.Wait
    }

    private fun inspectManualResult(screen: ScreenSnapshot): Decision = when (manualNext) {
        Stage.PICKUP -> {
            if (screen.elements.none { it.text == "Pickup and Drop Screen" }) Decision.Wait
            else {
                stage = Stage.PICKUP
                inspectPickup(screen)
            }
        }
        Stage.BOOKING_FOR -> {
            if (screen.elements.none { it.resourceId == "bfse_title" && it.text == "Booking for someone else?" }) {
                Decision.Wait
            } else {
                stage = Stage.BOOKING_FOR
                inspectBookingFor(screen)
            }
        }
        Stage.VEHICLE -> {
            if (screen.elements.none { it.text == "Choose your ride Screen" }) Decision.Wait
            else {
                // The human picked a Rapido row after we requested help.
                destinationSelected = true
                stage = Stage.FARE
                inspectFare(screen)
            }
        }
        else -> Decision.Stop("Manual result stage has no target")
    }

    private fun inspectVehicle(screen: ScreenSnapshot): Decision {
        if (screen.elements.none { it.text == "Choose your ride Screen" }) {
            return if (screen.elements.any { it.resourceId == "drop_text" } &&
                screen.elements.any { it.text.matches(Regex("Item \\d+ of \\d+")) }) {
                unmatchedResult(Stage.VEHICLE)
            } else Decision.Wait
        }
        stage = Stage.FARE
        return inspectFare(screen)
    }

    private fun inspectFare(screen: ScreenSnapshot): Decision {
        if (!destinationSelected) return Decision.Stop("Destination was not selected by this flow")
        if (screen.elements.none { it.text == "Choose your ride Screen" }) return Decision.Wait
        actualCabLabel = when {
            fareRowByLabel(screen, "Cab Daily") != null -> "Cab Daily"
            fareRowByLabel(screen, "Cab Economy") != null -> "Cab Economy"
            else -> return Decision.Wait
        }
        val fares = LinkedHashMap<RapidoIntent.Vehicle, String>()
        for (vehicle in RapidoIntent.Vehicle.entries) {
            val row = fareRow(screen, vehicle) ?: return Decision.Wait
            val fare = singleFare(row.text) ?: return Decision.Stop("Unclear ${vehicle.label} fare")
            fares[vehicle] = fare
        }
        if (screen.elements.count { it.resourceId == "fe_book_now_btn" } != 1) return Decision.Wait
        quotedFares = fares
        stage = Stage.AWAIT_CHOICE
        return Decision.Preview("रैपिडो में बाइक का किराया ${fares[RapidoIntent.Vehicle.BIKE]}, " +
            "ऑटो का किराया ${fares[RapidoIntent.Vehicle.AUTO]}, और कैब का किराया ${fares[RapidoIntent.Vehicle.CAB]} है। " +
            "आप कौन सी बुक करना चाहती हैं? बाइक, ऑटो या कैब बोलें।")
    }

    private fun inspectBookSelect(screen: ScreenSnapshot): Decision {
        if (!destinationSelected || screen.elements.none { it.text == "Choose your ride Screen" }) return Decision.Wait
        val vehicle = chosenVehicle ?: return Decision.Stop("No spoken vehicle choice")
        val row = fareRow(screen, vehicle) ?: return Decision.Wait
        if (singleFare(row.text) != quotedFares[vehicle]) return Decision.Stop("Fare changed after quote")
        if (row.text.endsWith(".Selected.")) {
            stage = Stage.BOOK_READY
            return inspectBookReady(screen)
        }
        return Decision.Tap(row.text, row.bounds, Stage.BOOK_READY)
    }

    private fun inspectBookReady(screen: ScreenSnapshot): Decision {
        if (!destinationSelected || screen.elements.none { it.text == "Choose your ride Screen" }) return Decision.Wait
        val vehicle = chosenVehicle ?: return Decision.Stop("No spoken vehicle choice")
        val row = fareRow(screen, vehicle) ?: return Decision.Wait
        val fare = singleFare(row.text) ?: return Decision.Stop("Selected fare is unclear")
        if (fare != quotedFares[vehicle]) return Decision.Stop("Fare changed after quote")
        if (!row.text.endsWith(".Selected.")) return Decision.Wait
        val actualLabel = labelFor(vehicle)
        val button = screen.elements.filter {
            it.resourceId == "fe_book_now_btn" && it.text == "Double tap to book $actualLabel"
        }
        if (button.size != 1) return Decision.Wait
        return Decision.Book(vehicle, actualLabel, fare, button.single().bounds)
    }

    private fun labelFor(vehicle: RapidoIntent.Vehicle): String =
        if (vehicle == RapidoIntent.Vehicle.CAB) actualCabLabel ?: "Cab Daily" else vehicle.label

    private fun fareRow(screen: ScreenSnapshot, vehicle: RapidoIntent.Vehicle): ScreenElement? =
        fareRowByLabel(screen, labelFor(vehicle))

    private fun fareRowByLabel(screen: ScreenSnapshot, label: String): ScreenElement? {
        val rows = screen.elements.filter {
            it.text.startsWith("$label ride. Estimated fare ") &&
                (it.text.endsWith(".Selected.") || it.text.endsWith(".Not selected."))
        }.distinctBy { it.bounds }
        if (rows.size != 1) return null
        val row = rows.single()
        return row.takeIf { screen.elements.any {
            it.resourceId == "fe_list_item_$label" && sameBounds(it.bounds, row.bounds)
        } }
    }

    private fun singleFare(text: String): String? =
        FARE.findAll(text).map { it.value }.distinct().toList().singleOrNull()

    private fun ScreenElement.matchesAny(vararg labels: String): Boolean =
        labels.any { normalize(text) == normalize(it) }

    private fun normalize(text: String): String = text.trim().lowercase()
        .replace(Regex("[?।]"), "").replace(Regex("\\s+"), " ")
    private fun normalizeTitle(text: String): String = text.trim().lowercase()
        .replace(Regex("\\s+"), " ")

    private fun containsCenter(outer: Rect, inner: Rect): Boolean {
        val x = inner.left + (inner.right - inner.left) / 2
        val y = inner.top + (inner.bottom - inner.top) / 2
        return x >= outer.left && x < outer.right && y >= outer.top && y < outer.bottom
    }

    private fun sameBounds(a: Rect, b: Rect): Boolean =
        a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom

    companion object {
        const val RAPIDO_PACKAGE = "com.rapido.passenger"
        private val FARE = Regex("₹\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
        private val RESULT_ROW_LABEL = Regex("Item \\d+ of \\d+")
        private val INDORE_ADDRESS = Regex("(?:^|,\\s*)Indore\\s*,\\s*(?:Madhya Pradesh|MP)(?:\\b|$)",
            RegexOption.IGNORE_CASE)
    }
}
