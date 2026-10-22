package com.tmplayer.ui.auth

import com.tmplayer.data.Country
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign in's number rules and the country guess, shared by the phone, TV, desktop and web. */
class PhoneEntryTest {
    private val gb = Country("GB", "United Kingdom", listOf("44"))
    private val je = Country("JE", "Jersey", listOf("44"))
    private val us = Country("US", "USA", listOf("1"))
    private val ca = Country("CA", "Canada", listOf("1"))
    private val ru = Country("RU", "Russia", listOf("7"))
    private val kz = Country("KZ", "Kazakhstan", listOf("7"))
    private val it = Country("IT", "Italy", listOf("39"))
    private val va = Country("VA", "Vatican City", listOf("39", "379"))
    private val india = Country("IN", "India", listOf("91"))
    private val bs = Country("BS", "Bahamas", listOf("1242"))
    private val countries = listOf(gb, je, us, ca, ru, kz, it, va, india, bs)

    @Test
    fun `a pasted international number is taken apart whatever its punctuation`() {
        for (raw in listOf("+44 7700 900123", "0044 7700 900123", "+44-7700-900-123", "(+44) 7700 900123", " +44 (7700) 900123")) {
            val entry = PhoneEntry()
            entry.typeNational(raw, countries)
            assertEquals(raw, "44", entry.dial)
            assertEquals(raw, "7700900123", entry.national)
        }
        val dial = PhoneEntry()
        dial.typeDial("+1 242 555 0100", countries)
        assertEquals("1242", dial.dial)
        assertEquals("5550100", dial.national)
    }

    @Test
    fun `the trunk zero goes, except where the zero is part of the number`() {
        val uk = PhoneEntry("44")
        uk.typeNational("07700 900123", countries)
        assertEquals("7700900123", uk.national)
        uk.take("+44 07700 900123", countries)
        assertEquals("7700900123", uk.national)
        val italy = PhoneEntry("39")
        italy.typeNational("06 1234 5678", countries)
        assertEquals("0612345678", italy.national)
    }

    @Test
    fun `other scripts' digits become ASCII`() {
        assertEquals("0123456789", PhoneRules.digits("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("0123456789", PhoneRules.digits("۰۱۲۳۴۵۶۷۸۹"))
        val entry = PhoneEntry("91")
        entry.typeNational("٩٨٧٦٥ ٤٣٢١٠", countries)
        assertEquals("9876543210", entry.national)
        assertEquals("+919876543210", PhoneRules.international("+٩١ ٩٨٧٦٥٤٣٢١٠", countries))
        assertEquals("+919876543210", keepInternational("۰۰۹۱۹۸۷۶۵۴۳۲۱۰"))
    }

    @Test
    fun `the national number is capped at E164's fifteen digits`() {
        val entry = PhoneEntry("44")
        entry.typeNational("1234567890123456789", countries)
        assertEquals(13, entry.national.length)
    }

    @Test
    fun `a shared code goes by area code, then the chosen country, then the usual one`() {
        assertEquals(ca, PhoneRules.country(countries, "1", areaIso = "CA", chosenIso = "US"))
        assertEquals(je, PhoneRules.country(countries, "44", areaIso = null, chosenIso = "JE"))
        assertEquals(kz, PhoneRules.country(countries, "7", areaIso = "", chosenIso = "KZ"))
        assertEquals(us, PhoneRules.country(countries, "1", areaIso = null, chosenIso = "IN"))
        assertEquals(gb, PhoneRules.country(countries, "44", areaIso = null, chosenIso = null))
        assertEquals(it, PhoneRules.country(countries, "39", areaIso = null, chosenIso = null))
        assertEquals(va, PhoneRules.country(countries, "379", areaIso = null, chosenIso = null))
    }

    @Test
    fun `a typed code switches the country and an unknown one is no country`() {
        val entry = PhoneEntry()
        entry.guess(gb)
        entry.typeDial("91", countries)
        assertEquals(india, PhoneRules.country(countries, entry.dial, null, entry.chosenIso))
        entry.typeDial("999", countries)
        assertNull(PhoneRules.country(countries, entry.dial, null, entry.chosenIso))
        entry.typeNational("12345678", countries)
        assertFalse(entry.ready(null))
    }

    @Test
    fun `a late guess never replaces what the viewer did`() {
        val picked = PhoneEntry()
        picked.pick(india)
        picked.guess(gb)
        assertEquals("91", picked.dial)
        val pasted = PhoneEntry()
        pasted.take("+7 912 345 6789", countries)
        pasted.guess(gb)
        assertEquals("7", pasted.dial)
        val fresh = PhoneEntry()
        fresh.guess(gb)
        assertEquals("44", fresh.dial)
        assertEquals("GB", fresh.chosenIso)
    }

    @Test
    fun `ready at Telegram's seven digits with a known country`() {
        val entry = PhoneEntry("44")
        entry.typeNational("7700", countries)
        assertFalse(entry.ready(gb))
        entry.typeNational("77009", countries)
        assertTrue(entry.ready(gb))
    }

    @Test
    fun `picking a country keeps the national number`() {
        val entry = PhoneEntry("44", "7700900123")
        entry.pick(india)
        assertEquals("+917700900123", entry.full)
    }

    @Test
    fun `the region of the browser's languages`() {
        assertEquals("IN", CountryGuess.regionOf(listOf("en-IN", "en")))
        assertEquals("TW", CountryGuess.regionOf(listOf("zh-Hant-TW")))
        assertEquals("BR", CountryGuess.regionOf(listOf("es-419", "pt_BR")))
        assertEquals("", CountryGuess.regionOf(listOf("en", "")))
    }

    @Test
    fun `the guess takes Telegram's answer first and the device's after`() = runTest {
        val found = CountryGuess.country({ countries }, { "CA" }, { "GB" })
        assertEquals(ca, found)
        assertEquals(gb, CountryGuess.country({ countries }, { "" }, { "GB" }))
        assertEquals(gb, CountryGuess.country({ countries }, { error("offline") }, { "gb" }))
        assertNull(CountryGuess.country({ countries }, { "" }, { "" }))
    }

    @Test
    fun `a slow Telegram leaves the device's answer, inside the limit`() = runTest {
        val found = CountryGuess.country({ countries }, { awaitCancellation() }, { "IN" })
        assertEquals(india, found)
        assertEquals(CountryGuess.LIMIT_MS, currentTime)
    }

    @Test
    fun `no country list in time gives nothing, at the limit`() = runTest {
        val never = CompletableDeferred<List<Country>>()
        assertNull(CountryGuess.country({ never.await() }, { "GB" }, { "GB" }))
        assertEquals(CountryGuess.LIMIT_MS, currentTime)
    }

    @Test
    fun `a quick answer does not wait out the limit`() = runTest {
        val found = CountryGuess.country({ delay(400); countries }, { delay(300); "JE" }, { "" })
        assertEquals(je, found)
        assertEquals(700L, currentTime)
    }
}
