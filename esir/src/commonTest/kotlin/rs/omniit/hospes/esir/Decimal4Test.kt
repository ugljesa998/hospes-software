package rs.omniit.hospes.esir

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Decimal4Test {
    @Test
    fun citaICuvaTacnuVrednost() {
        assertEquals("300", Decimal4.parse("300").toString())
        assertEquals("27.27", Decimal4.parse("27.27").toString())
        assertEquals("0.5", Decimal4.parse("0.50").toString())
        assertEquals("-12.3456", Decimal4.parse("-12.3456").toString())
        assertEquals(272_700L, Decimal4.parse("27.27").unscaled)
    }

    @Test
    fun neZaokruzujeTiho() {
        assertFailsWith<IllegalArgumentException> { Decimal4.parse("1.23456") }
        assertEquals("1.2345", Decimal4.parse("1.234500").toString())
    }

    @Test
    fun odbijaNeispravanUnos() {
        assertFailsWith<IllegalArgumentException> { Decimal4.parse("") }
        assertFailsWith<IllegalArgumentException> { Decimal4.parse("1,5") }
        assertFailsWith<IllegalArgumentException> { Decimal4.parse("1e3") }
        assertFailsWith<IllegalArgumentException> { Decimal4.parse("123456789012345") }
    }

    @Test
    fun sabiranjeIOduzimanjeSuTacni() {
        assertEquals("0.3", (Decimal4.parse("0.1") + Decimal4.parse("0.2")).toString())
        assertEquals("-27.2727", (Decimal4.parse("272.7273") - Decimal4.parse("300")).toString())
        assertFailsWith<IllegalArgumentException> {
            Decimal4.parse("99999999999999.9999") + Decimal4.parse("0.0001")
        }
    }

    @Test
    fun zapisZaStampu() {
        assertEquals("1.500,00", Decimal4.parse("1500").zaStampu())
        assertEquals("136,36", Decimal4.parse("136.3636").zaStampu())
        assertEquals("0,01", Decimal4.parse("0.005").zaStampu(), "polovina se zaokružuje naviše")
        assertEquals("3.500.000,00", Decimal4.parse("3500000").zaStampu())
        assertEquals("-27,27", Decimal4.parse("-27.2727").zaStampu())
        assertEquals("0,00", Decimal4.NULA.zaStampu())
    }

    @Test
    fun poredjenje() {
        assertTrue(Decimal4.parse("10") > Decimal4.parse("9.9999"))
        assertEquals(Decimal4.parse("10"), Decimal4.parse("10.0000"))
    }

    @Test
    fun jsonBrojBezGubitkaPreciznosti() {
        val json = Json
        assertEquals("0.1", json.decodeFromString(Decimal4.serializer(), "0.1").toString())
        assertEquals("27.2727", json.encodeToString(Decimal4.serializer(), Decimal4.parse("27.2727")))
        assertEquals("300", json.encodeToString(Decimal4.serializer(), Decimal4.parse("300.00")))
    }
}
