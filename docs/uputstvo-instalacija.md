# Hospes POS: uputstvo za instalaciju (Windows)

Proizvođač: OmniIT · Proizvod: Hospes POS · Verzija: 0.0.1 · ESIR broj: 1795/0.0.1

Hospes POS je softverski ESIR (elektronski sistem za izdavanje računa) za računar sa Windows-om.
Radi sa L-PFR-om (lokalni procesor fiskalnih računa) ili sa V-PFR-om Poreske uprave.

## 1. Šta je potrebno

- Računar sa Windows 10 ili Windows 11 (64-bit).
- Veza sa PFR-om:
  - **L-PFR**: L-PFR u istoj mreži (Ethernet ili Wi-Fi) i njegova adresa, npr. `http://192.168.1.20:7555/api`,
    i PIN bezbednosnog elementa (kartice);
  - **V-PFR**: internet veza (Ethernet, Wi-Fi ili mobilna mreža) i sertifikat sa portala Poreske uprave
    (fajl `.p12` ili `.pfx`), lozinka sertifikata i PAC.
- Termalni štampač za papir širine 57 mm ili 80 mm.
- Instalacioni fajl `Hospes POS-0.0.1.msi`.

Java i drugi programi se ne instaliraju posebno: sve što je potrebno je u instalacionom fajlu.

## 2. Instalacija

1. Dvaput kliknite na `Hospes POS-0.0.1.msi`.
2. Ako Windows prikaže poruku „Windows je zaštitio vaš računar“, kliknite „Više informacija“, pa „Ipak pokreni“.
3. Izaberite folder za instalaciju (ili ostavite predloženi) i kliknite „Install“.
4. Posle instalacije „Hospes POS“ se nalazi u Start meniju (grupa „Hospes“) i na radnoj površini.

Instalacija ne traži administratorska prava: program se instalira za korisnika koji je prijavljen na Windows.

## 3. Prvo pokretanje i podešavanja

Pri prvom pokretanju otvara se kartica **Podešavanja**:

1. **Kasiri**: upišite ime kasira i kliknite „Dodaj“. Ime izabranog kasira se štampa na svakom računu.
2. **PFR**: popunite jedan od ova dva dela:
   - **L-PFR**: upišite adresu L-PFR-a i kliknite „Sačuvaj L-PFR“;
   - **V-PFR**: kliknite „Izaberi sertifikat“, upišite lozinku i PAC, kliknite „Proveri sertifikat“
     (prikazuju se UID, PIB, adresa V-PFR-a i rok važenja), pa „Sačuvaj V-PFR“.
3. **Načini plaćanja**: ostavite „Svih 7 načina“, osim ako obveznik po Pravilniku (član 6, stav 2) ne evidentira
   platnu karticu, ček i instant plaćanje; tada izaberite „Bez kartice, čeka i instanta“.
4. **Tekst ispod računa**: tekst koji se štampa ispod „КРАЈ ФИСКАЛНОГ РАЧУНА“ (npr. „Hvala na poseti!“).
5. **Štampač**: izaberite širinu papira (58 mm ili 80 mm).

Zatim gore levo izaberite **L-PFR** ili **V-PFR** i kliknite „Osveži“. Treba da piše „Dostupan“, kao i poreske
stope koje je vratio PFR. Ako L-PFR traži PIN, kasir ga unosi u polje „PIN kartice“ (PIN se šalje jednom;
posle 5 pogrešnih unosa kartica se zaključava).

## 4. Gde su podaci

Svi podaci kase su u folderu `%APPDATA%\Hospes ESIR` (u Explorer-u upišite `%APPDATA%\Hospes ESIR`):

| Folder / fajl | Sadržaj |
|---|---|
| `config\local.json` | podešavanja kase (PFR, kasiri, papir, tekst ispod računa, serijski broj) |
| `config\*.p12`, `config\*.pfx` | sertifikat za V-PFR (kopira se pri izboru) |
| `racuni\` | elektronski žurnal: svaki izdati račun (zahtev, odgovor PFR-a, tekst računa, QR kod) |
| `podaci\katalog.json` | katalog artikala |
| `stampa\` | otisci računa (dok se ne poveže štampač) |
| `greske.log` | zapis neočekivanih grešaka, za podršku |

Folder sadrži lozinku sertifikata, PAC i elektronski žurnal: čuvajte ga i redovno pravite rezervnu kopiju
(kopirajte ceo folder).

## 5. Nova verzija i uklanjanje

- **Nova verzija**: pokrenite novi `.msi`. On zamenjuje staru verziju; podaci u `%APPDATA%\Hospes ESIR` ostaju.
- **Uklanjanje**: Windows Podešavanja → Aplikacije → Hospes POS → Deinstaliraj. Podaci u
  `%APPDATA%\Hospes ESIR` ostaju. Elektronski žurnal (`racuni\`) ne brišite.

## 6. Ako nešto ne radi

- „PFR se ne javlja“: proverite mrežu, adresu L-PFR-a (ili internet za V-PFR) i kliknite „Osveži“.
- „traži PIN“: kasir unosi PIN kartice.
- Program se neočekivano zatvori: pošaljite podršci fajl `%APPDATA%\Hospes ESIR\greske.log`.
