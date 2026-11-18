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
        "status" -> runBlocking { komandaStatus(args.toList()) }
        "pin" -> runBlocking { komandaPin(args.toList()) }
        "racun" -> runBlocking { komandaRacun(args.toList()) }
        "refundacija" -> runBlocking { komandaRefundacija(args.toList()) }
        "kopija" -> runBlocking { komandaKopija(args.toList()) }
        "iz-predracuna" -> runBlocking { komandaIzPredracuna(args.toList()) }
        "avans" -> runBlocking { komandaAvans(args.toList()) }
        "zatvori-avans" -> runBlocking { komandaZatvoriAvans(args.toList()) }
        "konacni-racun" -> runBlocking { komandaKonacniRacun(args.toList()) }
        "ponisti" -> runBlocking { komandaPonisti(args.toList()) }
        "vaucer" -> runBlocking { komandaVaucer(args.toList()) }
        "korporativna" -> runBlocking { komandaKorporativna(args.toList()) }
        "korporativna-refundacija" -> runBlocking { komandaKorporativnaRefundacija(args.toList()) }
        "bez-naknade" -> runBlocking { komandaBezNaknade(args.toList()) }
        "katalog" -> komandaKatalog(args.toList())
        "zurnal" -> komandaZurnal(args.toList())
        "lista" -> komandaZurnal(listOf("zurnal", "--sve"))
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
        Sve komande koje rade sa PFR-om primaju --pfr lpfr (podrazumevano) ili --pfr vpfr.

        Komande:
          status                              stanje PFR-a: dostupnost, PIN, šifre stanja, poreske stope
          pin                                 PIN iz config/local.json (jednom, bez ponavljanja)
          katalog [--trazi tekst]             katalog artikala (podaci/katalog.json)
          katalog dodaj --sifra 101 --naziv 'Espresso' --cena 180 --oznaka A [--jm kom] [--gtin …] [--kategorija …]
          katalog cena <šifra> <cena> | katalog ukloni <šifra> | katalog uvoz <fajl.csv> | katalog izvoz <fajl.csv>
          racun [--vrsta obuka|promet|predracun] [--artikal 'šifra|GTIN[;količina[;cena[;popust%]]]']...
                [--stavka 'Naziv;količina;cena[;oznaka[;popust%]]']...
                [--placanje gotovina | --placanje kartica:500 ...] [--primljeno 1000] [--oznaka A] [--kupac 10:123]
                                              račun sa obračunom (bez --stavka: „Test kafa“ 1 × 300, Obuka, gotovina)
          refundacija <broj> --kupac 20:123   refundacija celog računa (Promet, Obuka, Predračun), uz ID kupca
          kopija <broj>                       kopija računa vrste Promet (kod gotovinske refundacije: red za potpis kupca)
          iz-predracuna <broj> [--placanje kartica]
                                              Promet Prodaja uz poziv na predračun
          avans --iznos 1000 [--kupac 10:123] [--prethodni <broj>] [--placanje prenos] [--oznaka A] [--sifra 11]
                [--datum-uplate gggg-mm-dd]   Avans Prodaja; sledeći avans se poziva na prethodni;
                                              datum uplate = „ESIR vreme“ kad je virman stariji (PURS 9.2)
          zatvori-avans <broj poslednjeg avansa> [--kupac 10:123]
                                              Avans Refundacija za zbir svih avansa (ne daje se kupcu)
          konacni-racun <broj AR> --iznos 2500 [--naziv "..."] [--placanje kartica]
                                              Promet Prodaja posle avansa; PFR-u ide samo ostatak
          ponisti <broj> [--pib 123456789]    poništavanje računa sa greškom: refundacija sa sopstvenim PIB-om (9.3)
          vaucer --iznos 6000 [--oznaka A]    prodaja jednonamenskog vaučera (9.5)
          korporativna --pib 123456789 --kartica 6666666 [--stavka '...']...
                                              prodaja korporacijskom karticom, plaćanje vaučer (9.4)
          korporativna-refundacija --pib 123456789 [--mesec 2026-11]
                                              zbirna mesečna refundacija za kupca, iz žurnala (9.4)
          bez-naknade [--stavka '...']...     promet bez naknade, šifra 00: i drugo bezgotovinsko (9.7)
          (načini plaćanja: gotovina, kartica, cek, prenos, vaucer, instant, drugo)
          zurnal[--od gggg-mm-dd] [--do gggg-mm-dd] [--sve] [--vrsta promet] [--tip refundacija]
                 [--kupac 20:123] [--placanje kartica] [--trazi tekst]
                                              elektronski žurnal (podrazumevano poslednjih 30 dana)
          zurnal --prikazi <broj>             ceo račun iz žurnala
          lista                               ceo žurnal (isto što i zurnal --sve)
          provera <RequestId>                 da li je poslednji račun sa tim RequestId izdat
        """.trimIndent()
    )
}

internal fun ucitajPodesavanja(): Podesavanja {
    val fajl = File(putanjaPodesavanja)
    if (!fajl.exists()) {
        prekini("Nema fajla ${fajl.path}. Kopiraj config/example.json u config/local.json i upiši vrednosti.")
    }
    return Podesavanja.izJsona(fajl.readText())
}

internal fun ucitajLpfrPodesavanja(): LpfrPodesavanja {
    val lpfr = ucitajPodesavanja().lpfr ?: prekini("U config/local.json nedostaje deo \"lpfr\".")
    val problemi = lpfr.proveri()
    if (problemi.isNotEmpty()) {
        prekini("Podešavanja nisu ispravna:\n" + problemi.joinToString("\n") { "  - $it" })
    }
    return lpfr
}

private suspend fun komandaStatus(argumenti: List<String>) {
    otvoriPfr(argumenti).use { otvorena ->
        val veza = otvorena.veza
        println(otvorena.opis)
        println()

        val dostupan = veza.attention()
        red("Dostupan (attention)", if (dostupan) "da" else "NE")
        if (!dostupan) prekini("${veza.naziv} se ne javlja. Proveri internet i podešavanja u config/local.json.")

        val status = try {
            veza.status()
        } catch (e: EsirGreska) {
            prekini(e.message ?: e.toString())
        }
        ispisiStatus(status, veza.naziv)
    }
}

private suspend fun komandaPin(argumenti: List<String>) {
    if (vrednostOpcije(argumenti, "--pfr") == "vpfr") {
        prekini("V-PFR nema PIN: prijava se radi sertifikatom i PAC-om.")
    }
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

private fun ispisiStatus(s: StatusOdgovor, nazivPfra: String) {
    red("Vreme na $nazivPfra-u", s.sdcDateTime)
    red("Kartica/sertifikat", s.uid)
    red("Potreban PIN", s.isPinRequired?.let { if (it) "da" else "ne" } ?: "ne postoji (V-PFR)".takeIf { nazivPfra == "V-PFR" })
    red("Potreban audit", s.auditRequired?.let { if (it) "da" else "ne" })
    red("Poslednji račun", s.lastInvoiceNumber)
    red("Uređaj", listOfNotNull(s.make, s.model, s.softwareVersion).joinToString(" ").ifEmpty { null })
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
        println("Poreske stope: $nazivPfra ih nije vratio")
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
