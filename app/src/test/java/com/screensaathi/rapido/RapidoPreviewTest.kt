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
        assertEquals(RapidoIntent("राजवाड़ा", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("राजवाड़ा जाना है"))
        assertEquals(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO),
            RapidoIntent.parse("Rapido par Rajwada ke liye auto chahiye"))
        assertEquals(null, RapidoIntent.parse("आज मौसम कैसा है"))
    }

    @Test fun `ambiguous place results stop before selection`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        val decision = flow.inspect(screen(
            element("Rajwada, Indore"), element("Rajwada, Ujjain")))
        assertTrue(decision is RapidoPreview.Decision.Stop)
    }

    @Test fun `search query text alone is not a location result`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.RESULTS)
        assertEquals(RapidoPreview.Decision.Wait, flow.inspect(screen(element("Rajwada"))))
    }

    @Test fun `preview requires the right destination vehicle and one fare`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.FARE)
        assertTrue(flow.inspect(screen(element("Rajwada"), element("Auto"),
            element("₹128"), element("Book Auto"))) is RapidoPreview.Decision.Preview)
        assertEquals(RapidoPreview.Stage.DONE, flow.stage)
        assertFalse(flow.inspect(screen(element("Book Auto"))) is RapidoPreview.Decision.Tap)
    }

    @Test fun `fare ambiguity refuses a spoken quote`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        flow.advanced(RapidoPreview.Stage.FARE)
        val decision = flow.inspect(screen(element("Rajwada"), element("Auto"),
            element("₹128"), element("₹150"), element("Book Auto")))
        assertTrue(decision is RapidoPreview.Decision.Stop)
    }

    @Test fun `other app cannot drive a Rapido action`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        assertEquals(RapidoPreview.Decision.Wait,
            flow.inspect(screen(element("Where to?"), pkg = "com.other.app")))
    }

    @Test fun `one destination can reach a fare preview without a booking action`() {
        val flow = RapidoPreview(RapidoIntent("Rajwada", RapidoIntent.Vehicle.AUTO))
        val home = flow.inspect(screen(element("Where to?")))
        assertTrue(home is RapidoPreview.Decision.Tap)
        flow.advanced((home as RapidoPreview.Decision.Tap).next)
        val search = flow.inspect(screen(ScreenElement(0, "", "Where to?", "EditText",
            Rect(0, 0, 100, 50), true, true)))
        assertTrue(search is RapidoPreview.Decision.Type)
        flow.advanced((search as RapidoPreview.Decision.Type).next)
        val result = flow.inspect(screen(element("Rajwada, Indore")))
        assertTrue(result is RapidoPreview.Decision.Tap)
        flow.advanced((result as RapidoPreview.Decision.Tap).next)
        val vehicle = flow.inspect(screen(element("Auto")))
        assertTrue(vehicle is RapidoPreview.Decision.Tap)
        flow.advanced((vehicle as RapidoPreview.Decision.Tap).next)
        val fare = flow.inspect(screen(element("Rajwada, Indore"), element("Auto"),
            element("₹128"), element("Book Auto")))
        assertTrue(fare is RapidoPreview.Decision.Preview)
    }

    private fun screen(vararg elements: ScreenElement, pkg: String = RapidoPreview.RAPIDO_PACKAGE) =
        ScreenSnapshot(pkg, true, elements.toList())

    private fun element(text: String) = ScreenElement(0, "", text, "TextView",
        Rect(0, 0, 100, 50), false, true)
}
