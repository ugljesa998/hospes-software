package rs.omniit.hospes.esir

import java.io.File

/**
 * Podešavanja kase u fajlu (`config/local.json`): čita ih komandna linija, a menja ekran „Podešavanja“.
 * Upis ide preko privremenog fajla, da nestanak struje usred upisa ne ostavi pokvaren fajl.
 */
class PodesavanjaFajl(val fajl: File) {
    /** Sačuvana podešavanja; ako fajla nema (prvo pokretanje), prazna. */
    fun ucitaj(): Podesavanja = if (fajl.exists()) Podesavanja.izJsona(fajl.readText()) else Podesavanja()

    fun sacuvaj(podesavanja: Podesavanja) {
        fajl.absoluteFile.parentFile?.mkdirs()
        val privremeni = File(fajl.absoluteFile.parentFile, fajl.name + ".tmp")
        privremeni.writeText(podesavanja.uJson())
        if (!privremeni.renameTo(fajl)) {
            fajl.delete()
            check(privremeni.renameTo(fajl)) { "Podešavanja nije moguće sačuvati u ${fajl.path}" }
        }
    }
}

/**
 * Kratak opis sertifikata za V-PFR, za proveru pre čuvanja: UID, PIB, adresa V-PFR-a i rok važenja.
 * Baca [IllegalArgumentException] sa porukom na srpskom ako fajl ne postoji ili lozinka nije dobra.
 */
fun opisSertifikata(fajl: File, lozinka: String): String {
    require(fajl.exists()) { "Nema fajla ${fajl.path}" }
    val s = try {
        TaxCoreP12(fajl.readBytes(), lozinka.toCharArray())
    } catch (e: Exception) {
        throw IllegalArgumentException("Sertifikat ne može da se otvori: lozinka nije dobra ili fajl nije .p12/.pfx")
    }
    return listOfNotNull(
        s.uid?.let { "UID $it" },
        s.pib?.let { "PIB $it" },
        s.adresaVpfr?.let { "V-PFR ${bezbedanPrikazAdrese(it)}" },
        "važi do ${s.vaziDo}",
    ).joinToString(" · ")
}
