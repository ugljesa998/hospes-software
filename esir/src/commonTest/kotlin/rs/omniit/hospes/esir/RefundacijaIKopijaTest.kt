package rs.omniit.hospes.esir

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefundacijaIKopijaTest {
    private val prodaja = ZahtevZaRacun(
        invoiceType = VrstaRacuna.PROMET,
        transactionType = TipTransakcije.PRODAJA,
        payment = listOf(Placanje(Decimal4.parse("300"), NacinPlacanja.GOTOVINA)),
        items = listOf(
            Stavka("Test kafa", Decimal4.parse("1"), Decimal4.parse("300"), Decimal4.parse("300"), listOf("A")),
        ),
        cashier = "Hospes test",
    )

    private fun izdat(zahtev: ZahtevZaRacun, broj: String = "7EHXTU6T-7EHXTU6T-5") = IzdatRacun(
        requestId = "abc",
        zahtev = zahtev,
        racun = FiskalizovanRacun(invoiceNumber = broj, sdcDateTime = "2026-11-18T10:56:51+01:00"),
    )

    @Test
    fun refundacijaProdajePoPuraUputstvu() {
        val r = izdat(prodaja).zahtevZaRefundaciju("20:123456789")

        assertEquals(VrstaRacuna.PROMET, r.invoiceType)
        assertEquals(TipTransakcije.REFUNDACIJA, r.transactionType)
        assertEquals("20:123456789", r.buyerId)
        assertEquals("7EHXTU6T-7EHXTU6T-5", r.referentDocumentNumber)
        assertEquals("2026-11-18T10:56:51+01:00", r.referentDocumentDT)
        assertEquals(prodaja.items, r.items, "iste stavke, sa pozitivnim iznosima")
        assertEquals(prodaja.payment, r.payment)
        assertEquals(emptyList(), r.proveri())
    }

    @Test
    fun refundacijaObuke() {
        val r = izdat(prodaja.copy(invoiceType = VrstaRacuna.OBUKA)).zahtevZaRefundaciju("20:123456789")
        assertEquals(VrstaRacuna.OBUKA, r.invoiceType)
        assertEquals(TipTransakcije.REFUNDACIJA, r.transactionType)
    }

    @Test
    fun neRefundiraSeRefundacijaNiAvans() {
        val refundacija = izdat(prodaja).zahtevZaRefundaciju("20:123456789")
        assertFailsWith<IllegalArgumentException> { izdat(refundacija).zahtevZaRefundaciju("20:1") }
        assertFailsWith<IllegalArgumentException> {
            izdat(prodaja.copy(invoiceType = VrstaRacuna.AVANS)).zahtevZaRefundaciju("20:1")
        }
    }

    @Test
    fun kopijaProdaje() {
        val k = izdat(prodaja).zahtevZaKopiju()
        assertEquals(VrstaRacuna.KOPIJA, k.invoiceType)
        assertEquals(TipTransakcije.PRODAJA, k.transactionType)
        assertEquals("7EHXTU6T-7EHXTU6T-5", k.referentDocumentNumber)
        assertEquals(emptyList(), k.proveri())
    }

    @Test
    fun kopijaRefundacijeZadrzavaKupcaIPozivaSeNaRefundaciju() {
        val refundacija = izdat(prodaja).zahtevZaRefundaciju("20:123456789")
        val k = izdat(refundacija, broj = "7EHXTU6T-7EHXTU6T-6").zahtevZaKopiju()

        assertEquals(VrstaRacuna.KOPIJA, k.invoiceType)
        assertEquals(TipTransakcije.REFUNDACIJA, k.transactionType)
        assertEquals("20:123456789", k.buyerId)
        assertEquals("7EHXTU6T-7EHXTU6T-6", k.referentDocumentNumber)
        assertEquals(emptyList(), k.proveri())
    }

    @Test
    fun kopijaSeNePraviOdObukeIliKopije() {
        assertFailsWith<IllegalArgumentException> { izdat(prodaja.copy(invoiceType = VrstaRacuna.OBUKA)).zahtevZaKopiju() }
        val kopija = izdat(prodaja).zahtevZaKopiju()
        assertFailsWith<IllegalArgumentException> { izdat(kopija).zahtevZaKopiju() }
    }

    @Test
    fun refundacijaBezIdKupcaNijeIspravna() {
        val r = prodaja.copy(transactionType = TipTransakcije.REFUNDACIJA, referentDocumentNumber = "AAAA1111-AAAA1111-1")
        assertTrue(r.proveri().any { "ID kupca" in it })
    }

    @Test
    fun sifarnikIdKupca() {
        assertNull(SifarnikKupca.proveriIdKupca("10:123456789"))
        assertNull(SifarnikKupca.proveriIdKupca("12:123456789:54321"))
        assertTrue(SifarnikKupca.proveriIdKupca("123456789")!!.contains("format"))
        assertTrue(SifarnikKupca.proveriIdKupca("99:123")!!.contains("nepoznata šifra"))
        assertTrue(SifarnikKupca.proveriIdKupca("20:" + "1".repeat(20))!!.contains("20 znakova"))
        assertNull(SifarnikKupca.proveriOpcionoPolje("60:01032022_31032022"))
        assertTrue(SifarnikKupca.proveriOpcionoPolje("10:123")!!.contains("nepoznata šifra"))
    }

    @Test
    fun neispravanReferentniBroj() {
        val k = prodaja.copy(invoiceType = VrstaRacuna.KOPIJA, referentDocumentNumber = "nesto")
        assertTrue(k.proveri().any { "XXXXXXXX-XXXXXXXX" in it })
    }

    @Test
    fun izdatRacunSeCuvaICitaIzJsona() {
        val original = izdat(prodaja)
        val json = Json.encodeToString(IzdatRacun.serializer(), original)
        assertEquals(original, EsirHttp.json.decodeFromString(IzdatRacun.serializer(), json))
    }

    @Test
    fun dozvoljeneRadnjeZaRacun() {
        val promet = izdat(prodaja)
        assertTrue(promet.mozeRefundacija && promet.mozeKopija)
        val obuka = izdat(prodaja.copy(invoiceType = VrstaRacuna.OBUKA))
        assertTrue(obuka.mozeRefundacija && !obuka.mozeKopija, "kopija se izdaje samo za Promet")
        val refundacija = izdat(promet.zahtevZaRefundaciju("20:123456789"), "X-Y-6")
        assertTrue(!refundacija.mozeRefundacija && refundacija.mozeKopija, "kopija refundacije da, refundacija refundacije ne")
        val avans = izdat(prodaja.copy(invoiceType = VrstaRacuna.AVANS))
        assertTrue(!avans.mozeRefundacija && !avans.mozeKopija, "avans ima svoj tok (9.1)")
    }

    @Test
    fun zurnalZnaDaJeRacunVecRefundiran() {
        val zurnal = ZurnalUMemoriji()
        val original = izdat(prodaja, "X-Y-1")
        zurnal.sacuvaj(original)
        assertNull(zurnal.refundacijaZa("X-Y-1"))

        val refundacija = izdat(original.zahtevZaRefundaciju("20:123456789"), "X-Y-2")
        zurnal.sacuvaj(refundacija)
        zurnal.sacuvaj(izdat(refundacija.zahtevZaKopiju(), "X-Y-3"))
        zurnal.sacuvaj(izdat(original.zahtevZaKopiju(), "X-Y-4"))

        assertEquals("X-Y-2", zurnal.refundacijaZa("X-Y-1")?.racun?.invoiceNumber, "kopije se ne računaju")
        assertNull(zurnal.refundacijaZa("X-Y-2"))
    }

    private val tri = prodaja.copy(
        items = listOf(
            Stavka("Kafa /kom", Decimal4.parse("3"), Decimal4.parse("225"), Decimal4.parse("675"), listOf("A")),
            Stavka("Sir /kg", Decimal4.parse("0.25"), Decimal4.parse("1199.99"), Decimal4.parse("300"), listOf("A")),
            Stavka("Kafa /kom", Decimal4.parse("1"), Decimal4.parse("225"), Decimal4.parse("225"), listOf("A")),
        ),
        payment = listOf(Placanje(Decimal4.parse("700"), NacinPlacanja.GOTOVINA), Placanje(Decimal4.parse("500"), NacinPlacanja.PLATNA_KARTICA)),
    )

    private fun d(vararg v: String) = v.map(Decimal4::parse)

    @Test
    fun delimicnaRefundacija() {
        val original = izdat(tri, "X-Y-1")
        val r = original.zahtevZaDelimicnuRefundaciju("20:123456789", d("1", "0", "0"), NacinPlacanja.GOTOVINA)

        assertEquals(listOf(Stavka("Kafa /kom", Decimal4.parse("1"), Decimal4.parse("225"), Decimal4.parse("225"), listOf("A"))), r.items)
        assertEquals(listOf(Placanje(Decimal4.parse("225"), NacinPlacanja.GOTOVINA)), r.payment)
        assertEquals(TipTransakcije.REFUNDACIJA, r.transactionType)
        assertEquals("X-Y-1", r.referentDocumentNumber)
        assertEquals(emptyList(), r.proveri())

        val sir = original.zahtevZaDelimicnuRefundaciju("20:1", d("0", "0.1", "0"), NacinPlacanja.PLATNA_KARTICA)
        assertEquals("120", sir.items.single().totalAmount.toString(), "0,1 kg × 1199,99 = 119,999 → 120,00")
    }

    @Test
    fun neVracaSeViseNegoStoJeOstalo() {
        val original = izdat(tri, "X-Y-1")
        val prva = izdat(original.zahtevZaDelimicnuRefundaciju("20:1", d("2", "0", "1"), NacinPlacanja.GOTOVINA), "X-Y-2")
        assertEquals(d("0", "0.25", "1"), original.preostaloZaRefundaciju(listOf(prva)), "3 vraćene kafe se troše redom: prva stavka (3), pa druga")

        val greska = assertFailsWith<IllegalArgumentException> {
            original.zahtevZaDelimicnuRefundaciju("20:1", d("2", "0", "0"), NacinPlacanja.GOTOVINA, original.preostaloZaRefundaciju(listOf(prva)))
        }
        assertEquals("„Kafa /kom“: može da se vrati najviše 0, a traži se 2", greska.message)
        assertFailsWith<IllegalArgumentException> { original.zahtevZaDelimicnuRefundaciju("20:1", d("0", "0", "0"), NacinPlacanja.GOTOVINA) }
        assertFailsWith<IllegalArgumentException> { original.zahtevZaDelimicnuRefundaciju("20:1", d("0.0005", "0", "0"), NacinPlacanja.GOTOVINA) }

        val cela = izdat(original.zahtevZaRefundaciju("20:1"), "X-Y-3")
        assertEquals(d("0", "0", "0"), original.preostaloZaRefundaciju(listOf(cela)))
    }

    @Test
    fun zurnalRefundacijeIPredracun() {
        val zurnal = ZurnalUMemoriji()
        val original = izdat(tri, "X-Y-1").copy(racun = FiskalizovanRacun("X-Y-1", "2026-11-18T10:00:00+01:00"))
        zurnal.sacuvaj(original)
        zurnal.sacuvaj(izdat(original.zahtevZaDelimicnuRefundaciju("20:1", d("1", "0", "0"), NacinPlacanja.GOTOVINA), "X-Y-2"))
        zurnal.sacuvaj(izdat(original.zahtevZaDelimicnuRefundaciju("20:1", d("1", "0", "0"), NacinPlacanja.GOTOVINA), "X-Y-3"))
        assertEquals(2, zurnal.refundacijeZa("X-Y-1").size)

        val predracun = izdat(prodaja.copy(invoiceType = VrstaRacuna.PREDRACUN), "X-Y-4")
        zurnal.sacuvaj(predracun)
        assertNull(zurnal.prometIzPredracuna("X-Y-4"))
        zurnal.sacuvaj(izdat(predracun.zahtevZaPrometIzPredracuna(), "X-Y-5"))
        assertEquals("X-Y-5", zurnal.prometIzPredracuna("X-Y-4")?.racun?.invoiceNumber)
    }
}
