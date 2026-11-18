package rs.omniit.hospes.esir

import java.io.File
import java.util.Base64

/**
 * Elektronski žurnal u folderu (desktop, komandna linija). Za svaki račun čuva:
 * - `<broj>.json`: zahtev i odgovor PFR-a (osnova žurnala, refundacija i kopija),
 * - `<broj>.txt`: tekst računa od PFR-a, sa redovima koje je dodao ESIR,
 * - `<broj>.gif`: QR kod.
 *
 * Fajlovi koje nije moguće pročitati (npr. iz starije verzije, bez zahteva) se preskaču.
 */
class FolderZurnal(val folder: File) : ElektronskiZurnal {

    override fun sacuvaj(izdat: IzdatRacun, dodatak: List<String>) {
        folder.mkdirs()
        val ime = imeFajla(izdat.racun.invoiceNumber)
        File(folder, "$ime.json").writeText(EsirHttp.json.encodeToString(IzdatRacun.serializer(), izdat))
        izdat.racun.journal?.let { tekst ->
            val ceo = if (dodatak.isEmpty()) tekst else tekst + "\n" + NASLOV_DODATKA + "\n" + dodatak.joinToString("\n") + "\n"
            File(folder, "$ime.txt").writeText(ceo)
        }
        izdat.racun.verificationQRCode?.let { File(folder, "$ime.gif").writeBytes(Base64.getDecoder().decode(it)) }
    }

    override fun nadji(brojRacuna: String): IzdatRacun? = procitaj(fajl(brojRacuna, "json"))

    override fun svi(): List<IzdatRacun> =
        folder.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull(::procitaj).sortedWith(NAJNOVIJI_PRVO)

    /** Tekst računa onako kako je sačuvan (PFR + ESIR dodatak), ili `null`. */
    fun tekstRacuna(brojRacuna: String): String? = fajl(brojRacuna, "txt").takeIf { it.exists() }?.readText()

    fun fajl(brojRacuna: String, nastavak: String): File = File(folder, "${imeFajla(brojRacuna)}.$nastavak")

    private fun procitaj(fajl: File): IzdatRacun? =
        if (!fajl.exists()) null
        else runCatching { EsirHttp.json.decodeFromString(IzdatRacun.serializer(), fajl.readText()) }.getOrNull()

    private fun imeFajla(brojRacuna: String) = brojRacuna.replace(Regex("[^A-Za-z0-9-]"), "_")

    companion object {
        const val NASLOV_DODATKA = "---- ESIR dodaje na odštampan račun (ne šalje se PFR-u) ----"
    }
}
