package rs.omniit.hospes.esir.cli

import kotlinx.coroutines.runBlocking
import rs.omniit.hospes.esir.Esir
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.LpfrPodesavanja
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.RezultatPina
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
        "pin" -> runBlocking { komandaPin() }
        "racun" -> runBlocking { komandaRacun(args.toList()) }
        "refundacija" -> runBlocking { komandaRefundacija(args.toList()) }
        "kopija" -> runBlocking { komandaKopija(args.toList()) }
        "lista" -> komandaLista()
        "provera" -> runBlocking { komandaProvera(args.toList()) }
        else -> {
            println("Nepoznata komanda: $komanda")
            ispisiPomoc()
            exitProcess(2)
        }
    }
    // OkHttp drži svoje niti aktivnim još ~60 s posle poslednjeg zahteva, pa bi komanda „visila“ minut.
    exitProcess(0)
}

private fun ispisiPomoc() {
    println(
        """
        Komande:
          status                              stanje L-PFR-a: dostupnost, PIN, šifre stanja, poreske stope
          pin                                 PIN iz config/local.json (jednom, bez ponavljanja)
          racun [--vrsta obuka|promet] [--oznaka A]
                                              „Test kafa“ 1 × 300 RSD, gotovina (podrazumevano Obuka)
          refundacija <broj> --kupac 20:123   refundacija celog računa, uz ID kupca
          kopija <broj>                       kopija računa vrste Promet
          lista                               računi sačuvani u folderu racuni/
          provera <RequestId>                 da li je poslednji račun sa tim RequestId izdat
        """.trimIndent()
    )
}

internal fun ucitajLpfrPodesavanja(): LpfrPodesavanja {
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

private suspend fun komandaPin() {
    val podesavanja = ucitajLpfrPodesavanja()
    val pin = podesavanja.pin ?: prekini("U config/local.json nije upisan lpfr.pin.")
    println("L-PFR: ${bezbedanPrikazAdrese(podesavanja.baseUrl)} (token iz adrese se ne ispisuje)")
    println()

    EsirHttp.napraviZaJvm().use { http ->
        val veza = LpfrVeza(podesavanja.baseUrl, http)
        val rezultat = try {
            veza.unesiPin(pin)
        } catch (e: EsirGreska) {
            prekini(e.message ?: e.toString())
        }

        when (rezultat) {
            RezultatPina.Prihvacen -> {
                println("PIN je prihvaćen (0100). L-PFR ga pamti dok se kartica ne izvadi.")
                val sifre = try {
                    veza.status().gsc
                } catch (e: EsirGreska) {
                    prekini("PIN je prihvaćen, ali provera statusa posle toga nije uspela: ${e.message}")
                }
                println()
                println("Šifre stanja posle PIN-a:")
                sifre.forEach { kod -> println("  $kod  ${SifreStanja.za(kod).opis}") }
            }
            RezultatPina.NijePotreban ->
                println("PIN nije poslat: L-PFR već ima ispravan PIN.")
            is RezultatPina.NijePoslat -> {
                val kod = rezultat.sifra?.kod?.let { " ($it)" } ?: ""
                val savet = if (rezultat.sifra?.kod == "1300") {
                    "\nNa portalu: Развојно окружење → Развојни Л-ПФР → „Уметни картицу“."
                } else ""
                prekini("PIN nije poslat: ${rezultat.razlog}$kod.$savet")
            }
            is RezultatPina.Odbijen -> {
                val s = rezultat.sifra
                val savet = when {
                    s.trajna -> "Kartica je zaključana i ne može da se koristi."
                    s.kod == "2100" ->
                        "Program NE pokušava ponovo. Proveri PIN na portalu (kolona ПИН).\n" +
                            "Posle 5 pogrešnih PIN-ova kartica se zaključava. Na razvojnom L-PFR-u brojač se " +
                            "resetuje dugmetom „Ресетуј бројач ПИН-а“."
                    else -> "Program ne pokušava ponovo."
                }
                prekini("PIN nije prihvaćen: ${s.kod} ${s.opis}.\n$savet")
            }
        }
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

internal fun red(naziv: String, vrednost: String?) {
    println("${(naziv + ":").padEnd(22)}${vrednost ?: "-"}")
}

internal fun prekini(poruka: String): Nothing {
    System.err.println("Greška: $poruka")
    exitProcess(1)
}
