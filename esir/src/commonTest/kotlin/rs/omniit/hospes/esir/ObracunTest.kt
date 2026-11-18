package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObracunTest {
    private fun d(s: String) = Decimal4.parse(s)

    @Test
    fun mnozenjeIZaokruzivanje() {
        assertEquals("300", (d("1200") * d("0.25")).toString())
        assertEquals("5.0001", (d("10.0001") * d("0.5")).toString(), "5,00005 se zaokružuje na 5,0001")
        assertEquals("0.0001", (d("0.0001") * d("1")).toString())
        assertEquals("-7.5", (d("-2.5") * d("3")).toString())
        assertEquals("12.35", d("12.345").zaokruzi(2).toString(), "polovina naviše")
        assertEquals("-12.35", d("-12.345").zaokruzi(2).toString(), "polovina od nule")
        assertEquals("12.34", d("12.3449").zaokruzi(2).toString())
        assertEquals("13", d("12.5").zaokruzi(0).toString())
        assertEquals("4.9999", d("499.99").stotiDeo().toString())
        assertEquals(3, d("0.125").brojDecimala)
        assertEquals(2, d("0.250").brojDecimala, "nula na kraju se ne računa")
        assertEquals(0, d("300.0000").brojDecimala)
    }

    @Test
    fun mnozenjeVanOpsegaJeGreska() {
        assertFailsWith<IllegalArgumentException> { d("99999999999999") * d("99999999999999") }
    }

    @Test
    fun stavkaSaKolicinomUKilogramima() {
        val s = Obracun.stavka("Sir /kg", d("0.250"), d("1199.99"), listOf("Ђ"))
        assertEquals("1199.99", s.unitPrice.toString())
        assertEquals("300", s.totalAmount.toString(), "0,250 × 1.199,99 = 299,9975 → 300,00")
        assertEquals(emptyList(), s.proveri())
    }

    @Test
    fun popustUmanjujeJedinicnuCenu() {
        val s = Obracun.stavka("Kafa", d("3"), d("250"), listOf("A"), popustProcenat = d("10"))
        assertEquals("225", s.unitPrice.toString())
        assertEquals("675", s.totalAmount.toString())

        val neparan = Obracun.stavka("Sok", d("1"), d("199.99"), listOf("A"), popustProcenat = d("15"))
        assertEquals("169.99", neparan.unitPrice.toString(), "199,99 − 29,9985 = 169,9915 → 169,99")
    }

    @Test
    fun neispravneStavke() {
        assertFailsWith<IllegalArgumentException> { Obracun.stavka("X", d("0"), d("1"), listOf("A")) }
        assertFailsWith<IllegalArgumentException> { Obracun.stavka("X", d("0.0005"), d("1"), listOf("A")) }
        assertFailsWith<IllegalArgumentException> { Obracun.stavka("X", d("1"), d("-1"), listOf("A")) }
        assertFailsWith<IllegalArgumentException> { Obracun.stavka("X", d("1"), d("1"), listOf("A"), d("101")) }
    }

    @Test
    fun naplataGotovinomSaKusurom() {
        val n = Obracun.naplata(d("975"), listOf(NacinPlacanja.GOTOVINA to null), primljenoGotovine = d("1000"))
        assertEquals(listOf(Placanje(d("975"), NacinPlacanja.GOTOVINA)), n.placanja, "PFR-u ide ono što ostaje u kasi")
        assertEquals("25", n.kusur.toString())
    }

    @Test
    fun podeljenoPlacanje() {
        val n = Obracun.naplata(
            d("975"),
            listOf(NacinPlacanja.PLATNA_KARTICA to d("500"), NacinPlacanja.GOTOVINA to null),
            primljenoGotovine = d("500"),
        )
        assertEquals(
            listOf(Placanje(d("500"), NacinPlacanja.PLATNA_KARTICA), Placanje(d("475"), NacinPlacanja.GOTOVINA)),
            n.placanja,
        )
        assertEquals("25", n.kusur.toString())
    }

    @Test
    fun naplataKojaSeNeSlaze() {
        assertFailsWith<IllegalArgumentException> {
            Obracun.naplata(d("975"), listOf(NacinPlacanja.PLATNA_KARTICA to d("1000")))
        }
        assertFailsWith<IllegalArgumentException> {
            Obracun.naplata(d("975"), listOf(NacinPlacanja.PLATNA_KARTICA to d("500")))
        }
        assertFailsWith<IllegalArgumentException> {
            Obracun.naplata(d("975"), listOf(NacinPlacanja.GOTOVINA to null), primljenoGotovine = d("900"))
        }
        assertFailsWith<IllegalArgumentException> {
            Obracun.naplata(d("975"), listOf(NacinPlacanja.GOTOVINA to null, NacinPlacanja.PLATNA_KARTICA to null))
        }
    }

    @Test
    fun ogranicenjePlacanjaPoPravilniku() {
        val greska = assertFailsWith<IllegalArgumentException> {
            Obracun.naplata(
                d("100"),
                listOf(NacinPlacanja.PLATNA_KARTICA to null),
                ogranicenje = OgranicenjePlacanja.BEZ_KARTICE_CEKA_I_INSTANTA,
            )
        }
        assertTrue("gotovina" in greska.message!!)
        val dozvoljeno = OgranicenjePlacanja.BEZ_KARTICE_CEKA_I_INSTANTA.dozvoljeni.map { it.name }.toSet()
        assertEquals(setOf("DRUGO_BEZGOTOVINSKO", "GOTOVINA", "PRENOS_NA_RACUN", "VAUCER"), dozvoljeno)
    }

    @Test
    fun ukupnoRacuna() {
        val stavke = listOf(
            Obracun.stavka("Kafa", d("3"), d("250"), listOf("A"), popustProcenat = d("10")),
            Obracun.stavka("Sir /kg", d("0.250"), d("1199.99"), listOf("A")),
        )
        assertEquals("975", Obracun.ukupno(stavke).toString())
    }
}
