package rs.omniit.hospes.esir

/** Ishod unosa PIN-a preko [LpfrVeza.unesiPin]. */
sealed interface RezultatPina {

    /** L-PFR je prihvatio PIN (`0100`) i pamti ga dok se kartica ne izvadi ili L-PFR ne ugasi. */
    data object Prihvacen : RezultatPina

    /** L-PFR već ima ispravan PIN, pa PIN nije ni poslat. */
    data object NijePotreban : RezultatPina

    /** PIN nije poslat jer L-PFR ne može da ga proveri (nedostupan, nema kartice, kartica zaključana…). */
    data class NijePoslat(val razlog: String, val sifra: SifraStanja? = null) : RezultatPina

    /** PIN je poslat jednom i odbijen. Ne šalje se ponovo automatski, da se kartica ne bi zaključala. */
    data class Odbijen(val sifra: SifraStanja) : RezultatPina
}
