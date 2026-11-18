package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.FilterZurnala
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.naziv
import rs.omniit.hospes.esir.ukupno
import rs.omniit.hospes.esir.zbiroviZurnala
import java.time.LocalDate

/**
 * `zurnal [--od gggg-mm-dd] [--do gggg-mm-dd] [--sve] [--vrsta promet|kopija|predracun|obuka|avans]
 *         [--tip prodaja|refundacija] [--kupac 10:123] [--placanje kartica] [--trazi tekst] [--prikazi <broj>]`
 *
 * Elektronski žurnal (ESIR upitnik, deo 10, P16). Podrazumevano prikazuje poslednjih 30 dana.
 */
internal fun komandaZurnal(argumenti: List<String>) {
    vrednostOpcije(argumenti, "--prikazi")?.let { broj ->
        prikaziRacun(broj)
        return
    }

    val sve = "--sve" in argumenti
    val od = vrednostOpcije(argumenti, "--od") ?: if (sve) null else LocalDate.now().minusDays(30).toString()
    val filter = try {
        FilterZurnala(
            od = od,
            doDana = vrednostOpcije(argumenti, "--do"),
            vrste = vrednostOpcije(argumenti, "--vrsta")?.let { setOf(vrstaIzNaziva(it)) },
            tip = vrednostOpcije(argumenti, "--tip")?.let(::tipIzNaziva),
            kupac = vrednostOpcije(argumenti, "--kupac"),
            nacinPlacanja = vrednostOpcije(argumenti, "--placanje")?.let(::nacinIzNaziva),
            tekst = vrednostOpcije(argumenti, "--trazi"),
        )
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }

    val racuni = zurnal.pretrazi(filter)
    val period = if (sve && filter.od == null) "svi računi" else "od ${filter.od ?: "početka"} do ${filter.doDana ?: "danas"}"
    println("Elektronski žurnal (${zurnal.folder.path}/), $period: ${racuni.size} računa")
    if (racuni.isEmpty()) return
    println()
    println(
        "Vreme".padEnd(17) + "PFR broj računa".padEnd(23) + "Brojač".padEnd(9) + "Vrsta".padEnd(22) +
            "Ukupno".padStart(12) + "  Plaćanje / poziva se na",
    )
    racuni.forEach { r ->
        val vreme = r.racun.sdcDateTime?.take(16)?.replace('T', ' ') ?: "-"
        val placanje = r.zahtev.payment.joinToString(", ") { it.paymentType.naziv.lowercase() }
        val poziv = r.zahtev.referentDocumentNumber?.let { " → $it" } ?: ""
        println(
            vreme.padEnd(17) + r.racun.invoiceNumber.padEnd(23) + (r.racun.invoiceCounter ?: "-").padEnd(9) +
                r.oznaka.padEnd(22) + r.ukupno.zaStampu().padStart(12) + "  $placanje$poziv",
        )
    }
    println()
    println("Zbir po vrsti:")
    zbiroviZurnala(racuni).forEach { z ->
        println("  ${z.oznaka.padEnd(24)} ${z.broj.toString().padStart(4)} računa ${z.iznos.zaStampu().padStart(14)} RSD")
    }
    println()
    println("Ceo račun: zurnal --prikazi <PFR broj računa>")
}

private fun prikaziRacun(broj: String) {
    val izdat = zurnal.nadji(broj) ?: prekini("Nema računa $broj u elektronskom žurnalu.")
    red("PFR broj računa", izdat.racun.invoiceNumber)
    red("Vrsta", izdat.oznaka)
    red("PFR vreme", izdat.racun.sdcDateTime)
    red("RequestId", izdat.requestId)
    red("Kupac", izdat.zahtev.buyerId)
    red("Poziva se na", izdat.zahtev.referentDocumentNumber)
    red("Link za proveru", izdat.racun.verificationUrl)
    println()
    println(zurnal.tekstRacuna(broj) ?: izdat.racun.journal ?: "(nema sačuvanog teksta računa)")
    println()
    println("Ovo je prikaz iz žurnala. Kupcu se ponovo daje samo Kopija: komanda  kopija $broj")
}

private fun vrstaIzNaziva(naziv: String): VrstaRacuna = when (naziv) {
    "promet" -> VrstaRacuna.PROMET
    "kopija" -> VrstaRacuna.KOPIJA
    "predracun" -> VrstaRacuna.PREDRACUN
    "obuka" -> VrstaRacuna.OBUKA
    "avans" -> VrstaRacuna.AVANS
    else -> prekini("Nepoznata vrsta „$naziv“. Dozvoljeno: promet, kopija, predracun, obuka, avans")
}

private fun tipIzNaziva(naziv: String): TipTransakcije = when (naziv) {
    "prodaja" -> TipTransakcije.PRODAJA
    "refundacija" -> TipTransakcije.REFUNDACIJA
    else -> prekini("Nepoznat tip „$naziv“. Dozvoljeno: prodaja, refundacija")
}
