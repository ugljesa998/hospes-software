package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Avansni tok, po primeru iz PURS uputstva 9.1 (stan + parking mesto). */
class AvansTest {
    private fun d(s: String) = Decimal4.parse(s)

    private fun izdat(zahtev: ZahtevZaRacun, broj: String, vreme: String, porez: List<PoreskaStavka> = emptyList()) =
        IzdatRacun("rid-$broj", zahtev, FiskalizovanRacun(invoiceNumber = broj, sdcDateTime = vreme, taxItems = porez))

    private val prenos = NacinPlacanja.PRENOS_NA_RACUN
    private val uplata = listOf(
        AvansnaUplata(SifraAvansa.POSEBNA_STOPA, "Е", d("1500000")),
        AvansnaUplata(SifraAvansa.OPSTA_STOPA, "Ђ", d("250000")),
    )

    private val ap1 = izdat(
        Avans.zahtevZaAvans(uplata, listOf(Placanje(d("1750000"), prenos)), idKupca = "10:123456789", kasir = "Техничар"),
        "M97W88P4-M97W88P4-60", "2022-01-16T10:20:00+01:00",
    )
    private val ap2 = izdat(
        Avans.zahtevZaAvans(uplata, listOf(Placanje(d("1750000"), prenos)), prethodni = ap1),
        "M97W88P4-M97W88P4-90", "2022-02-16T12:30:00+01:00",
    )

    @Test
    fun prviAvans() {
        val z = ap1.zahtev
        assertEquals(VrstaRacuna.AVANS, z.invoiceType)
        assertEquals(TipTransakcije.PRODAJA, z.transactionType)
        assertEquals(listOf("11: Аванс (Е)", "10: Аванс (Ђ)"), z.items.map { it.name })
        assertEquals(listOf("1", "1"), z.items.map { it.quantity.toString() })
        assertEquals(listOf(listOf("Е"), listOf("Ђ")), z.items.map { it.labels })
        assertNull(z.referentDocumentNumber)
        assertEquals(emptyList(), z.proveri())
    }

    @Test
    fun sledeciAvansSePozivaNaPrethodniINasledjujeKupca() {
        val z = ap2.zahtev
        assertEquals("M97W88P4-M97W88P4-60", z.referentDocumentNumber)
        assertEquals("2022-01-16T10:20:00+01:00", z.referentDocumentDT)
        assertEquals("10:123456789", z.buyerId)
        assertEquals(emptyList(), z.proveri())
    }

    @Test
    fun placanjeMoraDaOdgovaraIznosuAvansa() {
        assertFailsWith<IllegalArgumentException> {
            Avans.zahtevZaAvans(uplata, listOf(Placanje(d("1000"), prenos)))
        }
    }

    @Test
    fun lanacSePratiUnazad() {
        val zurnal = listOf(ap1, ap2).associateBy { it.racun.invoiceNumber }
        assertEquals(listOf(ap1, ap2), Avans.lanacAvansa(ap2) { zurnal[it] })
        assertEquals(listOf(ap1), Avans.lanacAvansa(ap1) { zurnal[it] })
    }

    @Test
    fun zatvaranjeSabiraSveAvanseIPozivaSeNaPoslednji() {
        val ar = Avans.zahtevZaZatvaranje(listOf(ap1, ap2))

        assertEquals(VrstaRacuna.AVANS, ar.invoiceType)
        assertEquals(TipTransakcije.REFUNDACIJA, ar.transactionType)
        assertEquals("M97W88P4-M97W88P4-90", ar.referentDocumentNumber)
        assertEquals("10:123456789", ar.buyerId)
        val stavke = ar.items.associate { it.name to it.totalAmount.toString() }
        assertEquals(mapOf("11: Аванс (Е)" to "3000000", "10: Аванс (Ђ)" to "500000"), stavke)
        assertEquals(listOf(Placanje(d("3500000"), prenos)), ar.payment)
        assertEquals(emptyList(), ar.proveri())
    }

    @Test
    fun zatvaranjeBezKupcaNijeMoguce() {
        val bezKupca = izdat(
            Avans.zahtevZaAvans(uplata, listOf(Placanje(d("1750000"), prenos))),
            "AAAA1111-AAAA1111-1", "2022-01-16T10:20:00+01:00",
        )
        assertFailsWith<IllegalArgumentException> { Avans.zahtevZaZatvaranje(listOf(bezKupca)) }
        assertEquals("20:123", Avans.zahtevZaZatvaranje(listOf(bezKupca), idKupca = "20:123").buyerId)
    }

    @Test
    fun konacniRacunPoPursPrimeru() {
        val ar = izdat(
            Avans.zahtevZaZatvaranje(listOf(ap1, ap2)), "M97W88P4-M97W88P4-97", "2022-03-09T10:50:00+01:00",
            porez = listOf(PoreskaStavka("Ђ", d("83333.33")), PoreskaStavka("Е", d("272727.27"))),
        )
        val stavke = listOf(
            Stavka("Стан бр. 12 (87.6)", d("1"), d("6000000"), d("6000000"), listOf("Е")),
            Stavka("Паркинг место бр. 124", d("1"), d("800000"), d("800000"), listOf("Ђ")),
        )
        val k = Avans.konacniRacun(ar, stavke, prenos)

        assertEquals(VrstaRacuna.PROMET, k.zahtev.invoiceType)
        assertEquals(TipTransakcije.PRODAJA, k.zahtev.transactionType)
        assertEquals("M97W88P4-M97W88P4-97", k.zahtev.referentDocumentNumber)
        assertEquals("10:123456789", k.zahtev.buyerId)
        assertEquals(stavke, k.zahtev.items, "stavke idu po punoj ceni")
        assertEquals(listOf(Placanje(d("3300000"), prenos)), k.zahtev.payment, "PFR-u se šalje samo ostatak")
        assertEquals("3500000", k.placenoAvansom.toString())
        assertEquals("356060.6", k.pdvNaAvans.toString())
        assertEquals("3300000", k.preostaloZaPlacanje.toString())
        assertEquals("Последњи авансни рачун: M97W88P4-M97W88P4-90 16.02.2022", k.reklamnoPolje, "kao u PURS primeru")
        assertEquals(
            listOf(
                "Плаћено авансом:            3.500.000,00",
                "ПДВ на аванс:                 356.060,60",
                "Преостало за плаћање:       3.300.000,00",
            ),
            k.dodatniRedovi,
            "isto kao crveni redovi u PURS primeru, poravnato na 40 znakova",
        )
        assertEquals(emptyList(), k.zahtev.proveri())
    }

    @Test
    fun konacniRacunSePozivaSamoNaAvansRefundaciju() {
        assertFailsWith<IllegalArgumentException> { Avans.konacniRacun(ap2, emptyList(), prenos) }
    }

    @Test
    fun avansVeciOdRacunaNijePodrzan() {
        val ar = izdat(Avans.zahtevZaZatvaranje(listOf(ap1, ap2)), "M97W88P4-M97W88P4-97", "2022-03-09T10:50:00+01:00")
        val mala = listOf(Stavka("Nešto", d("1"), d("100"), d("100"), listOf("Ђ")))
        assertFailsWith<IllegalArgumentException> { Avans.konacniRacun(ar, mala, prenos) }
    }

    @Test
    fun sifreAvansa() {
        assertEquals(SifraAvansa.OPSTA_STOPA, SifraAvansa.zaKod(10))
        assertEquals(SifraAvansa.VAN_SISTEMA_PDV, SifraAvansa.zaKod(13))
        assertNull(SifraAvansa.zaKod(14))
    }
}
