package rs.omniit.hospes.esir.cli

import io.ktor.client.HttpClient
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.PfrVeza
import rs.omniit.hospes.esir.TaxCoreP12
import rs.omniit.hospes.esir.VpfrPodesavanja
import rs.omniit.hospes.esir.VpfrVeza
import rs.omniit.hospes.esir.bezbedanPrikazAdrese
import rs.omniit.hospes.esir.napraviZaJvm
import rs.omniit.hospes.esir.napraviZaJvmSaSertifikatom
import java.io.File

/** Otvorena veza sa PFR-om i HTTP klijent koji se zatvara posle upotrebe. */
internal class OtvorenaVeza(val veza: PfrVeza, private val http: HttpClient, val opis: String) : AutoCloseable {
    override fun close() = http.close()
}

/**
 * Otvara vezu sa PFR-om izabranim opcijom `--pfr lpfr|vpfr` (podrazumevano `lpfr`).
 * Podešavanja se čitaju iz `config/local.json`; tajne se ne ispisuju.
 */
internal fun otvoriPfr(argumenti: List<String>): OtvorenaVeza =
    when (val vrsta = vrednostOpcije(argumenti, "--pfr") ?: "lpfr") {
        "lpfr" -> {
            val p = ucitajLpfrPodesavanja()
            val http = EsirHttp.napraviZaJvm()
            OtvorenaVeza(
                LpfrVeza(p.baseUrl, http),
                http,
                "L-PFR: ${bezbedanPrikazAdrese(p.baseUrl)} (token iz adrese se ne ispisuje)",
            )
        }
        "vpfr" -> otvoriVpfr(ucitajVpfrPodesavanja())
        else -> prekini("Nepoznat PFR „$vrsta“. Dozvoljeno: --pfr lpfr ili --pfr vpfr")
    }

private fun otvoriVpfr(p: VpfrPodesavanja): OtvorenaVeza {
    val fajl = File(p.pfxPath!!)
    if (!fajl.exists()) prekini("Nema sertifikata ${fajl.path} (vpfr.pfxPath u config/local.json).")
    val lozinka = p.pfxPassword!!.toCharArray()
    val sertifikat = try {
        TaxCoreP12(fajl.readBytes(), lozinka)
    } catch (e: Exception) {
        prekini("Sertifikat ${fajl.path} ne može da se otvori. Proveri vpfr.pfxPassword. (${e.message})")
    }

    val izSertifikata = sertifikat.adresaVpfr
    val izPodesavanja = p.baseUrl
    val adresa = izSertifikata ?: izPodesavanja
        ?: prekini("Adresa V-PFR-a nije u sertifikatu, a nije ni upisana u vpfr.baseUrl.")
    if (izSertifikata != null && izPodesavanja != null && izSertifikata.trimEnd('/') != izPodesavanja.trimEnd('/')) {
        println("Napomena: adresa iz sertifikata ($izSertifikata) se razlikuje od vpfr.baseUrl; koristi se adresa iz sertifikata.")
    }

    val http = EsirHttp.napraviZaJvmSaSertifikatom(sertifikat.keyStore, lozinka)
    val izvor = if (izSertifikata != null) "adresa iz sertifikata" else "adresa iz podešavanja"
    return OtvorenaVeza(
        VpfrVeza(adresa, p.pac!!, http),
        http,
        "V-PFR: ${bezbedanPrikazAdrese(adresa)} ($izvor), sertifikat ${sertifikat.uid ?: "?"}, PIB ${sertifikat.pib ?: "?"}",
    )
}

private fun ucitajVpfrPodesavanja(): VpfrPodesavanja {
    val vpfr = ucitajPodesavanja().vpfr ?: prekini("U config/local.json nedostaje deo \"vpfr\".")
    val problemi = vpfr.proveri()
    if (problemi.isNotEmpty()) {
        prekini("Podešavanja za V-PFR nisu ispravna:\n" + problemi.joinToString("\n") { "  - $it" })
    }
    return vpfr
}
