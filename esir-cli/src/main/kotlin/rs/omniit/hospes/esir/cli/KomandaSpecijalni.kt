package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.FilterZurnala
import rs.omniit.hospes.esir.JednonamenskiVaucer
import rs.omniit.hospes.esir.KorporativnaKartica
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Obracun
import rs.omniit.hospes.esir.Placanje
import rs.omniit.hospes.esir.PrometBezNaknade
import rs.omniit.hospes.esir.StatusOdgovor
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZahtevZaRacun
import rs.omniit.hospes.esir.naziv
import rs.omniit.hospes.esir.zahtevZaPonistavanje
import java.time.YearMonth

/** `ponisti <broj>`: poništavanje Promet ili Avans Prodaje sa greškom (PURS 9.3), sopstvenim PIB-om. */
internal suspend fun komandaPonisti(argumenti: List<String>) {
    val broj = argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }
        ?: prekini("Upotreba: ponisti <PFR broj pogrešnog računa> [--pib 123456789]")
    val original = ucitajIzdat(broj)
    val zahtev = try {
        original.zahtevZaPonistavanje(vrednostOpcije(argumenti, "--pib"))
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }

    saPfrom(argumenti) { veza, status ->
        izdajIPrikazi(
            veza, status, zahtev,
            "Poništavanje računa $broj: ${zahtev.invoiceType.naziv} Refundacija sa sopstvenim PIB-om (${zahtev.buyerId})",
        )
        println()
        println("Sledeći korak po PURS 9.3: izdaj novi, ispravan račun.")
    }
}

/** `vaucer --iznos 6000 [--oznaka A] [--placanje gotovina]`: prodaja jednonamenskog vaučera (PURS 9.5). */
internal suspend fun komandaVaucer(argumenti: List<String>) {
    val iznos = broj(vrednostOpcije(argumenti, "--iznos") ?: prekini("Navedi iznos vaučera, npr. --iznos 6000"), "--iznos")
    val nacin = nacinPlacanja(argumenti) ?: NacinPlacanja.GOTOVINA

    saPfrom(argumenti) { veza, status ->
        val oznaka = proveriOznaku(vrednostOpcije(argumenti, "--oznaka") ?: "A", status)
        val stavka = try {
            JednonamenskiVaucer.stavka(oznaka, iznos)
        } catch (e: IllegalArgumentException) {
            prekini(e.message ?: e.toString())
        }
        val zahtev = ZahtevZaRacun(
            invoiceType = VrstaRacuna.PROMET,
            transactionType = TipTransakcije.PRODAJA,
            payment = listOf(Placanje(iznos, nacin)),
            items = listOf(stavka),
            cashier = "Hospes test",
        )
        izdajIPrikazi(veza, status, zahtev, "Promet Prodaja: ${stavka.name}, ${iznos.zaStampu()} RSD")
        println()
        println("Napomena (PURS 9.5): kada kupac iskoristi vaučer, fiskalni račun se NE izdaje.")
    }
}

/**
 * `korporativna --pib 123456789 --kartica 6666666 [--stavka 'Naziv;količina;cena[;oznaka]']...`:
 * prodaja korporacijskom karticom (PURS 9.4); plaćanje je uvek vaučer.
 */
internal suspend fun komandaKorporativna(argumenti: List<String>) {
    val pib = vrednostOpcije(argumenti, "--pib") ?: prekini("Navedi PIB kupca, npr. --pib 123456789")
    val kartica = vrednostOpcije(argumenti, "--kartica") ?: prekini("Navedi broj korporacijske kartice, npr. --kartica 6666666")
    val opisi = vrednostiOpcije(argumenti, "--stavka").ifEmpty { listOf("Test gorivo /л;10;150") }

    saPfrom(argumenti) { veza, status ->
        val stope = status.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }
        val stavke = opisi.map { stavkaIzOpisa(it, vrednostOpcije(argumenti, "--oznaka") ?: "A", stope) }
        val zahtev = try {
            KorporativnaKartica.zahtevZaProdaju(stavke, pib, kartica, kasir = "Hospes test")
        } catch (e: IllegalArgumentException) {
            prekini(e.message ?: e.toString())
        }
        izdajIPrikazi(
            veza, status, zahtev,
            "Promet Prodaja korporacijskom karticom $kartica, kupac 10:$pib, ${Obracun.ukupno(stavke).zaStampu()} RSD, vaučer",
        )
    }
}

/**
 * `korporativna-refundacija --pib 123456789 [--mesec 2026-11]`: zbirna mesečna Promet Refundacija
 * za sve prodaje korporacijskom karticom tom kupcu (PURS 9.4). Računi se uzimaju iz elektronskog žurnala.
 */
internal suspend fun komandaKorporativnaRefundacija(argumenti: List<String>) {
    val pib = vrednostOpcije(argumenti, "--pib") ?: prekini("Navedi PIB kupca, npr. --pib 123456789")
    val mesec = runCatching { YearMonth.parse(vrednostOpcije(argumenti, "--mesec") ?: YearMonth.now().toString()) }
        .getOrElse { prekini("Mesec mora biti u formatu gggg-mm, npr. 2026-11") }
    val od = mesec.atDay(1).toString()
    val doDana = mesec.atEndOfMonth().toString()

    val racuni = zurnal.pretrazi(FilterZurnala(od = od, doDana = doDana, kupac = "10:$pib"))
    val zahtev = try {
        KorporativnaKartica.zahtevZaMesecnuRefundaciju(racuni, pib, od, doDana, kasir = "Hospes test")
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }
    val obuhvaceno = racuni.filter { KorporativnaKartica.jeProdajaKarticom(it, pib) }
    println("Obuhvaćeno ${obuhvaceno.size} računa: ${obuhvaceno.joinToString { it.racun.invoiceNumber }}")

    saPfrom(argumenti) { veza, status ->
        izdajIPrikazi(
            veza, status, zahtev,
            "Zbirna Promet Refundacija za kupca 10:$pib, period $od – $doDana (${zahtev.buyerCostCenterId}), poziva se na ${zahtev.referentDocumentNumber}",
        )
    }
}

/** `bez-naknade [--stavka 'Naziv;količina;cena[;oznaka]']...`: promet bez naknade (PURS 9.7). */
internal suspend fun komandaBezNaknade(argumenti: List<String>) {
    val opisi = vrednostiOpcije(argumenti, "--stavka").ifEmpty { listOf("Test poklon;1;300") }

    saPfrom(argumenti) { veza, status ->
        val stope = status.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }
        val stavke = opisi.map { stavkaIzOpisa(it, vrednostOpcije(argumenti, "--oznaka") ?: "A", stope) }
        val zahtev = PrometBezNaknade.zahtev(stavke, kasir = "Hospes test")
        izdajIPrikazi(
            veza, status, zahtev,
            "Promet Prodaja bez naknade: ${stavke.size} stavk${if (stavke.size == 1) "a" else "e"}, ${Obracun.ukupno(stavke).zaStampu()} RSD",
            dodatak = listOf("Рекламно поље: ${PrometBezNaknade.NAPOMENA}"),
        )
    }
}

private fun proveriOznaku(oznaka: String, status: StatusOdgovor): String {
    val stope = status.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }
    if (stope.none { it.label == oznaka }) {
        prekini("Poreska oznaka $oznaka ne postoji. Dostupne: ${stope.joinToString(", ") { "${it.label} (${it.rate}%)" }}")
    }
    return oznaka
}
