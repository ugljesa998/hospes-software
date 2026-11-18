package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KatalogTest {
    private fun d(s: String) = Decimal4.parse(s)

    private val espresso = Artikal("101", "Espresso", d("180"), "Ђ", kategorija = "Kafa")
    private val sok = Artikal("201", "Coca-Cola 0,25", d("250"), "Ђ", gtin = "5449000000996", kategorija = "Sokovi")
    private val sir = Artikal("301", "Sir gauda", d("1199.99"), "Е", jedinicaMere = "kg")

    private fun katalog() = KatalogUMemoriji(listOf(espresso, sok, sir))

    @Test
    fun nazivNaRacunuSadrziJedinicuMere() {
        assertEquals("Espresso /kom", espresso.nazivNaRacunu)
        assertEquals("Sir gauda /kg", sir.nazivNaRacunu)
    }

    @Test
    fun pretragaPoNazivuSifriGtinuIKategoriji() {
        val k = katalog()
        assertEquals(listOf("101"), k.pretrazi("espr").map { it.sifra })
        assertEquals(listOf("201"), k.pretrazi("5449000000996").map { it.sifra })
        assertEquals(listOf("201"), k.pretrazi("sokovi").map { it.sifra })
        assertEquals(3, k.pretrazi("").size)
        assertEquals(sok, k.poSifriIliGtinu("5449000000996"))
        assertEquals(espresso, k.poSifriIliGtinu("101"))
        assertNull(k.poSifriIliGtinu("999"))
    }

    @Test
    fun promenaCene() {
        val k = katalog()
        assertEquals("200", k.promeniCenu("101", d("200")).cena.toString())
        assertEquals("200", k.nadji("101")!!.cena.toString())
        assertFailsWith<IllegalArgumentException> { k.promeniCenu("999", d("1")) }
    }

    @Test
    fun stavkaIzArtikla() {
        val s = sir.uStavku(kolicina = d("0.250"))
        assertEquals("Sir gauda /kg", s.name)
        assertEquals("300", s.totalAmount.toString())
        assertEquals(listOf("Е"), s.labels)

        val naProdaji = espresso.uStavku(kolicina = d("2"), cena = d("150"), popustProcenat = d("10"))
        assertEquals("135", naProdaji.unitPrice.toString(), "cena promenjena na prodaji, pa popust")
        assertEquals("5449000000996", sok.uStavku().gtin)
    }

    @Test
    fun neispravniArtikli() {
        val k = katalog()
        assertFailsWith<IllegalArgumentException> { k.sacuvaj(espresso.copy(sifra = "")) }
        assertFailsWith<IllegalArgumentException> { k.sacuvaj(espresso.copy(cena = d("1.005"))) }
        assertFailsWith<IllegalArgumentException> { k.sacuvaj(espresso.copy(oznaka = "")) }
        assertFailsWith<IllegalArgumentException> { k.sacuvaj(espresso.copy(sifra = "102", gtin = "5449000000996")) }
        assertTrue(Artikal("1", "X", d("1"), "A", gtin = "5449000000997").proveri().any { "kontrolnu cifru" in it })
    }

    @Test
    fun gtinKontrolnaCifra() {
        assertNull(Gtin.proveri("5449000000996"))
        assertNull(Gtin.proveri("96385074"), "GTIN-8")
        assertNull(Gtin.proveri("036000291452"), "UPC-A / GTIN-12")
        assertTrue(Gtin.proveri("5449000000997")!!.contains("kontrolnu"))
        assertTrue(Gtin.proveri("12345")!!.contains("cifara"))
        assertTrue(Gtin.proveri("54490000009AB")!!.contains("cifara"))
    }

    @Test
    fun izvozIUvozCsv() {
        val csv = KatalogCsv.izvoz(katalog().svi())
        val redovi = csv.trim().lines()
        assertEquals("sifra;naziv;jedinica_mere;cena;oznaka;gtin;kategorija", redovi[0])
        assertEquals("101;Espresso;kom;180,00;Ђ;;Kafa", redovi[1])
        assertEquals("301;Sir gauda;kg;1199,99;Е;;", redovi[3])

        val uvoz = KatalogCsv.uvoz(csv)
        assertEquals(emptyList(), uvoz.greske)
        assertEquals(katalog().svi(), uvoz.artikli)
    }

    @Test
    fun uvozIzExcelaSaGreskama() {
        val csv = "﻿SIFRA;Naziv;Cena;Oznaka;Jedinica_mere\r\n" +
            "1;Pivo 0,5;1.250,50;Ђ;kom\r\n" +
            "2;;100;Ђ;kom\r\n" +
            "3;Sok;abc;Ђ;kom\r\n" +
            "1;Duplikat;100;Ђ;kom\r\n" +
            "\r\n" +
            "4;Voda;80;Ђ;\r\n"
        val uvoz = KatalogCsv.uvoz(csv)
        assertEquals(listOf("1", "4"), uvoz.artikli.map { it.sifra })
        assertEquals("1250.5", uvoz.artikli[0].cena.toString())
        assertEquals("kom", uvoz.artikli[1].jedinicaMere, "prazna jedinica mere je „kom“")
        assertEquals(3, uvoz.greske.size)
        assertTrue(uvoz.greske[0].startsWith("Red 3:") && "naziv" in uvoz.greske[0])
        assertTrue(uvoz.greske[2].startsWith("Red 5:") && "ponavlja" in uvoz.greske[2])
    }

    @Test
    fun uvozBezObaveznihKolona() {
        val uvoz = KatalogCsv.uvoz("sifra;naziv\n1;X\n")
        assertTrue(uvoz.artikli.isEmpty())
        assertTrue("cena" in uvoz.greske.single() && "oznaka" in uvoz.greske.single())
    }

    @Test
    fun uvozMenjaPostojeceIDodajeNove() {
        val k = katalog()
        val n = k.uvezi(KatalogCsv.uvoz("sifra;naziv;cena;oznaka\n101;Espresso dupli;220;Ђ\n401;Čaj;150;Ђ\n"))
        assertEquals(2, n)
        assertEquals("Espresso dupli", k.nadji("101")!!.naziv)
        assertEquals(4, k.svi().size)
    }
}
