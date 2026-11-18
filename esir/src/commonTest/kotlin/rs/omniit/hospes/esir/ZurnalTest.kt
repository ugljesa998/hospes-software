package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ZurnalTest {
    private fun d(s: String) = Decimal4.parse(s)

    private fun racun(
        broj: String,
        vreme: String,
        vrsta: VrstaRacuna = VrstaRacuna.PROMET,
        tip: TipTransakcije = TipTransakcije.PRODAJA,
        naziv: String = "Kafa",
        iznos: String = "300",
        nacin: NacinPlacanja = NacinPlacanja.GOTOVINA,
        kupac: String? = null,
    ) = IzdatRacun(
        requestId = "r-$broj",
        zahtev = ZahtevZaRacun(
            invoiceType = vrsta,
            transactionType = tip,
            payment = listOf(Placanje(d(iznos), nacin)),
            items = listOf(Stavka(naziv, d("1"), d(iznos), d(iznos), listOf("A"))),
            cashier = "Marko",
            buyerId = kupac,
        ),
        racun = FiskalizovanRacun(invoiceNumber = broj, sdcDateTime = vreme, totalAmount = d(iznos), totalCounter = broj.substringAfterLast('-').toLong()),
    )

    private val zurnal = ZurnalUMemoriji().apply {
        sacuvaj(racun("AAA-AAA-1", "2026-10-01T09:00:00+02:00", naziv = "Kafa"))
        sacuvaj(racun("AAA-AAA-2", "2026-11-17T10:00:00+01:00", naziv = "Sok", iznos = "200", nacin = NacinPlacanja.PLATNA_KARTICA))
        sacuvaj(racun("AAA-AAA-3", "2026-11-18T11:00:00+01:00", tip = TipTransakcije.REFUNDACIJA, kupac = "20:123456789"))
        sacuvaj(racun("AAA-AAA-4", "2026-11-18T12:00:00+01:00", vrsta = VrstaRacuna.OBUKA, naziv = "Test kafa"))
    }

    private fun brojevi(filter: FilterZurnala) = zurnal.pretrazi(filter).map { it.racun.invoiceNumber }

    @Test
    fun sviOdNajnovijeg() {
        assertEquals(listOf("AAA-AAA-4", "AAA-AAA-3", "AAA-AAA-2", "AAA-AAA-1"), zurnal.svi().map { it.racun.invoiceNumber })
    }

    @Test
    fun poDatumu() {
        assertEquals(listOf("AAA-AAA-4", "AAA-AAA-3", "AAA-AAA-2"), brojevi(FilterZurnala(od = "2026-10-19")))
        assertEquals(listOf("AAA-AAA-4", "AAA-AAA-3"), brojevi(FilterZurnala(od = "2026-11-18", doDana = "2026-11-18")))
        assertEquals(listOf("AAA-AAA-1"), brojevi(FilterZurnala(doDana = "2026-10-31")))
    }

    @Test
    fun poVrstiTipuKupcuIPlacanju() {
        assertEquals(listOf("AAA-AAA-4"), brojevi(FilterZurnala(vrste = setOf(VrstaRacuna.OBUKA))))
        assertEquals(listOf("AAA-AAA-3"), brojevi(FilterZurnala(tip = TipTransakcije.REFUNDACIJA)))
        assertEquals(listOf("AAA-AAA-3"), brojevi(FilterZurnala(kupac = "20:123456789")))
        assertEquals(listOf("AAA-AAA-2"), brojevi(FilterZurnala(nacinPlacanja = NacinPlacanja.PLATNA_KARTICA)))
    }

    @Test
    fun poTekstu() {
        assertEquals(listOf("AAA-AAA-4", "AAA-AAA-3", "AAA-AAA-1"), brojevi(FilterZurnala(tekst = "KAFA")))
        assertEquals(listOf("AAA-AAA-2"), brojevi(FilterZurnala(tekst = "aaa-2")))
        assertEquals(4, brojevi(FilterZurnala(tekst = "marko")).size, "pretraga i po kasiru")
    }

    @Test
    fun zbiroviPoVrsti() {
        val zbirovi = zbiroviZurnala(zurnal.svi()).associate { it.oznaka to (it.broj to it.iznos.toString()) }
        assertEquals(
            mapOf("Obuka Prodaja" to (1 to "300"), "Promet Refundacija" to (1 to "300"), "Promet Prodaja" to (2 to "500")),
            zbirovi,
        )
    }

    @Test
    fun nadjiPoBroju() {
        assertEquals("Sok", zurnal.nadji("AAA-AAA-2")?.zahtev?.items?.single()?.name)
        assertNull(zurnal.nadji("nepostoji"))
    }

    @Test
    fun neispravanDatum() {
        assertFailsWith<IllegalArgumentException> { FilterZurnala(od = "18.11.2026") }
    }
}
