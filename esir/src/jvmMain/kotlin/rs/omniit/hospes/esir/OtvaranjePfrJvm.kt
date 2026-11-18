package rs.omniit.hospes.esir

import java.io.File

/**
 * Otvara vezu sa PFR-om na desktopu (Windows, macOS) po podešavanjima iz `config/local.json`.
 *
 * - L-PFR: adresa iz `lpfr.baseUrl`.
 * - V-PFR: sertifikat iz `vpfr.pfxPath` (putanja relativna u odnosu na [koren]), lozinka i PAC;
 *   adresa V-PFR-a se čita iz sertifikata, a `vpfr.baseUrl` je rezerva.
 *
 * Baca [IllegalArgumentException] sa porukom na srpskom kada podešavanja ili sertifikat nisu ispravni.
 * Tajne (token, lozinka, PAC) se nikad ne stavljaju u poruke ni u [OtvorenPfr.opis].
 */
fun otvoriPfr(podesavanja: Podesavanja, vrsta: VrstaPfra, koren: File = File(".")): OtvorenPfr = when (vrsta) {
    VrstaPfra.LPFR -> {
        val p = podesavanja.lpfr ?: throw IllegalArgumentException("U podešavanjima nedostaje deo \"lpfr\".")
        proveriPodesavanja("L-PFR", p.proveri())
        val http = EsirHttp.napraviZaJvm()
        OtvorenPfr(LpfrVeza(p.baseUrl, http), http, "L-PFR: ${bezbedanPrikazAdrese(p.baseUrl)}")
    }
    VrstaPfra.VPFR -> {
        val p = podesavanja.vpfr ?: throw IllegalArgumentException("U podešavanjima nedostaje deo \"vpfr\".")
        proveriPodesavanja("V-PFR", p.proveri())
        val fajl = File(p.pfxPath!!).let { if (it.isAbsolute) it else File(koren, it.path) }
        require(fajl.exists()) { "Nema sertifikata ${fajl.path} (vpfr.pfxPath)." }
        val lozinka = p.pfxPassword!!.toCharArray()
        val sertifikat = try {
            TaxCoreP12(fajl.readBytes(), lozinka)
        } catch (e: Exception) {
            throw IllegalArgumentException("Sertifikat ${fajl.path} ne može da se otvori. Proveri vpfr.pfxPassword.", e)
        }
        val adresa = sertifikat.adresaVpfr ?: p.baseUrl
            ?: throw IllegalArgumentException("Adresa V-PFR-a nije u sertifikatu, a nije ni upisana u vpfr.baseUrl.")
        val http = EsirHttp.napraviZaJvmSaSertifikatom(sertifikat.keyStore, lozinka)
        val izvor = if (sertifikat.adresaVpfr != null) "adresa iz sertifikata" else "adresa iz podešavanja"
        OtvorenPfr(
            VpfrVeza(adresa, p.pac!!, http),
            http,
            "V-PFR: ${bezbedanPrikazAdrese(adresa)} ($izvor), sertifikat ${sertifikat.uid ?: "?"}, PIB ${sertifikat.pib ?: "?"}",
        )
    }
}

private fun proveriPodesavanja(naziv: String, problemi: List<String>) {
    require(problemi.isEmpty()) { "Podešavanja za $naziv nisu ispravna:\n" + problemi.joinToString("\n") { "  - $it" } }
}
