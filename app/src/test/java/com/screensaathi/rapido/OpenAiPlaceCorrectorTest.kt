package com.screensaathi.rapido

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer

class OpenAiPlaceCorrectorTest {
    private val source = "https://example.org/indore/bicholi"

    @Test fun acceptsPlaceOutsideBuiltInListWhenSearchSourceIsReturned() {
        assertEquals(OpenAiPlaceCorrector.Correction("Bicholi Mardana Extension", source),
            OpenAiPlaceCorrector.parseCorrection(response("Bicholi Mardana Extension")))
    }

    @Test fun rejectsUnsearchedOrUncitedClaimsAndInvalidPlaces() {
        assertNull(OpenAiPlaceCorrector.parseCorrection(response("Bicholi Mardana Extension", searched = false)))
        assertNull(OpenAiPlaceCorrector.parseCorrection(response("Bicholi Mardana Extension",
            resultUrl = "https://other.example/place")))
        assertNull(OpenAiPlaceCorrector.parseCorrection(response("Bicholi Mardana Extension",
            sourceUrl = "http://example.org/place", resultUrl = "http://example.org/place")))
        assertNull(OpenAiPlaceCorrector.parseCorrection(response("Not In Indore", confirmed = false)))
        assertNull(OpenAiPlaceCorrector.parseCorrection(response("जगह")))
        assertNull(OpenAiPlaceCorrector.parseCorrection(response("", confirmed = false)))
        assertNull(OpenAiPlaceCorrector.parseCorrection(response("Bicholi Mardana Extension",
            status = "incomplete")))
        assertNull(OpenAiPlaceCorrector.parseCorrection("bad json"))
    }

    @Test fun searchesUnknownPlaceWithOnlyExtractedName() {
        var sent: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://api.openai.com/v1/responses", request.url.toString())
            assertEquals("Bearer test-key", request.header("Authorization"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(response("Bicholi Mardana Extension").toResponseBody()).build()
        }.build()
        val corrector = OpenAiPlaceCorrector("test-key", client)
        assertEquals("Bicholi Mardana Extension", corrector.correct("Bicholi Mardna Ext")?.place)
        assertEquals("Unverified place spelling: Bicholi Mardna Ext", sent!!.getString("input"))
        assertEquals("gpt-6-luna", sent!!.getString("model"))
        assertEquals("none", sent!!.getJSONObject("reasoning").getString("effort"))
        assertEquals("required", sent!!.getString("tool_choice"))
        assertEquals("web_search", sent!!.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("low", sent!!.getJSONArray("tools").getJSONObject(0)
            .getString("search_context_size"))
        assertFalse(sent!!.getBoolean("store"))
        assertTrue(corrector.shouldCheck("Bicholi Mardna Ext"))
        assertFalse(corrector.shouldCheck("Vijay Nagar"))
        assertFalse(OpenAiPlaceCorrector(apiKey = "").shouldCheck("Bicholi Mardna Ext"))
    }

    private fun response(
        place: String,
        status: String = "completed",
        searched: Boolean = true,
        sourceUrl: String = source,
        resultUrl: String = sourceUrl,
        confirmed: Boolean = true,
    ): String {
        val output = JSONArray()
        if (searched) output.put(JSONObject().put("type", "web_search_call")
            .put("status", "completed")
            .put("action", JSONObject().put("type", "search")
                .put("sources", JSONArray().put(JSONObject().put("url", sourceUrl)))))
        output.put(JSONObject().put("type", "message").put("status", "completed")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                .put("text", JSONObject().put("place", place)
                    .put("source_url", resultUrl)
                    .put("confirmed_in_indore", confirmed).toString()))))
        return JSONObject().put("status", status).put("output", output).toString()
    }
}
