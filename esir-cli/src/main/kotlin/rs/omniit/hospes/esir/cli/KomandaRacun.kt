package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.FiskalizovanRacun
import rs.omniit.hospes.esir.IzdatRacun
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Obracun
import rs.omniit.hospes.esir.PfrVeza
import rs.omniit.hospes.esir.Placanje
import rs.omniit.hospes.esir.PoreskaStopa
import rs.omniit.hospes.esir.RezultatRacuna
import rs.omniit.hospes.esir.SifreStanja
import rs.omniit.hospes.esir.StatusOdgovor
import rs.omniit.hospes.esir.Stavka
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZahtevZaRacun
import rs.omniit.hospes.esir.naziv
import rs.omniit.hospes.esir.noviRequestId
import java.io.File
import java.util.Base64

/** Folder u koji se čuvaju izdati računi (zahtev + odgovor, tekst, QR kod). Nije u Git-u. */
internal val folderRacuna = File("racuni")

/**
 * `racun [--vrsta obuka|promet|predracun] [--stavka "Naziv;količina;cena[;oznaka[;popust%]]"]...
 *        [--placanje gotovina | --placanje kartica:500 ...] [--primljeno 1000] [--oznaka A] [--kupac 10:123]`
 *
 * Bez `--stavka` izdaje „Test kafa“ 1 × 300 RSD; bez `--placanje` plaća se gotovinom.
 * `--stavka` i `--placanje` mogu da se navedu više puta. Jedno plaćanje sme biti bez iznosa (dobija ostatak).
 * `--primljeno` je gotovina koju je kupac dao; razlika je kusur (ne šalje se PFR-u).
 */
internal suspend fun komandaRacun(argumenti: List<String>) {
    val vrsta = when (val v = vrednostOpcije(argumenti, "--vrsta") ?: "obuka") {
        "obuka" -> VrstaRacuna.OBUKA
        "promet" -> VrstaRacuna.PROMET
        "predracun" -> VrstaRacuna.PREDRACUN
        else -> prekini("Nepoznata vrsta „$v“. Dozvoljeno: obuka, promet, predracun")
    }
    val podrazumevanaOznaka = vrednostOpcije(argumenti, "--oznaka") ?: "A"
    val opisiStavki = vrednostiOpcije(argumenti, "--stavka").ifEmpty { listOf("Test kafa;1;300") }
    val delovi = vrednostiOpcije(argumenti, "--placanje").ifEmpty { listOf("gotovina") }.map(::deoNaplate)
    val primljeno = vrednostOpcije(argumenti, "--primljeno")?.let { broj(it, "--primljeno") }
    val kupac = vrednostOpcije(argumenti, "--kupac")

    saPfrom(argumenti) { veza, status ->
        val stope = status.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }
        val stavke = opisiStavki.map { stavkaIzOpisa(it, podrazumevanaOznaka, stope) }
        val ukupno = Obracun.ukupno(stavke)
        val naplata = try {
            Obracun.naplata(ukupno, delovi, primljeno)
        } catch (e: IllegalArgumentException) {
            prekini(e.message ?: e.toString())
        }
        val zahtev = ZahtevZaRacun(
            invoiceType = vrsta,
            transactionType = TipTransakcije.PRODAJA,
            payment = naplata.placanja,
            items = stavke,
            cashier = "Hospes test",
            buyerId = kupac,
        )

        println("Obračun:")
        stavke.forEach { s ->
            println("  ${s.name.take(24).padEnd(24)} ${s.quantity.toString().padStart(7)} × ${s.unitPrice.zaStampu().padStart(10)} = ${s.totalAmount.zaStampu().padStart(10)}  [${s.labels.joinToString()}]")
        }
        red("Ukupno", "${ukupno.zaStampu()} RSD")
        naplata.placanja.forEach { red("  ${it.paymentType.naziv}", "${it.amount.zaStampu()} RSD") }
        if (primljeno != null) red("  Primljeno / kusur", "${primljeno.zaStampu()} / ${naplata.kusur.zaStampu()} RSD")
        println()

        izdajIPrikazi(
            veza, status, zahtev,
            "${vrsta.naziv} Prodaja: ${stavke.size} stavk${if (stavke.size == 1) "a" else "e"}, ukupno ${ukupno.zaStampu()} RSD",
            dodatak = if (naplata.kusur > Decimal4.NULA) listOf("Повраћај:" + naplata.kusur.zaStampu().padStart(31)) else emptyList(),
        )
    }
}

