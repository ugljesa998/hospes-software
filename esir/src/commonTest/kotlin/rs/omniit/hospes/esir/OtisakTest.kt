package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tekst računa vrste Obuka, kako ga vraća PFR (skraćen). */
internal val TEKST_OBUKA = """
======== ОВО НИЈЕ ФИСКАЛНИ РАЧУН =======
              RS115423827
-------------ОБУКА ПРОДАЈА--------------
Espresso /kom (A)
       180,00          2          360,00
----------------------------------------
Укупан износ:                     360,00
========================================
        ОВО НИЈЕ ФИСКАЛНИ РАЧУН
========================================
ПФР број рачуна:    7EHXTU6T-7EHXTU6T-31
========================================
======== ОВО НИЈЕ ФИСКАЛНИ РАЧУН =======
""".trimStart('\n')

/** Tekst fiskalnog računa vrste Promet (skraćen). */
internal val TEKST_PROMET = """
============ ФИСКАЛНИ РАЧУН ============
-------------ПРОМЕТ ПРОДАЈА-------------
Kafa /kom (A)
       300,00          1          300,00
========================================
ПФР број рачуна:    7EHXTU6T-7EHXTU6T-11
======== КРАЈ ФИСКАЛНОГ РАЧУНА =========
""".trimStart('\n')

class OtisakTest {
    private val qr = QrMatrica(21, BooleanArray(21 * 21))

    @Test
    fun upozorenjaKrupnoIQrPreZavrsneLinije() {
        val delovi = Otisak.delovi(TEKST_OBUKA, qr, dodatak = listOf("Повраћај:                         140,00"))

        assertEquals(3, delovi.count { it is DeoOtiska.Upozorenje }, "upozorenje na vrhu, u sredini i na kraju")
        assertTrue(delovi.filterIsInstance<DeoOtiska.Upozorenje>().all { it.tekst == "ОВО НИЈЕ ФИСКАЛНИ РАЧУН" })
        val iQr = delovi.indexOfFirst { it is DeoOtiska.QrKod }
        assertIs<DeoOtiska.Upozorenje>(delovi[iQr + 1], "QR kod je pre završne linije")
        assertEquals(DeoOtiska.Red("========================================"), delovi[iQr - 1])
        assertEquals(DeoOtiska.Red("Повраћај:                         140,00"), delovi.last(), "dodatak ESIR-a ide posle teksta PFR-a")
        assertEquals(DeoOtiska.Red(""), delovi[delovi.size - 2], "prazan red pre dodatka")
        assertEquals(DeoOtiska.Red("Espresso /kom (A)"), delovi[3], "razmaci na kraju reda se ne crtaju")
    }

    @Test
    fun fiskalniRacunQrPreKraja() {
        val delovi = Otisak.delovi(TEKST_PROMET, qr)
        assertEquals(0, delovi.count { it is DeoOtiska.Upozorenje })
        assertIs<DeoOtiska.QrKod>(delovi[delovi.size - 2])
        assertEquals(DeoOtiska.Red("======== КРАЈ ФИСКАЛНОГ РАЧУНА ========="), delovi.last())
    }

    @Test
    fun tekstSaWindowsKrajemRedaIzVpfra() {
        val delovi = Otisak.delovi(TEKST_PROMET.replace("\n", "\r\n"), qr)
        assertEquals(Otisak.delovi(TEKST_PROMET, qr).size, delovi.size)
        assertTrue(delovi.filterIsInstance<DeoOtiska.Red>().none { '\r' in it.tekst })
    }

    @Test
    fun bezQrKoda() {
        assertTrue(Otisak.delovi(TEKST_PROMET, null).none { it is DeoOtiska.QrKod })
    }

    @Test
    fun prelamanjeUpozorenja() {
        assertEquals(listOf("ОВО НИЈЕ ФИСКАЛНИ РАЧУН"), Otisak.prelomi("ОВО НИЈЕ ФИСКАЛНИ РАЧУН", 24))
        assertEquals(listOf("ОВО НИЈЕ", "ФИСКАЛНИ РАЧУН"), Otisak.prelomi("ОВО НИЈЕ ФИСКАЛНИ РАЧУН", 20))
        assertEquals(listOf("ABCD", "EFGH", "IJ"), Otisak.prelomi("ABCDEFGHIJ", 4), "predugačka reč se seče")
    }

    @Test
    fun qrModuliIzSlike() {
        // Mali QR (21 × 21): tri „oka“ i nešto podataka, nacrtan 4 piksela po modulu, sa belim rubom od 3 piksela.
        val n = 21
        val moduli = BooleanArray(n * n)
        fun oko(x0: Int, y0: Int) {
            for (y in 0 until 7) for (x in 0 until 7) {
                moduli[(y0 + y) * n + x0 + x] = x == 0 || y == 0 || x == 6 || y == 6 || (x in 2..4 && y in 2..4)
            }
        }
        oko(0, 0); oko(14, 0); oko(0, 14)
        for (i in listOf(8 * n + 8, 9 * n + 10, 12 * n + 15, 20 * n + 20, 10 * n + 20)) moduli[i] = true
        val p = 4
        val ivica = 3
        val strana = n * p + 2 * ivica

        val procitano = assertNotNull(QrMatrica.izSlike(strana, strana) { x, y ->
            val mx = (x - ivica) / p
            val my = (y - ivica) / p
            x >= ivica && y >= ivica && mx < n && my < n && moduli[my * n + mx]
        })
        assertEquals(21, procitano.velicina)
        for (y in 0 until n) for (x in 0 until n) assertEquals(moduli[y * n + x], procitano[x, y], "modul $x,$y")
    }

    @Test
    fun slikaKojaNijeQr() {
        assertNull(QrMatrica.izSlike(10, 10) { _, _ -> true }, "pun kvadrat")
        assertNull(QrMatrica.izSlike(10, 10) { _, _ -> false }, "prazna slika")
    }

    @Test
    fun escPosSlikaURedovimaITrakama() {
        val r = Raster(16, 3)
        r[0, 0] = true // prva tačka levo: najviši bit prvog bajta
        r[15, 0] = true // poslednja tačka: najniži bit drugog bajta
        r[8, 2] = true

        val bajtovi = EscPos.slika(r, redovaPoTraci = 2)
        val ocekivano = listOf(
            0x1D, 0x76, 0x30, 0, 2, 0, 2, 0, // traka 1: 2 bajta u redu, 2 reda
            0x80, 0x01,
            0x00, 0x00,
            0x1D, 0x76, 0x30, 0, 2, 0, 1, 0, // traka 2: 1 red
            0x00, 0x80,
        ).map { it.toByte() }.toByteArray()
        assertContentEquals(ocekivano, bajtovi)

        val racun = EscPos.racun(r)
        assertContentEquals(EscPos.POCETAK, racun.copyOfRange(0, 2))
        assertContentEquals(EscPos.ODSECANJE, racun.copyOfRange(racun.size - 4, racun.size))
    }
}
