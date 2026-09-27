package com.screensaathi.rapido

import android.graphics.Rect
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidoPreviewTest {
    @Test fun `extracts a Hindi destination and defaults to auto`() {
        assertEquals(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("राजवाड़ा जाना है"))
        assertEquals(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("रजवाड़ा जना है"))
        assertEquals(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("Rapido par Rajwada ke liye auto chahiye"))
        assertEquals(RapidoIntent("Vijay Nagar", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("विजयनगर जाना है"))
        assertEquals(RapidoIntent("Vijay Nagar", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("vijaynagar jana hh"))
        assertEquals(RapidoIntent("Vijay Nagar", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("विजयनगर जाना hh"))
        assertEquals(RapidoIntent("Vijay Nagar", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("मुझे विजय नगर जाना है"))
        assertEquals(null, RapidoIntent.parse("विजयनगर जाना है जल्दी"))
        assertFalse(PlaceNameNormalizer.isEnglishPlace("विजयनगर"))
        assertFalse(PlaceNameNormalizer.isEnglishPlace("vijaynagar jana hh"))
        assertTrue(PlaceNameNormalizer.isEnglishPlace("Vijay Nagar"))
        assertEquals("MG Road", PlaceNameNormalizer.englishPlace("MG roda"))
        assertEquals(RapidoIntent("MG Road", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("MG roda jana hh"))
        assertEquals(null, RapidoIntent.parse("आज मौसम कैसा है"))
    }

    @Test fun `understands the Hindi pickup choice and place`() {
        assertEquals(RapidoIntent.PickupChoice.CURRENT, RapidoIntent.pickupChoice("यहीं से"))
        assertEquals(RapidoIntent.PickupChoice.OTHER, RapidoIntent.pickupChoice("दूसरी जगह से"))
        assertEquals(null, RapidoIntent.pickupChoice("हाँ"))
        assertEquals("Palasia", RapidoIntent.pickupPlace("पलासिया से"))
        assertEquals("Vijay Nagar", RapidoIntent.pickupPlace("विजय नगर से"))
        assertEquals(RapidoIntent.Vehicle.BIKE, RapidoIntent.bookingVehicle("बाइक"))
        assertEquals(RapidoIntent.Vehicle.AUTO, RapidoIntent.bookingVehicle("ऑटो"))
        assertEquals(RapidoIntent.Vehicle.CAB, RapidoIntent.bookingVehicle("कैब"))
        assertEquals(null, RapidoIntent.bookingVehicle("बाइक या ऑटो"))
    }

    @Test fun `unmatched place results request manual selection and resume at fares`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        val choices = screen(element("Rajwada, Indore"), element("Rajwada, Ujjain"))
        repeat(4) { assertEquals(RapidoPreview.Decision.Wait, flow.inspect(choices)) }
        val decision = flow.inspect(choices)
        assertTrue(decision is RapidoPreview.Decision.ManualSelection)
        flow.awaitManualSelection((decision as RapidoPreview.Decision.ManualSelection).next)
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(choices))
        assertTrue(flow.inspect(fareScreen()) is RapidoPreview.Decision.Preview)
    }

    @Test fun `search query text alone is not a location result`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(screen(element("Rajwada"))))
    }

    @Test fun `tap that leaves Rapido on results asks for a manual selection`() {
        val flow = RapidoPreview(RapidoIntent("Vijay Nagar", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.VEHICLE)
        val stillSearching = screen(
            element("Pickup and Drop Screen"),
            ScreenElement(1, "drop_text", "Drop Location is Vijay Nagar. Double tap to change",
                "View", rect(178, 429, 1000, 521), false, false),
            element("Item 1 of 5"))
        repeat(11) { assertEquals(RapidoPreview.Decision.Wait, flow.inspect(stillSearching)) }
        val manual = flow.inspect(stillSearching)
        assertTrue(manual is RapidoPreview.Decision.ManualSelection)
        flow.awaitManualSelection((manual as RapidoPreview.Decision.ManualSelection).next)
        assertTrue(flow.inspect(fareScreen()) is RapidoPreview.Decision.Preview)
    }

    @Test fun `fare screen without a selected destination cannot be quoted`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.FARE)
        assertTrue(flow.inspect(fareScreen()) is RapidoPreview.Decision.Stop)
        assertFalse(flow.choose(RapidoIntent.Vehicle.AUTO))
    }

    @Test fun `other app cannot drive a Rapido action`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        assertEquals(RapidoPreview.Decision.Wait,
            flow.inspect(screen(element("Where to?"), pkg = "com.other.app")))
    }

    @Test fun `current pickup quotes three fares and books only after a vehicle choice`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        val address = "51, Manik Bagh Rd, Indore, Madhya Pradesh, India"
        val home = flow.inspect(screen(
            element("Home Screen"),
            ScreenElement(1, "", address, "TextView", rect(140, 220, 1010, 280), false, false),
            ScreenElement(2, "", "Where do you want to go?", "Button", rect(55, 473, 1025, 570), false, false)))
        assertTrue(home is RapidoPreview.Decision.Tap)
        flow.advanced((home as RapidoPreview.Decision.Tap).next)
        val pickup = ScreenElement(1, "pickup_text", "Pickup Location is $address. Double tap to change",
            "View", rect(178, 294, 1000, 386), false, false)
        val drop = ScreenElement(2, "drop_text", "Enter Drop location Input Field. Enter at least 4 characters to start searching.",
            "View", rect(178, 429, 1000, 521), false, false)
        val search = flow.inspect(screen(element("Pickup and Drop Screen"), pickup, drop))
        assertTrue(search is RapidoPreview.Decision.Type)
        flow.advanced((search as RapidoPreview.Decision.Type).next)
        val resultScreen = screen(
            element("Pickup and Drop Screen"), pickup,
            drop.copy(text = "Drop Location is Rajwada. Double tap to change"),
            ScreenElement(3, "", "Item 1 of 5", "View", rect(0, 694, 1080, 878), false, false),
            ScreenElement(4, "", "Rajwada", "TextView", rect(120, 729, 312, 786), false, false),
            ScreenElement(5, "", "Rajwada Palace", "TextView", rect(184, 916, 537, 973), false, false))
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(resultScreen))
        val result = flow.inspect(resultScreen)
        assertTrue(result is RapidoPreview.Decision.Tap)
        val tap = result as RapidoPreview.Decision.Tap
        assertEquals("Rajwada", tap.label)
        assertEquals(120, tap.bounds.left)
        assertEquals(729, tap.bounds.top)
        flow.advanced(result.next)
        val quote = flow.inspect(fareScreen())
        assertTrue(quote is RapidoPreview.Decision.Preview)
        val message = (quote as RapidoPreview.Decision.Preview).message
        assertTrue(message.contains("₹34") && message.contains("₹61") && message.contains("₹95"))
        assertEquals(RapidoPreview.Stage.AWAIT_CHOICE, flow.stage)
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(fareScreen()))
        assertTrue(flow.choose(RapidoIntent.Vehicle.AUTO))
        val select = flow.inspect(fareScreen())
        assertTrue(select is RapidoPreview.Decision.Tap)
        flow.advanced((select as RapidoPreview.Decision.Tap).next)
        val book = flow.inspect(fareScreen(selected = RapidoIntent.Vehicle.AUTO))
        assertTrue(book is RapidoPreview.Decision.Book)
        assertEquals(RapidoIntent.Vehicle.AUTO, (book as RapidoPreview.Decision.Book).vehicle)
        assertEquals("₹61", book.fare)
    }

    @Test fun `changed fare blocks the booking action`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        flow.advanced(RapidoPreview.Stage.VEHICLE)
        assertTrue(flow.inspect(fareScreen()) is RapidoPreview.Decision.Preview)
        assertTrue(flow.choose(RapidoIntent.Vehicle.AUTO))
        assertTrue(flow.inspect(fareScreen(autoFare = "₹89")) is RapidoPreview.Decision.Stop)
    }

    @Test fun `Cab Economy is used when Cab Daily is unavailable`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        flow.advanced(RapidoPreview.Stage.VEHICLE)
        assertTrue(flow.inspect(fareScreen(cabLabel = "Cab Economy", cabFare = "₹125"))
            is RapidoPreview.Decision.Preview)
        assertTrue(flow.choose(RapidoIntent.Vehicle.CAB))
        val select = flow.inspect(fareScreen(cabLabel = "Cab Economy", cabFare = "₹125"))
        assertTrue(select is RapidoPreview.Decision.Tap)
        flow.advanced((select as RapidoPreview.Decision.Tap).next)
        val book = flow.inspect(fareScreen(selected = RapidoIntent.Vehicle.CAB,
            cabLabel = "Cab Economy", cabFare = "₹125"))
        assertEquals("Cab Economy", (book as RapidoPreview.Decision.Book).actualLabel)
    }

    @Test fun `first equally matched Rapido row wins`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        val resultScreen = screen(
            ScreenElement(0, "drop_text", "Drop Location is Rajwada. Double tap to change",
                "View", rect(178, 429, 1000, 521), false, false),
            ScreenElement(1, "", "Item 1 of 2", "View", rect(0, 694, 1080, 878), false, false),
            ScreenElement(2, "", "Rajwada", "TextView", rect(120, 729, 312, 786), false, false),
            ScreenElement(3, "", "Item 2 of 2", "View", rect(0, 881, 1080, 1065), false, false),
            ScreenElement(4, "", "Rajwada", "TextView", rect(120, 916, 312, 973), false, false))
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(resultScreen))
        val result = flow.inspect(resultScreen)
        val tap = result as RapidoPreview.Decision.Tap
        assertEquals("Rajwada", tap.label)
        assertEquals(120, tap.bounds.left)
        assertEquals(729, tap.bounds.top)
    }

    @Test fun `waits for Rapido placeholder row before selecting first result`() {
        val flow = RapidoPreview(RapidoIntent("Vijay Nagar", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        val field = ScreenElement(0, "drop_text", "Drop Location is Vijay Nagar. Double tap to change",
            "View", rect(178, 429, 1000, 521), false, false)
        val loading = screen(field,
            ScreenElement(1, "", "Item 1 of 7", "View", rect(0, 694, 1080, 878), false, false),
            ScreenElement(2, "", "Item 2 of 7", "View", rect(0, 881, 1080, 1065), false, false),
            ScreenElement(3, "", "Vijay Nagar", "TextView", rect(120, 916, 312, 973), false, false))
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(loading))
        val loaded = screen(field,
            ScreenElement(1, "", "Item 1 of 6", "View", rect(0, 694, 1080, 878), false, false),
            ScreenElement(2, "", "Vijay Nagar", "TextView", rect(120, 729, 312, 786), false, false),
            ScreenElement(3, "", "Item 2 of 6", "View", rect(0, 881, 1080, 1065), false, false),
            ScreenElement(4, "", "Vijay Nagar, Scheme No 54", "TextView",
                rect(120, 916, 540, 973), false, false))
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(loaded))
        val tap = flow.inspect(loaded) as RapidoPreview.Decision.Tap
        assertEquals("Vijay Nagar", tap.label)
        assertEquals(729, tap.bounds.top)
    }

    @Test fun `alternate pickup selects self before searching drop`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO, "Palasia"))
        val home = flow.inspect(screen(element("Home Screen"),
            ScreenElement(0, "", "Where do you want to go?", "Button", rect(55, 473, 1025, 570), false, false)))
        flow.advanced((home as RapidoPreview.Decision.Tap).next)
        val pickup = ScreenElement(1, "pickup_text", "Pickup Location is 51, Manik Bagh Rd, Indore. Double tap to change",
            "View", rect(178, 294, 1000, 386), false, false)
        val open = flow.inspect(screen(element("Pickup and Drop Screen"), pickup))
        assertTrue(open is RapidoPreview.Decision.Tap)
        flow.advanced((open as RapidoPreview.Decision.Tap).next)
        val type = flow.inspect(screen(element("PICKUP location Select on map"), pickup))
        assertTrue(type is RapidoPreview.Decision.Type)
        assertEquals("pickup_text", (type as RapidoPreview.Decision.Type).fieldId)
        flow.advanced(type.next)
        val resultScreen = screen(
            pickup.copy(text = "Pickup Location is Palasia. Double tap to change"),
            ScreenElement(2, "", "Item 1 of 5", "View", rect(0, 694, 1080, 878), false, false),
            ScreenElement(3, "", "Palasia", "TextView", rect(186, 729, 346, 786), false, false))
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(resultScreen))
        val result = flow.inspect(resultScreen)
        assertTrue(result is RapidoPreview.Decision.Tap)
        flow.advanced((result as RapidoPreview.Decision.Tap).next)
        val self = flow.inspect(screen(
            ScreenElement(4, "bfse_title", "Booking for someone else?", "TextView",
                rect(40, 1751, 731, 1816), false, false),
            ScreenElement(5, "", "Yes, for someone else", "TextView",
                rect(297, 2042, 783, 2099), false, false),
            ScreenElement(6, "", "No, booking for me", "TextView",
                rect(329, 2192, 751, 2249), false, false)))
        assertTrue(self is RapidoPreview.Decision.Tap)
        assertEquals("No, booking for me", (self as RapidoPreview.Decision.Tap).label)
        flow.advanced(self.next)
        val drop = flow.inspect(screen(element("Pickup and Drop Screen"),
            pickup.copy(text = "Pickup Location is Palasia, Indore, Madhya Pradesh, India. Double tap to change"),
            ScreenElement(7, "drop_text", "Enter Drop location Input Field.", "View",
                rect(178, 429, 1000, 521), false, false)))
        assertTrue(drop is RapidoPreview.Decision.Type)
    }

    private fun screen(vararg elements: ScreenElement, pkg: String = RapidoPreview.RAPIDO_PACKAGE) =
        ScreenSnapshot(pkg, true, elements.toList())

    private fun fareScreen(
        selected: RapidoIntent.Vehicle = RapidoIntent.Vehicle.BIKE,
        autoFare: String = "₹61",
        cabLabel: String = "Cab Daily",
        cabFare: String = "₹95",
    ): ScreenSnapshot {
        val rows = listOf(
            Triple(RapidoIntent.Vehicle.BIKE, "₹34", rect(0, 1209, 1080, 1470)),
            Triple(RapidoIntent.Vehicle.AUTO, autoFare, rect(0, 1470, 1080, 1620)),
            Triple(RapidoIntent.Vehicle.CAB, cabFare, rect(0, 1770, 1080, 1920)),
        )
        val elements = mutableListOf(element("Choose your ride Screen"))
        rows.forEachIndexed { index, (vehicle, fare, bounds) ->
            val label = if (vehicle == RapidoIntent.Vehicle.CAB) cabLabel else vehicle.label
            elements += ScreenElement(index + 1, "fe_list_item_$label", "", "View",
                bounds, false, false)
            elements += ScreenElement(index + 4, "", "$label ride. Estimated fare ${fare}in 1 min." +
                if (vehicle == selected) "Selected." else "Not selected.", "View", bounds, false, false)
        }
        val selectedLabel = if (selected == RapidoIntent.Vehicle.CAB) cabLabel else selected.label
        elements += ScreenElement(8, "fe_book_now_btn", "Double tap to book $selectedLabel",
            "View", rect(40, 2180, 1040, 2300), false, false)
        return screen(*elements.toTypedArray())
    }

    private fun element(text: String) = ScreenElement(0, "", text, "TextView",
        rect(0, 0, 100, 50), false, true)

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