/** Stavka iz opisa „Naziv;količina;cena[;oznaka[;popust%]]“, npr. „Kafa;3;250;A;10“ ili „Sir /kg;0.250;1199.99“. */
private fun stavkaIzOpisa(opis: String, podrazumevanaOznaka: String, stope: List<PoreskaStopa>): Stavka {
    val delovi = opis.split(";").map { it.trim() }
    if (delovi.size !in 3..5 || delovi[0].isEmpty()) {
        prekini("Neispravna stavka „$opis“. Format: \"Naziv;količina;cena[;oznaka[;popust%]]\", npr. \"Kafa;3;250;A;10\"")
    }
    val oznaka = delovi.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: podrazumevanaOznaka
    if (stope.none { it.label == oznaka }) {
        prekini("Poreska oznaka $oznaka ne postoji u važećim stopama PFR-a. Dostupne: ${stope.joinToString(", ") { "${it.label} (${it.rate}%)" }}")
    }
    return try {
        Obracun.stavka(
            naziv = delovi[0],
            kolicina = broj(delovi[1], "količina u „$opis“"),
            jedinicnaCena = broj(delovi[2], "cena u „$opis“"),
            oznake = listOf(oznaka),
            popustProcenat = delovi.getOrNull(4)?.removeSuffix("%")?.let { broj(it, "popust u „$opis“") } ?: Decimal4.NULA,
        )
    } catch (e: IllegalArgumentException) {
        prekini("Stavka „$opis“: ${e.message}")
    }
}

/** Deo naplate iz „gotovina“ ili „kartica:500“. */
private fun deoNaplate(opis: String): Pair<NacinPlacanja, Decimal4?> {
    val naziv = opis.substringBefore(':')
    val iznos = opis.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { broj(it, "iznos u „$opis“") }
    return nacinIzNaziva(naziv) to iznos
}

private fun broj(tekst: String, sta: String): Decimal4 =
    runCatching { Decimal4.parse(tekst.replace(',', '.')) }.getOrElse { prekini("Neispravan broj za $sta: „$tekst“") }

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

    saPfrom(argumenti) { veza, status ->
        val nacini = zahtev.payment.joinToString(", ") { it.paymentType.naziv.lowercase() }
        izdajIPrikazi(veza, status, zahtev, "Promet Prodaja iz predračuna $broj, $nacini")
    }
}

internal fun nacinPlacanja(argumenti: List<String>): NacinPlacanja? =
    vrednostOpcije(argumenti, "--placanje")?.let(::nacinIzNaziva)

