package rs.omniit.hospes.esir

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Položaj QR koda na otisku, u tačkama. */
data class PolozajQr(val x: Int, val y: Int, val strana: Int) {
    val mm: Double get() = strana.toDouble() / SirinaPapira.TACAKA_PO_MM
}

/** Račun nacrtan u tačke štampača, sa merama koje se proveravaju (širina, QR, veličina slova). */
class NacrtanOtisak(
    val raster: Raster,
    val papir: SirinaPapira,
    val qr: PolozajQr?,
    /** Koliko znakova staje u red običnim slovima. */
    val kolona: Int,
    val velicinaSlova: Float,
    val velicinaSlovaUpozorenja: Float,
) {
    /** Slika kako izgleda na papiru: cela širina papira, sa belim ivicama koje štampač ne dohvata. */
    fun png(): ByteArray {
        val sirinaPapira = papir.mm * SirinaPapira.TACAKA_PO_MM
        val ivica = (sirinaPapira - raster.sirina) / 2
        val vrh = 3 * SirinaPapira.TACAKA_PO_MM
        val slika = BufferedImage(sirinaPapira, raster.visina + 2 * vrh, BufferedImage.TYPE_BYTE_GRAY)
        val g = slika.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, slika.width, slika.height)
        g.color = Color(0xCC, 0xCC, 0xCC)
        g.drawRect(0, 0, slika.width - 1, slika.height - 1)
        g.dispose()
        val crna = Color.BLACK.rgb
        for (y in 0 until raster.visina) for (x in 0 until raster.sirina) {
            if (raster[x, y]) slika.setRGB(ivica + x, vrh + y, crna)
        }
        return ByteArrayOutputStream().also { ImageIO.write(slika, "png", it) }.toByteArray()
    }
}

/**
 * Crta otisak u tačke termalnog štampača (Java AWT; radi i bez ekrana, npr. na serveru ili u testu).
 *
 * - Tekst: slova jednake širine, tako da najduži red (bar 40 znakova, kao kod PFR-a) zauzme celu širinu štampe.
 * - „ОВО НИЈЕ ФИСКАЛНИ РАЧУН“: duplo veća slova (visina i širina), prelomljeno u dva reda ako ne staje.
 * - QR kod: 40–50 mm (veličinu bira [Otisak.stranaQr] da se pouzdano čita), moduli puni crni kvadrati, belo iznad i ispod.
 */
object CrtanjeOtiska {
    private const val RAZMAK_QR = 2 * SirinaPapira.TACAKA_PO_MM

    /** Siva svetlija od ovoga ostaje bela; tamnija postaje crna tačka. */
    private const val PRAG = 150

    private val KONTEKST = FontRenderContext(null, true, true)

    fun nacrtaj(delovi: List<DeoOtiska>, papir: SirinaPapira): NacrtanOtisak {
        val sirina = papir.tacaka
        val kolona = maxOf(Otisak.KOLONA, delovi.filterIsInstance<DeoOtiska.Red>().maxOfOrNull { it.tekst.length } ?: 0)
        val obican = slovaZaKolone(kolona, sirina)
        val krupan = obican.deriveFont(obican.size2D * 2)
        val metrika = obican.getLineMetrics("ОВОgЖ", KONTEKST)
        val metrikaKrupnog = krupan.getLineMetrics("ОВОgЖ", KONTEKST)
        val visinaReda = (metrika.ascent + metrika.descent).roundToInt() + 1
        val visinaKrupnog = (metrikaKrupnog.ascent + metrikaKrupnog.descent).roundToInt() + 2

        val upozorenja = delovi.filterIsInstance<DeoOtiska.Upozorenje>().associateWith { Otisak.prelomi(it.tekst, kolona / 2) }
        val visina = delovi.sumOf { deo ->
            when (deo) {
                is DeoOtiska.Red -> visinaReda
                is DeoOtiska.Upozorenje -> upozorenja.getValue(deo).size * visinaKrupnog
                is DeoOtiska.QrKod -> Otisak.stranaQr(deo.matrica.velicina, sirina) + 2 * RAZMAK_QR
            }
        }

        val slika = BufferedImage(sirina, maxOf(visina, 1), BufferedImage.TYPE_BYTE_GRAY)
        val g = slika.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, slika.width, slika.height)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        var y = 0
        var qr: PolozajQr? = null
        delovi.forEach { deo ->
            when (deo) {
                is DeoOtiska.Red -> {
                    g.font = obican
                    g.drawString(deo.tekst, 0f, y + metrika.ascent)
                    y += visinaReda
                }
                is DeoOtiska.Upozorenje -> {
                    g.font = krupan
                    upozorenja.getValue(deo).forEach { red ->
                        val w = krupan.getStringBounds(red, KONTEKST).width.toFloat()
                        g.drawString(red, (sirina - w) / 2, y + metrikaKrupnog.ascent)
                        y += visinaKrupnog
                    }
                }
                is DeoOtiska.QrKod -> {
                    y += RAZMAK_QR
                    val qrStrana = Otisak.stranaQr(deo.matrica.velicina, sirina)
                    val x0 = (sirina - qrStrana) / 2
                    nacrtajQr(g, deo.matrica, x0, y, qrStrana)
                    qr = PolozajQr(x0, y, qrStrana)
                    y += qrStrana + RAZMAK_QR
                }
            }
        }
        g.dispose()

