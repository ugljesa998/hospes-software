package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.FiskalizovanRacun
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Placanje
import rs.omniit.hospes.esir.RezultatRacuna
import rs.omniit.hospes.esir.SifreStanja
import rs.omniit.hospes.esir.Stavka
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZahtevZaRacun
import rs.omniit.hospes.esir.bezbedanPrikazAdrese
import rs.omniit.hospes.esir.napraviZaJvm
import rs.omniit.hospes.esir.noviRequestId
import java.io.File
import java.util.Base64

/** Folder u koji se čuvaju izdati računi (tekst, QR kod, ceo odgovor). Nije u Git-u. */
private val folderRacuna = File("racuni")

/**
 * `racun [--oznaka A]`: izdaje Training Sale račun „Test kafa“, 1 × 300 RSD, gotovina.
 * Isto kao treći `curl` test, ali iz ESIR modula, sa zaštitom od duplog računa.
 */
internal suspend fun komandaRacun(argumenti: List<String>) {
    val podesavanja = ucitajLpfrPodesavanja()
    val trazenaOznaka = vrednostOpcije(argumenti, "--oznaka") ?: "A"
    println("L-PFR: ${bezbedanPrikazAdrese(podesavanja.baseUrl)} (token iz adrese se ne ispisuje)")
    println()

    EsirHttp.napraviZaJvm().use { http ->
        val veza = LpfrVeza(podesavanja.baseUrl, http)

        val status = try {
            veza.status()
        } catch (e: EsirGreska) {
            prekini(e.message ?: e.toString())
        }
        if (status.isPinRequired == true) {
            prekini("L-PFR traži PIN. Prvo pokreni:  ./gradlew -q :esir-cli:run --args=\"pin\"")
        }

        val stope = status.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }
        val stopa = stope.firstOrNull { it.label == trazenaOznaka }
            ?: prekini(
                "Poreska oznaka $trazenaOznaka ne postoji u važećim stopama L-PFR-a. " +
                    "Dostupne: ${stope.joinToString(", ") { "${it.label} (${it.rate}%)" }}",
            )
        val jezik = LpfrVeza.JEZIK_SRPSKI_CIRILICA.takeIf { it in status.supportedLanguages }
            ?: status.supportedLanguages.firstOrNull()

        val iznos = Decimal4.parse("300")
        val zahtev = ZahtevZaRacun(
            invoiceType = VrstaRacuna.OBUKA,
            transactionType = TipTransakcije.PRODAJA,
            payment = listOf(Placanje(iznos, NacinPlacanja.GOTOVINA)),
            items = listOf(
                Stavka(
                    name = "Test kafa",
                    quantity = Decimal4.parse("1"),
                    unitPrice = iznos,
                    totalAmount = iznos,
                    labels = listOf(stopa.label),
                ),
            ),
            cashier = "Hospes test",
        )
        val requestId = noviRequestId()

        println("Izdajem:     Obuka – Prodaja, „Test kafa“ 1 × 300 RSD, gotovina")
        red("Poreska oznaka", "${stopa.label} (${stopa.rate}%, iz statusa L-PFR-a)")
        red("Jezik računa", jezik)
        red("RequestId", requestId)
        println()

        when (val rezultat = veza.izdajRacun(zahtev, requestId, jezik)) {
            is RezultatRacuna.Izdat -> {
                if (rezultat.pronadjenNaknadno) {
                    println("Veza je prekinuta, ali je račun pronađen po RequestId. Nije izdat dvaput.")
                }
                println("RAČUN JE IZDAT.")
                ispisiIRacunSacuvaj(rezultat.racun)
            }
            is RezultatRacuna.Odbijen -> {
                val opis = rezultat.greska?.let { g ->
                    buildString {
                        append(g.message ?: "bez poruke")
                        g.modelState.forEach { polje ->
                            polje.errors.forEach { kod ->
                                append("\n  ${polje.property.ifEmpty { "(ceo zahtev)" }}: $kod ${SifreStanja.za(kod).opis}")
                            }
                        }
                    }
                } ?: rezultat.telo
                prekini("L-PFR je odbio račun (HTTP ${rezultat.httpStatus}). Račun NIJE izdat.\n$opis")
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
            ispisiIRacunSacuvaj(racun)
        }
    }
}

private fun ispisiIRacunSacuvaj(racun: FiskalizovanRacun) {
    red("PFR broj računa", racun.invoiceNumber)
    red("Brojač računa", racun.invoiceCounter)
    red("PFR vreme", racun.sdcDateTime)
    red("Ukupno", racun.totalAmount?.let { "$it RSD" })
    racun.taxItems.forEach { porez ->
        red("Porez ${porez.label}", "${porez.categoryName ?: ""} ${porez.rate ?: "?"}% = ${porez.amount} RSD".trim())
    }
    red("Link za proveru", racun.verificationUrl)
    red("Poruka PFR-a", racun.messages)

    folderRacuna.mkdirs()
    val ime = racun.invoiceNumber.replace(Regex("[^A-Za-z0-9-]"), "_")
    File(folderRacuna, "$ime.json").writeText(EsirHttp.json.encodeToString(FiskalizovanRacun.serializer(), racun))
    racun.journal?.let { File(folderRacuna, "$ime.txt").writeText(it) }
    racun.verificationQRCode?.let { File(folderRacuna, "$ime.gif").writeBytes(Base64.getDecoder().decode(it)) }

    println()
    println("Tekst računa (journal), onako kako ga je vratio L-PFR:")
    println(racun.journal ?: "(L-PFR nije vratio tekst računa)")
    println()
    println("Sačuvano u folderu ${folderRacuna.path}/: $ime.txt (tekst), $ime.gif (QR kod), $ime.json (ceo odgovor)")
}

private fun vrednostOpcije(argumenti: List<String>, ime: String): String? {
    val i = argumenti.indexOf(ime)
    return if (i >= 0) argumenti.getOrNull(i + 1) ?: prekini("Opcija $ime traži vrednost") else null
}
