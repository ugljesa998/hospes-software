package rs.omniit.hospes.app

import androidx.compose.ui.graphics.ImageBitmap
import rs.omniit.hospes.esir.ElektronskiZurnal
import rs.omniit.hospes.esir.Katalog
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.VrstaPfra

/**
 * Ono što ekran dobija od platforme (desktop sada, Android kasnije):
 * otvaranje PFR veze, elektronski žurnal, katalog artikala, izbor fajla, PIN iz podešavanja i današnji datum.
 */
interface Okruzenje {
    /** PIN za L-PFR iz podešavanja (razvoj). U pravoj kasi PIN unosi kasir. */
    val pin: String?

    val zurnal: ElektronskiZurnal

    /** Katalog artikala ove kase. */
    val katalog: Katalog

    /** Korisnik bira CSV fajl za uvoz kataloga; vraća sadržaj ili `null` ako je odustao. */
    fun procitajCsv(): String?

    /** Korisnik bira gde da sačuva CSV; upisuje [tekst] i vraća putanju, ili `null` ako je odustao. */
    fun sacuvajCsv(predlogImena: String, tekst: String): String?

    /** Otvara vezu sa PFR-om; baca [IllegalArgumentException] sa porukom ako podešavanja nisu ispravna. */
    fun otvoriPfr(vrsta: VrstaPfra): OtvorenPfr

    /** Sačuvan tekst računa (PFR + ESIR dodatak), ako postoji. */
    fun tekstRacuna(brojRacuna: String): String?

    /** Datum pre 30 dana, `gggg-mm-dd`, za podrazumevani prikaz žurnala. */
    fun pre30Dana(): String
}

/** QR kod iz PFR-a je GIF slika; svaka platforma je dekodira na svoj način. */
expect fun ByteArray.uSliku(): ImageBitmap?
