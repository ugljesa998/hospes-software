package rs.omniit.hospes.esir.cli

import io.ktor.client.HttpClient
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.FiskalizovanRacun
import rs.omniit.hospes.esir.IzdatRacun
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Placanje
import rs.omniit.hospes.esir.RezultatRacuna
import rs.omniit.hospes.esir.SifreStanja
import rs.omniit.hospes.esir.StatusOdgovor
import rs.omniit.hospes.esir.Stavka
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZahtevZaRacun
import rs.omniit.hospes.esir.bezbedanPrikazAdrese
import rs.omniit.hospes.esir.naziv
import rs.omniit.hospes.esir.napraviZaJvm
import rs.omniit.hospes.esir.noviRequestId
import java.io.File
import java.util.Base64

/** Folder u koji se čuvaju izdati računi (zahtev + odgovor, tekst, QR kod). Nije u Git-u. */
private val folderRacuna = File("racuni")

/**
 * `racun [--vrsta obuka|promet|predracun] [--placanje gotovina] [--oznaka A]`:
 * izdaje račun „Test kafa“, 1 × 300 RSD. Podrazumevano je Obuka Prodaja, gotovina, kao treći `curl` test.
 */
internal suspend fun komandaRacun(argumenti: List<String>) {
    val vrsta = when (val v = vrednostOpcije(argumenti, "--vrsta") ?: "obuka") {
        "obuka" -> VrstaRacuna.OBUKA
        "promet" -> VrstaRacuna.PROMET
        "predracun" -> VrstaRacuna.PREDRACUN
        else -> prekini("Nepoznata vrsta „$v“. Dozvoljeno: obuka, promet, predracun")
    }
    val nacinPlacanja = nacinPlacanja(argumenti) ?: NacinPlacanja.GOTOVINA
    val trazenaOznaka = vrednostOpcije(argumenti, "--oznaka") ?: "A"

    saLpfrom { veza, status ->
        val stope = status.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }
        val stopa = stope.firstOrNull { it.label == trazenaOznaka }
            ?: prekini(
                "Poreska oznaka $trazenaOznaka ne postoji u važećim stopama L-PFR-a. " +
                    "Dostupne: ${stope.joinToString(", ") { "${it.label} (${it.rate}%)" }}",
            )
        val iznos = Decimal4.parse("300")
        val zahtev = ZahtevZaRacun(
            invoiceType = vrsta,
            transactionType = TipTransakcije.PRODAJA,
            payment = listOf(Placanje(iznos, nacinPlacanja)),
            items = listOf(Stavka("Test kafa", Decimal4.parse("1"), iznos, iznos, listOf(stopa.label))),
            cashier = "Hospes test",
        )
        red("Poreska oznaka", "${stopa.label} (${stopa.rate}%, iz statusa L-PFR-a)")
        izdajIPrikazi(
            veza, status, zahtev,
            "${vrsta.naziv} Prodaja: „Test kafa“ 1 × 300 RSD, ${nacinPlacanja.naziv.lowercase()}",
        )
    }
}

/** `iz-predracuna <PFR broj predračuna> [--placanje gotovina]`: Promet Prodaja uz poziv na predračun. */
internal suspend fun komandaIzPredracuna(argumenti: List<String>) {
    val broj = argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }
        ?: prekini("Upotreba: iz-predracuna <PFR broj predračuna> [--placanje gotovina|kartica|...]")
    val original = ucitajIzdat(broj)
    val placanje = nacinPlacanja(argumenti)?.let { nacin ->
        listOf(Placanje(original.zahtev.payment.fold(Decimal4.NULA) { zbir, p -> zbir + p.amount }, nacin))
    }
    val zahtev = try {
        original.zahtevZaPrometIzPredracuna(placanje)
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }

    saLpfrom { veza, status ->
        val nacini = zahtev.payment.joinToString(", ") { it.paymentType.naziv.lowercase() }
        izdajIPrikazi(veza, status, zahtev, "Promet Prodaja iz predračuna $broj, $nacini")
    }
}

private fun nacinPlacanja(argumenti: List<String>): NacinPlacanja? =
    when (val p = vrednostOpcije(argumenti, "--placanje")) {
        null -> null
        "gotovina" -> NacinPlacanja.GOTOVINA
        "kartica" -> NacinPlacanja.PLATNA_KARTICA
        "cek" -> NacinPlacanja.CEK
        "prenos" -> NacinPlacanja.PRENOS_NA_RACUN
        "vaucer" -> NacinPlacanja.VAUCER
        "instant" -> NacinPlacanja.INSTANT_PLACANJE
        "drugo" -> NacinPlacanja.DRUGO_BEZGOTOVINSKO
        else -> prekini("Nepoznat način plaćanja „$p“. Dozvoljeno: gotovina, kartica, cek, prenos, vaucer, instant, drugo")
    }

