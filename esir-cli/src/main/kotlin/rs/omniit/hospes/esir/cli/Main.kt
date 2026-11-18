package rs.omniit.hospes.esir.cli

import kotlinx.coroutines.runBlocking
import rs.omniit.hospes.esir.Esir
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.LpfrPodesavanja
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.SifreStanja
import rs.omniit.hospes.esir.StatusOdgovor
import rs.omniit.hospes.esir.VrstaSifre
import rs.omniit.hospes.esir.bezbedanPrikazAdrese
import rs.omniit.hospes.esir.napraviZaJvm
import java.io.File
import kotlin.system.exitProcess

/** Putanja do podešavanja; može da se promeni promenljivom okruženja HOSPES_CONFIG. */
private val putanjaPodesavanja: String = System.getenv("HOSPES_CONFIG") ?: "config/local.json"

fun main(args: Array<String>) {
    println("Hospes ESIR ${Esir.VERSION} (TaxCore ${Esir.PROTOCOL})")

    when (val komanda = args.firstOrNull()) {
        null, "pomoc" -> ispisiPomoc()
        "status" -> runBlocking { komandaStatus() }
        else -> {
            println("Nepoznata komanda: $komanda")
            ispisiPomoc()
            exitProcess(2)
        }
    }
}

private fun ispisiPomoc() {
    println(
        """
        Komande:
          status   provera L-PFR-a: dostupnost, PIN, šifre stanja, poreske stope
          pin      slanje PIN-a (u izradi)
          racun    izdavanje Training Sale računa (u izradi)
        """.trimIndent()
    )
}

private fun ucitajLpfrPodesavanja(): LpfrPodesavanja {
    val fajl = File(putanjaPodesavanja)
    if (!fajl.exists()) {
        prekini("Nema fajla ${fajl.path}. Kopiraj config/example.json u config/local.json i upiši vrednosti.")
    }
    val lpfr = Podesavanja.izJsona(fajl.readText()).lpfr
        ?: prekini("U ${fajl.path} nedostaje deo \"lpfr\".")
    val problemi = lpfr.proveri()
    if (problemi.isNotEmpty()) {
        prekini("Podešavanja nisu ispravna:\n" + problemi.joinToString("\n") { "  - $it" })
    }
    return lpfr
}

private suspend fun komandaStatus() {
    val podesavanja = ucitajLpfrPodesavanja()
    println("L-PFR: ${bezbedanPrikazAdrese(podesavanja.baseUrl)} (token iz adrese se ne ispisuje)")
    println()

    EsirHttp.napraviZaJvm().use { http ->
        val veza = LpfrVeza(podesavanja.baseUrl, http)

        val dostupan = veza.attention()
        red("Dostupan (attention)", if (dostupan) "da" else "NE")
        if (!dostupan) prekini("L-PFR se ne javlja. Proveri internet i adresu u config/local.json.")

        val status = try {
            veza.status()
        } catch (e: EsirGreska) {
            prekini(e.message ?: e.toString())
        }
        ispisiStatus(status)
    }
}

private fun ispisiStatus(s: StatusOdgovor) {
    red("Vreme na L-PFR-u", s.sdcDateTime)
    red("Kartica (UID)", s.uid)
    red("Potreban PIN", s.isPinRequired?.let { if (it) "da" else "ne" })
    red("Potreban audit", s.auditRequired?.let { if (it) "da" else "ne" })
    red("Poslednji račun", s.lastInvoiceNumber)
    red("L-PFR", listOfNotNull(s.make, s.model, s.softwareVersion).joinToString(" ").ifEmpty { null })
    red("Jezici računa", s.supportedLanguages.joinToString(", ").ifEmpty { null })

    println()
    println("Šifre stanja:")
    if (s.gsc.isEmpty()) println("  (nema)")
    s.gsc.forEach { kod ->
        val sifra = SifreStanja.za(kod)
        println("  $kod  ${vrsta(sifra.vrsta).padEnd(10)}  ${sifra.opis}")
    }
    if (s.mssc.isNotEmpty()) println("  Šifre proizvođača L-PFR-a: ${s.mssc.joinToString(", ")}")

    println()
    val grupa = s.currentTaxRates
    if (grupa == null) {
        println("Poreske stope: L-PFR ih nije vratio")
    } else {
        println("Poreske stope (važe od ${grupa.validFrom ?: "?"}, grupa ${grupa.groupId ?: "?"}):")
        grupa.taxCategories.sortedBy { it.orderId ?: Int.MAX_VALUE }.forEach { kategorija ->
            val stope = kategorija.taxRates.joinToString(", ") { "${it.label} = ${it.rate}%" }
            println("  ${kategorija.name.padEnd(10)}  $stope")
        }
    }
    println()
    println("Napomena: ovo su stope iz sandbox okruženja. ESIR uvek koristi stope koje vrati PFR.")
}

private fun vrsta(v: VrstaSifre) = when (v) {
    VrstaSifre.INFO -> "info"
    VrstaSifre.UPOZORENJE -> "upozorenje"
    VrstaSifre.GRESKA -> "greška"
}

private fun red(naziv: String, vrednost: String?) {
    println("${(naziv + ":").padEnd(22)}${vrednost ?: "-"}")
}

private fun prekini(poruka: String): Nothing {
    System.err.println("Greška: $poruka")
    exitProcess(1)
}
