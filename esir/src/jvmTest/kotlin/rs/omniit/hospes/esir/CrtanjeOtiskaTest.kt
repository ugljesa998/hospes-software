package rs.omniit.hospes.esir

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrtanjeOtiskaTest {
    private val folder: File = Files.createTempDirectory("otisak").toFile()

    @AfterTest
    fun obrisi() {
        folder.deleteRecursively()
    }

    /** Adresa za proveru dužine kao kod PFR-a: QR od 97 modula, kao na pravim računima iz sandbox-a. */
    private val adresa = "https://sandbox.suf.purs.gov.rs/v/?vl=" +
        "AzdFSFhUVTZUN0VIWFRVNlQfAAAABAAAAGAqnQAAAAAAAAABoe6eqdADAADE7YS9IBkswi6f%2BzuntU".repeat(10)

    /** QR kod kao GIF, kao što ga vraća PFR: 4 piksela po modulu, bez belog ruba. */
    private fun gif(tekst: String, pikselaPoModulu: Int = 4): Pair<ByteArray, Int> {
        val m = QRCodeWriter().encode(
            tekst, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
        )
        val strana = m.width * pikselaPoModulu
        val slika = BufferedImage(strana, strana, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until strana) for (x in 0 until strana) {
            slika.setRGB(x, y, if (m[x / pikselaPoModulu, y / pikselaPoModulu]) 0x000000 else 0xFFFFFF)
        }
        return ByteArrayOutputStream().also { ImageIO.write(slika, "gif", it) }.toByteArray() to m.width
    }

    /** Čita QR kod sa otiska, kao telefon kupca. */
    private fun procitajQr(o: NacrtanOtisak): String {
        val q = assertNotNull(o.qr)
        val x0 = maxOf(0, q.x - 16)
        val y0 = maxOf(0, q.y - 16)
        val w = minOf(o.raster.sirina, q.x + q.strana + 16) - x0
        val h = minOf(o.raster.visina, q.y + q.strana + 16) - y0
        val pikseli = IntArray(w * h) { i -> if (o.raster[x0 + i % w, y0 + i / w]) 0xFF000000.toInt() else -1 }
        val bitmapa = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pikseli)))
        return QRCodeReader().decode(bitmapa, mapOf(DecodeHintType.TRY_HARDER to true)).text
    }

    @Test
    fun qrKodOd44mmKojiSeCitaNaObaPapira() {
        val (slika, moduli) = gif(adresa)
        val qr = assertNotNull(CrtanjeOtiska.qrIzSlike(slika))
        assertEquals(moduli, qr.velicina)
        assertEquals(97, moduli)
        assertTrue(moduli * 4 > 384, "originalna slika 1:1 ne bi stala na papir od 58 mm")

        for (papir in SirinaPapira.entries) {
            val o = CrtanjeOtiska.nacrtaj(Otisak.delovi(TEKST_PROMET, qr), papir)
            assertEquals(papir.tacaka, o.raster.sirina)
            val q = assertNotNull(o.qr)
            assertTrue(q.mm in 40.0..50.0, "QR ${q.mm} mm na papiru ${papir.naziv}")
            assertTrue(q.x >= 0 && q.x + q.strana <= o.raster.sirina, "QR staje u širinu štampe")
            assertEquals(adresa, procitajQr(o), "QR na papiru ${papir.naziv} se čita")
        }
    }

    @Test
    fun qrSeCitaZaRazneDuzineAdreseIObaPapira() {
        // Pravi računi: 97 modula (L-PFR) i 101 (V-PFR). Proba od 89 do 105 modula; svaki mora da se pročita i bude 40–50 mm.
        // (Veće ZXing ne čita pouzdano ni kada je kod savršeno nacrtan, pa tu nije merilo.)
        val velicine = mutableSetOf<Int>()
        for (ponavljanja in 8..11) {
            val tekst = "https://sandbox.suf.purs.gov.rs/v/?vl=" + "AzdFSFhUVTZUN0VIWFRVNlQfAAAABAAAAGAqnQAAAAAAAAABoe6eqdADAADE7YS9IBkswi6f%2BzuntU".repeat(ponavljanja) + "x".repeat(ponavljanja * 7)
            val qr = assertNotNull(CrtanjeOtiska.qrIzSlike(gif(tekst).first))
            velicine += qr.velicina
            for (papir in SirinaPapira.entries) {
                val o = CrtanjeOtiska.nacrtaj(listOf(DeoOtiska.QrKod(qr)), papir)
                val q = assertNotNull(o.qr)
                assertTrue(q.mm in 40.0..50.0 && q.strana <= papir.tacaka, "${qr.velicina} modula, ${papir.naziv}: ${q.mm} mm")
                assertEquals(tekst, procitajQr(o), "${qr.velicina} modula, ${papir.naziv}, ${q.mm} mm")
            }
        }
        assertTrue(97 in velicine && 101 in velicine, "proba pokriva prave veličine: $velicine")
    }

    @Test
    fun jednakiModuliKadaStaju() {
        assertEquals(388, Otisak.stranaQr(97, SirinaPapira.MM80.tacaka), "97 × 4 tačke = 48,5 mm na 80 mm papiru")
        val na58 = Otisak.stranaQr(97, SirinaPapira.MM58.tacaka)
        assertTrue(na58 in 320..384 && na58 != 352, "na 58 mm nema mesta za 4 tačke po modulu; 352 se ne čita pouzdano")
    }

    @Test
    fun upozorenjeDuploVecimSlovimaITekstUCeluSirinu() {
        for (papir in SirinaPapira.entries) {
            val o = CrtanjeOtiska.nacrtaj(Otisak.delovi(TEKST_OBUKA, null), papir)
            assertEquals(40, o.kolona)
            assertTrue(o.velicinaSlovaUpozorenja >= 2 * o.velicinaSlova)
        }
        val siri = CrtanjeOtiska.nacrtaj(listOf(DeoOtiska.Red("X".repeat(48))), SirinaPapira.MM80)
        assertEquals(48, siri.kolona, "duži red od 40 znakova smanjuje slova, ne seče se")
    }

    @Test
    fun otisakIzZurnalaNaVirtuelnomStampacu() {
        val (slika, _) = gif(adresa)
        val izdat = IzdatRacun(
            requestId = "abc",
            zahtev = ZahtevZaRacun(
                invoiceType = VrstaRacuna.OBUKA,
                transactionType = TipTransakcije.PRODAJA,
                payment = listOf(Placanje(Decimal4.parse("360"), NacinPlacanja.GOTOVINA)),
                items = listOf(Stavka("Espresso /kom", Decimal4.parse("2"), Decimal4.parse("180"), Decimal4.parse("360"), listOf("A"))),
            ),
            racun = FiskalizovanRacun(
                invoiceNumber = "7EHXTU6T-7EHXTU6T-31",
                sdcDateTime = "2026-11-18T13:25:38+01:00",
                journal = TEKST_OBUKA,
                verificationQRCode = Base64.getEncoder().encodeToString(slika),
            ),
        )
        val zurnal = FolderZurnal(File(folder, "racuni"))
        zurnal.sacuvaj(izdat, dodatak = listOf("Повраћај:                         140,00"))
        assertEquals(listOf("Повраћај:                         140,00"), zurnal.dodatak("7EHXTU6T-7EHXTU6T-31"), "bez razvojnog naslova")

        val o = assertNotNull(zurnal.otisak("7EHXTU6T-7EHXTU6T-31", SirinaPapira.MM58))
        assertEquals(adresa, procitajQr(o))

        val stampac = VirtuelniStampac(File(folder, "stampa"))
        val opis = stampac.stampaj("7EHXTU6T-7EHXTU6T-31", o)
        assertTrue("7EHXTU6T-7EHXTU6T-31-58mm.png" in opis, opis)
        val png = ImageIO.read(File(folder, "stampa/7EHXTU6T-7EHXTU6T-31-58mm.png"))
        assertEquals(58 * 8, png.width, "pregled je cela širina papira")
        val escPos = File(folder, "stampa/7EHXTU6T-7EHXTU6T-31-58mm.escpos").readBytes()
        assertContentEquals(EscPos.racun(o.raster), escPos)
        assertEquals(384 / 8, escPos[6].toInt(), "48 bajtova u redu = 384 tačke")
    }

    @Test
    fun racunCijiQrNijeMogucePripremitiSeNeStampa() {
        val zurnal = FolderZurnal(File(folder, "racuni"))
        val prazanGif = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_BYTE_BINARY), "gif", it)
        }.toByteArray()
        assertTrue(ImageIO.read(ByteArrayInputStream(prazanGif)) != null)
        zurnal.sacuvaj(
            IzdatRacun(
                requestId = "x",
                zahtev = ZahtevZaRacun(
                    invoiceType = VrstaRacuna.PROMET,
                    transactionType = TipTransakcije.PRODAJA,
                    payment = listOf(Placanje(Decimal4.parse("300"), NacinPlacanja.GOTOVINA)),
                    items = listOf(Stavka("Kafa /kom", Decimal4.parse("1"), Decimal4.parse("300"), Decimal4.parse("300"), listOf("A"))),
                ),
                racun = FiskalizovanRacun(
                    invoiceNumber = "AAA-BBB-1",
                    journal = TEKST_PROMET,
                    verificationQRCode = Base64.getEncoder().encodeToString(prazanGif),
                ),
            ),
        )
        assertFailsWith<IllegalStateException> { zurnal.otisak("AAA-BBB-1", SirinaPapira.MM80) }
    }
}