/** `refundacija <PFR broj računa> --kupac 20:123456789`: refundacija celog sačuvanog računa. */
internal suspend fun komandaRefundacija(argumenti: List<String>) {
    val broj = argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }
        ?: prekini("Upotreba: refundacija <PFR broj računa> --kupac <šifra:broj>, npr. --kupac 20:123456789")
    val kupac = vrednostOpcije(argumenti, "--kupac")
        ?: prekini("Refundacija traži ID kupca koji prima novac, npr. --kupac 20:123456789 (20 = broj lične karte)")
    val original = ucitajIzdat(broj)
    val zahtev = try {
        original.zahtevZaRefundaciju(kupac)
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }

    saLpfrom { veza, status ->
        izdajIPrikazi(veza, status, zahtev, "${zahtev.invoiceType.naziv} Refundacija za $broj, kupac $kupac")
    }
}

/** `kopija <PFR broj računa>`: kopija sačuvanog računa vrste Promet (prodaje ili refundacije). */
internal suspend fun komandaKopija(argumenti: List<String>) {
    val broj = argumenti.getOrNull(1) ?: prekini("Upotreba: kopija <PFR broj računa>")
    val original = ucitajIzdat(broj)
    val zahtev = try {
        original.zahtevZaKopiju()
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }

    saLpfrom { veza, status ->
        izdajIPrikazi(veza, status, zahtev, "Kopija ${zahtev.transactionType.naziv} za $broj")
    }
}

/** `lista`: računi sačuvani u folderu `racuni/`, od najnovijeg. */
internal fun komandaLista() {
    val zapisi = folderRacuna.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { EsirHttp.json.decodeFromString(IzdatRacun.serializer(), f.readText()) }.getOrNull() }
        .sortedByDescending { it.racun.totalCounter ?: 0 }
    if (zapisi.isEmpty()) {
        println("Nema sačuvanih računa u ${folderRacuna.path}/.")
        return
    }
    println("PFR broj računa".padEnd(26) + "Brojač".padEnd(10) + "Vrsta".padEnd(24) + "Ukupno".padEnd(10) + "Poziva se na")
    zapisi.forEach { z ->
        println(
            z.racun.invoiceNumber.padEnd(26) +
                (z.racun.invoiceCounter ?: "-").padEnd(10) +
                z.oznaka.padEnd(24) +
                (z.racun.totalAmount?.toString() ?: "-").padEnd(10) +
                (z.zahtev.referentDocumentNumber ?: ""),
        )
    }
}

/** `provera <RequestId>`: traži poslednji račun po RequestId, bez izdavanja novog. */
internal suspend fun komandaProvera(argumenti: List<String>) {
    val requestId = argumenti.getOrNull(1) ?: prekini("Upotreba: provera <RequestId>")
    val podesavanja = ucitajLpfrPodesavanja()

    EsirHttp.napraviZaJvm().use { http ->
        val racun = try {
            LpfrVeza(podesavanja.baseUrl, http).racunPoRequestId(requestId)
        } catch (e: EsirGreska) {
            prekini(e.message ?: e.toString())
        } catch (e: IllegalArgumentException) {
            prekini(e.message ?: e.toString())
        }
        if (racun == null) {
            println("L-PFR nema račun sa RequestId $requestId.")
            println("Napomena: L-PFR čuva samo poslednji račun, pa ova provera važi za poslednji izdati račun.")
        } else {
            println("Račun sa RequestId $requestId JESTE izdat.")
            ispisiRacun(racun)
        }
    }
}

/** Učita podešavanja, otvori vezu, proveri status i PIN, pa izvrši [blok]. */
private suspend fun saLpfrom(blok: suspend (LpfrVeza, StatusOdgovor) -> Unit) {
    val podesavanja = ucitajLpfrPodesavanja()
    println("L-PFR: ${bezbedanPrikazAdrese(podesavanja.baseUrl)} (token iz adrese se ne ispisuje)")
    println()

    EsirHttp.napraviZaJvm().use { http: HttpClient ->
        val veza = LpfrVeza(podesavanja.baseUrl, http)
        val status = try {
            veza.status()
        } catch (e: EsirGreska) {
            prekini(e.message ?: e.toString())
        }
        if (status.isPinRequired == true) {
            prekini("L-PFR traži PIN. Prvo pokreni:  ./gradlew -q :esir-cli:run --args=\"pin\"")
        }
        blok(veza, status)
    }
}

