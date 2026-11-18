package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecijalniSlucajeviTest {
    private fun d(s: String) = Decimal4.parse(s)
    private fun stavka(naziv: String, kol: String, cena: String, oznaka: String = "Ђ") =
        Obracun.stavka(naziv, d(kol), d(cena), listOf(oznaka))

    private fun izdat(zahtev: ZahtevZaRacun, broj: String, vreme: String = "2026-11-18T12:00:00+01:00", tin: String? = "RS111111111") =
        IzdatRacun("rid-$broj", zahtev, FiskalizovanRacun(invoiceNumber = broj, sdcDateTime = vreme, tin = tin))

    private val prodaja = ZahtevZaRacun(
        invoiceType = VrstaRacuna.PROMET,
        transactionType = TipTransakcije.PRODAJA,
        payment = listOf(Placanje(d("12000000"), NacinPlacanja.GOTOVINA)),
        items = listOf(stavka("Пардон штапићи (ком)", "400000", "30")),
    )

    // 9.3 Poništavanje

    @Test
    fun ponistavanjeSaSopstvenimPibom() {
        val p = izdat(prodaja, "Y9ANWU3Y-Y9ANWU3Y-7").zahtevZaPonistavanje()
        assertEquals(TipTransakcije.REFUNDACIJA, p.transactionType)
        assertEquals("10:111111111", p.buyerId, "PIB prodavca iz odgovora PFR-a, bez „RS“")
        assertEquals("Y9ANWU3Y-Y9ANWU3Y-7", p.referentDocumentNumber)
        assertEquals(prodaja.items, p.items, "sve stavke pogrešnog računa")
        assertEquals(emptyList(), p.proveri())
    }

    @Test
    fun ponistavanjeSamoProdajeIBezPibaNe() {
        val refundacija = izdat(prodaja, "A-B-1").zahtevZaRefundaciju("20:123")
        assertFailsWith<IllegalArgumentException> { izdat(refundacija, "A-B-2").zahtevZaPonistavanje() }
        assertFailsWith<IllegalArgumentException> { izdat(prodaja, "A-B-3", tin = null).zahtevZaPonistavanje() }
        assertEquals("10:123456789", izdat(prodaja, "A-B-4", tin = null).zahtevZaPonistavanje("123456789").buyerId)
    }

    // 9.4 Korporacijske kartice

    private val gorivo = listOf(stavka("Бензин БМБ /л", "10", "150"))

    @Test
    fun prodajaKorporacijskomKarticom() {
        val z = KorporativnaKartica.zahtevZaProdaju(gorivo, "123456789", "6666666")
        assertEquals("10:123456789", z.buyerId)
        assertEquals("50:6666666", z.buyerCostCenterId)
        assertEquals(listOf(Placanje(d("1500"), NacinPlacanja.VAUCER)), z.payment)
        assertEquals(emptyList(), z.proveri())
    }

    @Test
    fun korporacijskaKarticaBezVaucerJeGreska() {
        val z = KorporativnaKartica.zahtevZaProdaju(gorivo, "123456789", "6666666")
            .copy(payment = listOf(Placanje(d("1500"), NacinPlacanja.GOTOVINA)))
        assertTrue(z.proveri().any { "vaučer" in it })
    }

    @Test
    fun mesecnaZbirnaRefundacija() {
        val z1 = izdat(KorporativnaKartica.zahtevZaProdaju(gorivo, "123456789", "6666666"), "Y-Y-150", "2026-03-15T23:55:11+01:00")
        val z2 = izdat(KorporativnaKartica.zahtevZaProdaju(gorivo, "123456789", "7777777"), "Y-Y-180", "2026-03-31T23:55:11+02:00")
        val drugiKupac = izdat(KorporativnaKartica.zahtevZaProdaju(gorivo, "987654321", "1"), "Y-Y-190", "2026-03-20T10:00:00+01:00")
        val april = izdat(KorporativnaKartica.zahtevZaProdaju(gorivo, "123456789", "6666666"), "Y-Y-200", "2026-04-01T08:00:00+02:00")
        val obican = izdat(prodaja.copy(buyerId = "10:123456789"), "Y-Y-170", "2026-03-20T10:00:00+01:00")

        val r = KorporativnaKartica.zahtevZaMesecnuRefundaciju(
            listOf(z1, z2, drugiKupac, april, obican), "123456789", od = "2026-03-01", doDana = "2026-03-31",
        )
        assertEquals(TipTransakcije.REFUNDACIJA, r.transactionType)
        assertEquals("10:123456789", r.buyerId)
        assertEquals("60:01032026_31032026", r.buyerCostCenterId)
        assertEquals("Y-Y-180", r.referentDocumentNumber, "poslednji račun kupcu u periodu, bez obzira na karticu")
        val s = r.items.single()
        assertEquals("20", s.quantity.toString())
        assertEquals("3000", s.totalAmount.toString())
        assertEquals(listOf(Placanje(d("3000"), NacinPlacanja.VAUCER)), r.payment)
        assertEquals(emptyList(), r.proveri())
    }

    @Test
    fun mesecnaRefundacijaBezRacunaJeGreska() {
        assertFailsWith<IllegalArgumentException> {
            KorporativnaKartica.zahtevZaMesecnuRefundaciju(emptyList(), "123456789", "2026-03-01", "2026-03-31")
        }
    }

    // 9.5 Jednonamenski vaučer

    @Test
    fun jednonamenskiVaucer() {
        val s = JednonamenskiVaucer.stavka("Ђ", d("6000"))
        assertEquals("20: Једнонаменски ваучер (Ђ)", s.name)
        assertEquals("1", s.quantity.toString())
        val z = prodaja.copy(items = listOf(s), payment = listOf(Placanje(d("6000"), NacinPlacanja.GOTOVINA)))
        assertEquals(emptyList(), z.proveri())
        val dva = z.copy(items = listOf(s, s), payment = listOf(Placanje(d("12000"), NacinPlacanja.GOTOVINA)))
        assertTrue(dva.proveri().any { "jedan jednonamenski vaučer" in it })
    }

    // 9.7 Promet bez naknade

    @Test
    fun prometBezNaknade() {
        val z = PrometBezNaknade.zahtev(listOf(stavka("Хаљина", "1", "6000")))
        assertEquals("00: Хаљина", z.items.single().name)
        assertEquals(listOf(Placanje(d("6000"), NacinPlacanja.DRUGO_BEZGOTOVINSKO)), z.payment)
        assertEquals(emptyList(), z.proveri())
    }

    @Test
    fun prometBezNaknadePravila() {
        val pomesano = prodaja.copy(items = listOf(stavka("00: Poklon", "1", "100"), stavka("Kafa", "1", "100")))
        assertTrue(pomesano.proveri().any { "plaćenim stavkama" in it })
        val gotovina = PrometBezNaknade.zahtev(listOf(stavka("Poklon", "1", "100")))
            .copy(payment = listOf(Placanje(d("100"), NacinPlacanja.GOTOVINA)))
        assertTrue(gotovina.proveri().any { "drugo bezgotovinsko" in it })
    }

    // 9.6 Kopija refundacije sa potpisom i 9.2 ESIR vreme

    @Test
    fun potpisKupcaSamoNaKopijiGotovinskeRefundacije() {
        val refundacija = izdat(prodaja, "A-B-1").zahtevZaRefundaciju("20:0023456")
        val kopija = izdat(refundacija, "A-B-2").zahtevZaKopiju()
        assertTrue(kopija.redZaPotpisKupca()!!.startsWith("Потпис купца"))
        val karticom = kopija.copy(payment = listOf(Placanje(d("12000000"), NacinPlacanja.PLATNA_KARTICA)))
        assertNull(karticom.redZaPotpisKupca())
        assertNull(izdat(prodaja, "A-B-3").zahtevZaKopiju().redZaPotpisKupca())
    }

    @Test
    fun esirVremeZaStarijuUplatu() {
        assertEquals("2026-11-17T00:00:00", esirVremeZaDatumUplate("2026-11-17"))
        assertFailsWith<IllegalArgumentException> { esirVremeZaDatumUplate("17.11.2026") }
    }
}
