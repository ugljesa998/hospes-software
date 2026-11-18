package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SifreStanjaTest {
    @Test
    fun poznateSifre() {
        assertEquals("Potreban je PIN", SifreStanja.za("1500").opis)
        assertEquals(VrstaSifre.UPOZORENJE, SifreStanja.za("1300").vrsta)
        assertEquals(VrstaSifre.INFO, SifreStanja.za("0100").vrsta)
    }

    @Test
    fun zakljucanaKarticaJeTrajnaGreska() {
        assertTrue(SifreStanja.za("2110").trajna)
        assertFalse(SifreStanja.za("2100").trajna)
    }

    @Test
    fun nepoznataSifraDobijaVrstuPoPrvojCifri() {
        assertEquals(VrstaSifre.UPOZORENJE, SifreStanja.za("1777").vrsta)
        assertEquals(VrstaSifre.GRESKA, SifreStanja.za("6001").vrsta)
        assertEquals("Nepoznata šifra", SifreStanja.za("6001").opis)
    }
}
