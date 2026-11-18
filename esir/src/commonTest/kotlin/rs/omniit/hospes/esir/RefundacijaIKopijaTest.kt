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
}
