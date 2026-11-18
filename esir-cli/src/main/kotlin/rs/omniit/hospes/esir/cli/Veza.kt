package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.VrstaPfra

/**
 * Otvara vezu sa PFR-om izabranim opcijom `--pfr lpfr|vpfr` (podrazumevano `lpfr`).
 * Podešavanja se čitaju iz `config/local.json`; tajne se ne ispisuju.
 */
internal fun otvoriPfr(argumenti: List<String>): OtvorenPfr {
    val vrsta = when (val v = vrednostOpcije(argumenti, "--pfr") ?: "lpfr") {
        "lpfr" -> VrstaPfra.LPFR
        "vpfr" -> VrstaPfra.VPFR
        else -> prekini("Nepoznat PFR „$v“. Dozvoljeno: --pfr lpfr ili --pfr vpfr")
    }
    return try {
        rs.omniit.hospes.esir.otvoriPfr(ucitajPodesavanja(), vrsta)
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }
}
