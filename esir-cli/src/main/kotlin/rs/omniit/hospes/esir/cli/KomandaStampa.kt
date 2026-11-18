package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.EscPos
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.VirtuelniStampac
import rs.omniit.hospes.esir.otisak
import java.io.File

/** Virtuelni štampač komandne linije: `stampa/` (nije u Git-u). */
private val stampac = VirtuelniStampac(File("stampa"))

/**
 * `stampa <PFR broj> [--papir 58|80]`: račun iz žurnala na virtuelnom štampaču (alat za razvoj i proveru izgleda).
 * Bez `--papir` štampa na oba papira, da se uporede.
 *
 * Ovo nikad ne sme da ide na pravi štampač: drugi primerak fiskalnog računa se izdaje preko PFR-a (račun vrste Kopija).
 */
internal fun komandaStampa(argumenti: List<String>) {
    val broj = argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }
        ?: prekini("Upotreba: stampa <PFR broj računa> [--papir 58|80]")
    val papiri = when (val p = vrednostOpcije(argumenti, "--papir")) {
        null -> SirinaPapira.entries
        else -> listOf(SirinaPapira.entries.firstOrNull { it.mm.toString() == p.removeSuffix("mm") } ?: prekini("Papir može biti 58 ili 80"))
    }
    if (nadjiSacuvan(broj) == null) prekini("Nema računa $broj u elektronskom žurnalu. Spisak: komanda zurnal")

    papiri.forEach { papir ->
        val otisak = try {
            zurnal.otisak(broj, papir)
        } catch (e: IllegalStateException) {
            prekini(e.message ?: e.toString())
        } ?: prekini("Račun $broj nema tekst od PFR-a")
        println()
        println("Papir ${papir.naziv}: štampa ${papir.tacaka} tačaka (${papir.tacaka / SirinaPapira.TACAKA_PO_MM} mm), ${otisak.kolona} znakova u redu")
        red("Dužina računa", "%.1f cm".format(otisak.raster.visina / SirinaPapira.TACAKA_PO_MM / 10.0))
        red("QR kod", otisak.qr?.let { "%.1f mm".format(it.mm) } ?: "nema")
        red("ESC/POS", "${EscPos.racun(otisak.raster).size} bajtova (račun kao slika, pa sečenje)")
        println(stampac.stampaj(broj, otisak))
    }
    println()
    println("Otvori PNG da vidiš kako račun izgleda na papiru; QR kod sa slike može da se skenira telefonom.")
}
