package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PredracunTest {
    private val predracun = ZahtevZaRacun(
        invoiceType = VrstaRacuna.PREDRACUN,
        transactionType = TipTransakcije.PRODAJA,
        payment = listOf(Placanje(Decimal4.parse("300"), NacinPlacanja.PRENOS_NA_RACUN)),
        items = listOf(
            Stavka("Test kafa", Decimal4.parse("1"), Decimal4.parse("300"), Decimal4.parse("300"), listOf("A")),
        ),
        cashier = "Hospes test",
    )

    private fun izdat(zahtev: ZahtevZaRacun) = IzdatRacun(
        requestId = "abc",
        zahtev = zahtev,
        racun = FiskalizovanRacun(invoiceNumber = "7EHXTU6T-7EHXTU6T-7", sdcDateTime = "2026-11-18T12:00:00+01:00"),
    )

    @Test
    fun refundacijaPredracuna() {
        val r = izdat(predracun).zahtevZaRefundaciju("20:123456789")
        assertEquals(VrstaRacuna.PREDRACUN, r.invoiceType)
        assertEquals(TipTransakcije.REFUNDACIJA, r.transactionType)
        assertEquals("7EHXTU6T-7EHXTU6T-7", r.referentDocumentNumber)
        assertEquals(emptyList(), r.proveri())
    }

    @Test
    fun prometIzPredracunaPozivaSeNaPredracun() {
        val p = izdat(predracun).zahtevZaPrometIzPredracuna()
        assertEquals(VrstaRacuna.PROMET, p.invoiceType)
        assertEquals(TipTransakcije.PRODAJA, p.transactionType)
        assertEquals("7EHXTU6T-7EHXTU6T-7", p.referentDocumentNumber)
        assertEquals("2026-11-18T12:00:00+01:00", p.referentDocumentDT)
        assertEquals(predracun.items, p.items)
        assertEquals(predracun.payment, p.payment, "bez navedenog plaćanja ostaje plaćanje sa predračuna")
        assertEquals(emptyList(), p.proveri())
    }

    @Test
    fun prometIzPredracunaSaDrugacijimPlacanjem() {
        val gotovina = listOf(Placanje(Decimal4.parse("300"), NacinPlacanja.GOTOVINA))
        assertEquals(gotovina, izdat(predracun).zahtevZaPrometIzPredracuna(gotovina).payment)
    }

    @Test
    fun prometSeNePraviOdDrugihVrsta() {
        assertFailsWith<IllegalArgumentException> {
            izdat(predracun.copy(invoiceType = VrstaRacuna.PROMET)).zahtevZaPrometIzPredracuna()
        }
        val refundacijaPredracuna = izdat(predracun).zahtevZaRefundaciju("20:123456789")
        assertFailsWith<IllegalArgumentException> { izdat(refundacijaPredracuna).zahtevZaPrometIzPredracuna() }
    }

    @Test
    fun predracunNemaKopiju() {
        assertFailsWith<IllegalArgumentException> { izdat(predracun).zahtevZaKopiju() }
    }
}
