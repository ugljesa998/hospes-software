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