private fun nacinIzNaziva(p: String): NacinPlacanja =
    when (p) {
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

    saPfrom(argumenti) { veza, status ->
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

    saPfrom(argumenti) { veza, status ->
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
    val requestId = argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }
        ?: prekini("Upotreba: provera <RequestId> [--pfr lpfr|vpfr]")

    otvoriPfr(argumenti).use { otvorena ->
        val veza = otvorena.veza
        println(otvorena.opis)
        val racun = try {
            veza.racunPoRequestId(requestId)
        } catch (e: EsirGreska) {
            prekini(e.message ?: e.toString())
        } catch (e: IllegalArgumentException) {
            prekini(e.message ?: e.toString())
        }
        if (racun == null) {
            println("${veza.naziv} nema račun sa RequestId $requestId.")
            println("Napomena: PFR čuva samo poslednji račun, pa ova provera važi za poslednji izdati račun.")
        } else {
            println("Račun sa RequestId $requestId JESTE izdat.")
            ispisiRacun(racun)
        }
    }
}

/** Otvori vezu sa izabranim PFR-om (`--pfr`), proveri status i PIN, pa izvrši [blok]. */
internal suspend fun saPfrom(argumenti: List<String>, blok: suspend (PfrVeza, StatusOdgovor) -> Unit) {
    otvoriPfr(argumenti).use { otvorena ->
        println(otvorena.opis)
        println()
        val veza = otvorena.veza
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

/**
 * Izdaje račun, ispisuje ga i čuva u `racuni/`. Vraća izdat račun; na grešku prekida program.
 *
 * @param dodatak redovi koje ESIR štampa ispod teksta PFR-a, a koji se ne šalju PFR-u (npr. kod avansa).
 */
internal suspend fun izdajIPrikazi(
    veza: PfrVeza,
    status: StatusOdgovor,
    zahtev: ZahtevZaRacun,
    opis: String,
    dodatak: List<String> = emptyList(),
): IzdatRacun {
    val jezik = PfrVeza.JEZIK_SRPSKI_CIRILICA.takeIf { it in status.supportedLanguages }
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
            if (dodatak.isNotEmpty()) {
                println(NASLOV_DODATKA)
                dodatak.forEach(::println)
            }
            sacuvaj(izdat, dodatak)
            return izdat
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
            prekini("${veza.naziv} je odbio račun (HTTP ${rezultat.httpStatus}). Račun NIJE izdat.\n$opisGreske")
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
    println("Tekst računa (journal), onako kako ga je vratio PFR:")
    println(racun.journal ?: "(PFR nije vratio tekst računa)")
}

private const val NASLOV_DODATKA = "---- ESIR dodaje na odštampan račun (ne šalje se PFR-u) ----"

private fun imeFajla(brojRacuna: String) = brojRacuna.replace(Regex("[^A-Za-z0-9-]"), "_")

private fun sacuvaj(izdat: IzdatRacun, dodatak: List<String>) {
    folderRacuna.mkdirs()
    val ime = imeFajla(izdat.racun.invoiceNumber)
    File(folderRacuna, "$ime.json").writeText(EsirHttp.json.encodeToString(IzdatRacun.serializer(), izdat))
    izdat.racun.journal?.let { tekst ->
        val ceo = if (dodatak.isEmpty()) tekst else tekst + "\n" + NASLOV_DODATKA + "\n" + dodatak.joinToString("\n") + "\n"
        File(folderRacuna, "$ime.txt").writeText(ceo)
    }
    izdat.racun.verificationQRCode?.let { File(folderRacuna, "$ime.gif").writeBytes(Base64.getDecoder().decode(it)) }
    println()
    println("Sačuvano u ${folderRacuna.path}/: $ime.json (zahtev i odgovor), $ime.txt (tekst), $ime.gif (QR kod)")
}

/** Sačuvan račun po PFR broju, ili `null` ako ga nema ili je sačuvan starijom verzijom. */
internal fun nadjiSacuvan(brojRacuna: String): IzdatRacun? {
    val fajl = File(folderRacuna, "${imeFajla(brojRacuna)}.json")
    if (!fajl.exists()) return null
    return runCatching { EsirHttp.json.decodeFromString(IzdatRacun.serializer(), fajl.readText()) }.getOrNull()
}

internal fun ucitajIzdat(brojRacuna: String): IzdatRacun {
    val fajl = File(folderRacuna, "${imeFajla(brojRacuna)}.json")
    if (!fajl.exists()) prekini("Nema sačuvanog računa $brojRacuna u ${folderRacuna.path}/. Spisak: komanda lista")
    return runCatching { EsirHttp.json.decodeFromString(IzdatRacun.serializer(), fajl.readText()) }.getOrElse {
        prekini(
            "Račun $brojRacuna je sačuvan starijom verzijom, bez zahteva, pa za njega ne može refundacija ni kopija. " +
                "Izdaj novi račun.",
        )
    }
}

internal fun vrednostOpcije(argumenti: List<String>, ime: String): String? {
    val i = argumenti.indexOf(ime)
    return if (i >= 0) argumenti.getOrNull(i + 1) ?: prekini("Opcija $ime traži vrednost") else null
}

/** Sve vrednosti opcije koja može da se navede više puta, npr. `--stavka A --stavka B`. */
internal fun vrednostiOpcije(argumenti: List<String>, ime: String): List<String> =
    argumenti.indices.filter { argumenti[it] == ime }.map { i ->
        argumenti.getOrNull(i + 1)?.takeUnless { it.startsWith("--") } ?: prekini("Opcija $ime traži vrednost")
    }