        val raster = Raster(sirina, slika.height)
        val podaci = slika.raster
        for (yy in 0 until slika.height) for (x in 0 until sirina) raster[x, yy] = podaci.getSample(x, yy, 0) < PRAG
        return NacrtanOtisak(raster, papir, qr, kolona, obican.size2D, krupan.size2D)
    }

    /** Moduli QR koda: ivice modula su na celim tačkama (bez sivih prelaza), isto kao u [Otisak.stranaQr]. */
    private fun nacrtajQr(g: java.awt.Graphics2D, m: QrMatrica, x0: Int, y0: Int, strana: Int) {
        val ivice = Otisak.iviceModula(m.velicina, strana)
        for (my in 0 until m.velicina) for (mx in 0 until m.velicina) {
            if (m[mx, my]) g.fillRect(x0 + ivice[mx], y0 + ivice[my], ivice[mx + 1] - ivice[mx], ivice[my + 1] - ivice[my])
        }
    }

    /** Slova jednake širine (ćirilica i latinica) takve veličine da [kolona] znakova stane u [sirina] tačaka. */
    private fun slovaZaKolone(kolona: Int, sirina: Int): Font {
        val probna = Font(Font.MONOSPACED, Font.PLAIN, 100)
        val sirinaNa100 = probna.getStringBounds("Ж".repeat(kolona), KONTEKST).width
        return probna.deriveFont((100 * sirina / sirinaNa100 * 0.995).toFloat())
    }

    /** QR kod iz slike koju vraća PFR (GIF). */
    fun qrIzSlike(bajtovi: ByteArray): QrMatrica? {
        val slika = ImageIO.read(ByteArrayInputStream(bajtovi)) ?: return null
        return QrMatrica.izSlike(slika.width, slika.height) { x, y ->
            val rgb = slika.getRGB(x, y)
            val alfa = rgb ushr 24
            val svetlina = ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / 3
            alfa > 127 && svetlina < 128
        }
    }
}

/**
 * Otisak računa iz žurnala: tekst od PFR-a, QR kod iz odgovora PFR-a i redovi koje je ESIR dodao.
 * Baca [IllegalStateException] ako račun ima QR kod koji ne može da se pripremi za štampu (račun bez QR koda ne sme da se izda kupcu).
 */
fun FolderZurnal.otisak(brojRacuna: String, papir: SirinaPapira): NacrtanOtisak? {
    val izdat = nadji(brojRacuna) ?: return null
    val tekst = izdat.racun.journal ?: return null
    val qr = izdat.racun.verificationQRCode?.let { kod ->
        CrtanjeOtiska.qrIzSlike(Base64.getDecoder().decode(kod))
            ?: throw IllegalStateException("QR kod računa $brojRacuna nije moguće pripremiti za štampu")
    }
    return CrtanjeOtiska.nacrtaj(Otisak.delovi(tekst, qr, dodatak(brojRacuna)), papir)
}

/** Štampač kome ESIR šalje račun. */
interface Stampac {
    /** Štampa otisak; vraća kratak opis za korisnika (gde je odštampan). */
    fun stampaj(ime: String, otisak: NacrtanOtisak): String
}

/**
 * Virtuelni štampač, dok nema pravog: umesto papira upisuje `<ime>-<papir>mm.png` (kako bi račun izgledao)
 * i `<ime>-<papir>mm.escpos` (tačno ono što bi otišlo štampaču; može se kasnije poslati pravom štampaču).
 */
class VirtuelniStampac(val folder: File) : Stampac {
    override fun stampaj(ime: String, otisak: NacrtanOtisak): String {
        folder.mkdirs()
        val osnova = "${ime.replace(Regex("[^A-Za-z0-9-]"), "_")}-${otisak.papir.mm}mm"
        val png = File(folder, "$osnova.png").apply { writeBytes(otisak.png()) }
        File(folder, "$osnova.escpos").writeBytes(EscPos.racun(otisak.raster))
        return "Virtuelni štampač (${otisak.papir.naziv}): ${png.path}"
    }
}