private suspend fun izdajIPrikazi(veza: LpfrVeza, status: StatusOdgovor, zahtev: ZahtevZaRacun, opis: String) {
    val jezik = LpfrVeza.JEZIK_SRPSKI_CIRILICA.takeIf { it in status.supportedLanguages }
        ?: status.supportedLanguages.firstOrNull()
    val requestId = noviRequestId()

    println("Izdajem:              $opis")
    red("Jezik računa", jezik)
    red("RequestId", requestId)
    println()

    when (val rezultat = veza.izdajRacun(zahtev, requestId, jezik)) {
        is RezultatRacuna.Izdat -> {
            if (rezultat.pronadjenNaknadno) {
                println("Veza je prekinuta, ali je račun pronađen po RequestId. Nije izdat dvaput.")
            }
            println("RAČUN JE IZDAT.")
            val izdat = IzdatRacun(requestId, zahtev, rezultat.racun)
            ispisiRacun(izdat.racun)
            sacuvaj(izdat)
        }
        is RezultatRacuna.Odbijen -> {
            val opisGreske = rezultat.greska?.let { g ->
                buildString {
                    append(g.message ?: "bez poruke")
                    g.modelState.forEach { polje ->
                        polje.errors.forEach { kod ->
                            append("\n  ${polje.property.ifEmpty { "(ceo zahtev)" }}: $kod ${SifreStanja.za(kod).opis}")
                        }
                    }
                }
            } ?: rezultat.telo
            prekini("L-PFR je odbio račun (HTTP ${rezultat.httpStatus}). Račun NIJE izdat.\n$opisGreske")
        }
        is RezultatRacuna.NijeIzdat ->
            prekini("Račun NIJE izdat: ${rezultat.razlog}. Bezbedno je pokušati ponovo.")
        is RezultatRacuna.NepoznatoStanje ->
            prekini(
                "NE ZNA SE da li je račun izdat: ${rezultat.razlog}.\n" +
                    "Ne izdaj račun ponovo. Prvo proveri:\n" +
                    "  ./gradlew -q :esir-cli:run --args=\"provera ${rezultat.requestId}\"",
            )
    }
}

private fun ispisiRacun(racun: FiskalizovanRacun) {
    red("PFR broj računa", racun.invoiceNumber)
    red("Brojač računa", racun.invoiceCounter)
    red("PFR vreme", racun.sdcDateTime)
    red("Ukupno", racun.totalAmount?.let { "$it RSD" })
    racun.taxItems.forEach { porez ->
        red("Porez ${porez.label}", "${porez.categoryName ?: ""} ${porez.rate ?: "?"}% = ${porez.amount} RSD".trim())
    }
    red("Link za proveru", racun.verificationUrl)
    red("Poruka PFR-a", racun.messages)
    println()
    println("Tekst računa (journal), onako kako ga je vratio L-PFR:")
    println(racun.journal ?: "(L-PFR nije vratio tekst računa)")
}

private fun imeFajla(brojRacuna: String) = brojRacuna.replace(Regex("[^A-Za-z0-9-]"), "_")

private fun sacuvaj(izdat: IzdatRacun) {
    folderRacuna.mkdirs()
    val ime = imeFajla(izdat.racun.invoiceNumber)
    File(folderRacuna, "$ime.json").writeText(EsirHttp.json.encodeToString(IzdatRacun.serializer(), izdat))
    izdat.racun.journal?.let { File(folderRacuna, "$ime.txt").writeText(it) }
    izdat.racun.verificationQRCode?.let { File(folderRacuna, "$ime.gif").writeBytes(Base64.getDecoder().decode(it)) }
    println()
    println("Sačuvano u ${folderRacuna.path}/: $ime.json (zahtev i odgovor), $ime.txt (tekst), $ime.gif (QR kod)")
}

private fun ucitajIzdat(brojRacuna: String): IzdatRacun {
    val fajl = File(folderRacuna, "${imeFajla(brojRacuna)}.json")
    if (!fajl.exists()) prekini("Nema sačuvanog računa $brojRacuna u ${folderRacuna.path}/. Spisak: komanda lista")
    return runCatching { EsirHttp.json.decodeFromString(IzdatRacun.serializer(), fajl.readText()) }.getOrElse {
        prekini(
            "Račun $brojRacuna je sačuvan starijom verzijom, bez zahteva, pa za njega ne može refundacija ni kopija. " +
                "Izdaj novi račun.",
        )
    }
}

private fun vrednostOpcije(argumenti: List<String>, ime: String): String? {
    val i = argumenti.indexOf(ime)
    return if (i >= 0) argumenti.getOrNull(i + 1) ?: prekini("Opcija $ime traži vrednost") else null
}
