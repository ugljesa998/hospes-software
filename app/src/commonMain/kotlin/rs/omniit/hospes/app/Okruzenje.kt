package rs.omniit.hospes.app

import androidx.compose.ui.graphics.ImageBitmap
import rs.omniit.hospes.esir.ElektronskiZurnal
import rs.omniit.hospes.esir.Katalog
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.VrstaPfra

/**
 * Ono što ekran dobija od platforme (desktop sada, Android kasnije):
 * podešavanja kase, otvaranje PFR veze, elektronski žurnal, katalog artikala, izbor fajla, štampa i današnji datum.
 * PIN kartice ovde ne postoji: unosi ga kasir na ekranu.
 */
interface Okruzenje {
    /** Folder sa podacima kase (podešavanja, žurnal, katalog, štampa); prikazuje se u podešavanjima. */
    val folderPodataka: String

    /** Sačuvana podešavanja kase (PFR, kasiri, papir); prazna pri prvom pokretanju. */
    fun ucitajPodesavanja(): Podesavanja

    fun sacuvajPodesavanja(podesavanja: Podesavanja)

    /** Kasir bira fajl sertifikata za V-PFR (.p12/.pfx); fajl se čuva uz podešavanja. Vraća putanju za podešavanja ili `null`. */
    fun izaberiSertifikat(): String?

    /** Proverava sertifikat i lozinku; vraća opis (UID, PIB, adresa V-PFR-a, rok) ili baca [IllegalArgumentException]. */
    fun opisSertifikata(putanja: String, lozinka: String): String

    val zurnal: ElektronskiZurnal

    /** Katalog artikala ove kase. */
    val katalog: Katalog

    /** Korisnik bira CSV fajl za uvoz kataloga; vraća sadržaj ili `null` ako je odustao. */
    fun procitajCsv(): String?

    /** Korisnik bira gde da sačuva CSV; upisuje [tekst] i vraća putanju, ili `null` ako je odustao. */
    fun sacuvajCsv(predlogImena: String, tekst: String): String?

    /** Otvara vezu sa PFR-om po poslednjim sačuvanim podešavanjima; baca [IllegalArgumentException] ako nisu ispravna. */
    fun otvoriPfr(vrsta: VrstaPfra): OtvorenPfr

    /** Sačuvan tekst računa (PFR + ESIR dodatak), ako postoji. */
    fun tekstRacuna(brojRacuna: String): String?

    /** Račun iz žurnala nacrtan za [papir], kao PNG slika (pregled na ekranu), ili `null` ako ga nema. */
    fun pregledOtiska(brojRacuna: String, papir: SirinaPapira): ByteArray?

    /**
     * Štampa račun iz žurnala; vraća kratak opis gde je odštampan. Baca izuzetak ako štampa nije uspela.
     * Poziva se jednom, posle izdavanja: drugi primerak se izdaje preko PFR-a kao Kopija.
     */
    fun stampaj(brojRacuna: String, papir: SirinaPapira): String

    /** Datum pre 30 dana, `gggg-mm-dd`, za podrazumevani prikaz žurnala. */
    fun pre30Dana(): String
}

/** QR kod iz PFR-a je GIF slika; svaka platforma je dekodira na svoj način. */
expect fun ByteArray.uSliku(): ImageBitmap?
