# hospes-software

Hospes POS je sopstveni POS i fiskalni sistem kompanije OmniIT za tržište Srbije: ESIR, LPFR i poslovni deo.

## Struktura

| Folder | Šta je | Tehnologija |
|---|---|---|
| `esir/` | ESIR modul: komunikacija sa L-PFR i V-PFR (TaxCore v3) | Kotlin Multiplatform |
| `esir-cli/` | Komandna linija za ručno testiranje ESIR modula | Kotlin (JVM) |
| `app/` | Aplikacija: ESIR test ekran (desktop sada, Android i Windows kasnije) | Compose Multiplatform |
| `config/` | Primer podešavanja (`example.json`). Pravo podešavanje ide u `config/local.json`, koje nije u Git-u | — |

Sledeći delovi (LPFR, aplikacija, hub, server, admin) dodaju se kada dođemo do njih.

## Zahtevi

- JDK 17 ili noviji
- Internet, za razvojni LPFR Poreske

Gradle ne treba instalirati: `./gradlew` sam preuzima odgovarajuću verziju.

## Aplikacija

```bash
./gradlew :app:run              # otvara ESIR test ekran (prozor) na desktopu
```

Ekran: izbor L-PFR/V-PFR, stanje i PIN, stavke iz kataloga (klik, pretraga, bar-kod čitač + Enter) ili ručni unos
(količina, cena, popust, poreska oznaka iz PFR-a), plaćanje i kusur, izdavanje računa, prikaz računa sa QR kodom,
elektronski žurnal za 30 dana i katalog artikala (novi artikal, izmena i cena, pretraga, uvoz i izvoz CSV).
Posle izdavanja račun se štampa jednom, na izabranom papiru (58 ili 80 mm); desno se vidi „Tekst“ ili tačan izgled na papiru.
Kupac na računu („na firmu“): ispod plaćanja „Kupac“, šifra po šifarniku 9.8 (10 = PIB) i broj; važi za jedan račun.

Radnje nad računom iz žurnala (izaberi račun, dugmad ispod naslova):
- „Naplati“ (predračun): Promet Prodaja sa istim stavkama, uz poziv na predračun; predračun se naplaćuje jednom.
- „Refundacija“: ceo račun ili samo neke stavke i količine, uz ID kupca koji prima novac (9.6). Ne može se vratiti
  više nego što je ostalo posle ranijih refundacija. Posle povraćaja gotovine (vrsta Promet) aplikacija odmah izdaje
  i Kopiju sa redom za potpis kupca.
- „Kopija“: kopija računa vrste Promet (potvrđuje se drugim klikom).
Koristi isti `config/local.json`, isti žurnal (`racuni/`) i isti katalog (`podaci/katalog.json`) kao komandna linija.

Ako je aplikacija upaljena, zatvori je pre `./gradlew build`, pa je posle ponovo pokreni.

## Komande

```bash
./gradlew build                 # prevođenje i svi testovi
./gradlew :esir:allTests        # samo testovi ESIR modula
./gradlew :esir-cli:run         # pokretanje komandne linije (spisak komandi)
./gradlew -q :esir-cli:run --args="status"   # stanje L-PFR-a, šifre stanja, poreske stope
./gradlew -q :esir-cli:run --args="pin"      # PIN iz config/local.json, šalje se najviše jednom
./gradlew -q :esir-cli:run --args="racun"    # Obuka Prodaja: „Test kafa“ 1 × 300 RSD, gotovina
./gradlew -q :esir-cli:run --args="racun --vrsta promet"                          # Promet Prodaja
./gradlew -q :esir-cli:run --args="racun --vrsta promet --stavka 'Kafa;3;250;A;10' --stavka 'Sir /kg;0.250;1199.99' --placanje kartica:500 --placanje gotovina --primljeno 500"
#   više stavki (naziv;količina;cena;oznaka;popust%), podeljeno plaćanje, kusur
./gradlew -q :esir-cli:run --args="racun --vrsta predracun --placanje prenos"     # Predračun Prodaja
./gradlew -q :esir-cli:run --args="iz-predracuna <PFR broj> --placanje kartica"    # Promet Prodaja iz predračuna
./gradlew -q :esir-cli:run --args="kopija <PFR broj računa>"                      # kopija računa vrste Promet
./gradlew -q :esir-cli:run --args="refundacija <PFR broj računa> --kupac 20:123456789"   # refundacija uz ID kupca
./gradlew -q :esir-cli:run --args="zurnal"   # elektronski žurnal, poslednjih 30 dana, sa zbirom po vrsti
./gradlew -q :esir-cli:run --args="zurnal --tip refundacija --trazi kafa"   # filteri: --od --do --vrsta --tip --kupac --placanje --trazi --sve
./gradlew -q :esir-cli:run --args="zurnal --prikazi <PFR broj>"             # ceo račun iz žurnala
```

Avansni tok (PURS uputstvo, poglavlje 9.1):

```bash
./gradlew -q :esir-cli:run --args="avans --iznos 1000 --kupac 10:123456789 --placanje prenos"   # prvi avans
./gradlew -q :esir-cli:run --args="avans --iznos 500 --prethodni <broj prvog> --placanje gotovina"   # sledeći avans
./gradlew -q :esir-cli:run --args="zatvori-avans <broj poslednjeg avansa>"      # Avans Refundacija, ne daje se kupcu
./gradlew -q :esir-cli:run --args="konacni-racun <broj AR> --iznos 2500 --naziv 'Najam vozila' --placanje kartica"
./gradlew -q :esir-cli:run --args="provera <RequestId>"   # da li je poslednji račun sa tim RequestId izdat
```

Katalog artikala (ESIR upitnik, deo 11) se čuva u `podaci/katalog.json` (nije u Git-u):

```bash
./gradlew -q :esir-cli:run --args="katalog uvoz config/katalog-primer.csv"     # primer kafića (oznaka A = sandbox)
./gradlew -q :esir-cli:run --args="katalog"                                    # spisak; --trazi kafa (naziv, šifra, GTIN, kategorija)
./gradlew -q :esir-cli:run --args="katalog dodaj --sifra 105 --naziv 'Topla čokolada' --cena 280 --oznaka A --kategorija 'Topli napici'"
./gradlew -q :esir-cli:run --args="katalog cena 101 190"                       # promena cene
./gradlew -q :esir-cli:run --args="katalog izvoz izvoz/katalog.csv"            # CSV za Excel (UTF-8 sa BOM-om)
./gradlew -q :esir-cli:run --args="racun --artikal '101;2' --artikal 5449000000996 --artikal '501;0.35' --primljeno 2000"
#   artikal po šifri ili bar-kodu (GTIN): šifra[;količina[;cena[;popust%]]]
```

CSV: kolone `sifra;naziv;jedinica_mere;cena;oznaka;gtin;kategorija`, decimalni zarez (`1199,99`).
Obavezne su `sifra`, `naziv`, `cena` i `oznaka`. Excel fajl sačuvan kao „CSV UTF-8“ ili običan „CSV“ (Windows-1250) se čita ispravno.
Poreska oznaka mora postojati u stanju PFR-a; u sandbox-u su oznake izmišljene (A = 10%), u produkciji npr. Ђ = 20%.

Štampa računa (dok nema pravog štampača, virtuelni štampač u folderu `stampa/`, nije u Git-u):

```bash
./gradlew -q :esir-cli:run --args="stampa <PFR broj>"              # PNG (izgled na papiru) i .escpos (bajtovi za štampač), 58 i 80 mm
./gradlew -q :esir-cli:run --args="stampa <PFR broj> --papir 58"
```

- Račun ide štampaču **kao slika** (ESC/POS `GS v 0`): ćirilica radi na svakom štampaču, otisak = pregled.
- Termalni štampač: 8 tačaka po mm; 58 mm papir → 384 tačke (48 mm), 80 mm → 576 tačaka (72 mm), 40 znakova u redu.
- „ОВО НИЈЕ ФИСКАЛНИ РАЧУН“ duplo većim slovima; QR kod 40–50 mm, pre završne linije
  (na 80 mm 48,5 mm sa jednakim modulima od 4 tačke; na 58 mm oko 43 mm). Testovi proveravaju da se QR čita.
- `stampa` je alat za razvoj: drugi primerak fiskalnog računa se ne štampa ponovo, već se izdaje Kopija (`kopija <broj>`).

Izdati računi se čuvaju u folderu `racuni/` (tekst računa, QR kod kao GIF, ceo odgovor PFR-a). Folder nije u Git-u.

Specijalni slučajevi (PURS uputstvo, poglavlje 9):

```bash
./gradlew -q :esir-cli:run --args="ponisti <PFR broj>"                                  # 9.3 poništavanje greške (sopstveni PIB)
./gradlew -q :esir-cli:run --args="korporativna --pib 123456789 --kartica 6666666"      # 9.4 korporacijska kartica, vaučer
./gradlew -q :esir-cli:run --args="korporativna-refundacija --pib 123456789 --mesec 2026-11"   # 9.4 zbirna mesečna refundacija
./gradlew -q :esir-cli:run --args="vaucer --iznos 1000"                                 # 9.5 jednonamenski vaučer
./gradlew -q :esir-cli:run --args="bez-naknade --stavka 'Poklon;1;300'"                 # 9.7 promet bez naknade
./gradlew -q :esir-cli:run --args="avans --iznos 400 --kupac 10:123456789 --datum-uplate 2026-11-17"   # 9.2 starija uplata
```

### L-PFR ili V-PFR

Sve komande koje rade sa PFR-om primaju `--pfr lpfr` (podrazumevano) ili `--pfr vpfr`:

```bash
./gradlew -q :esir-cli:run --args="status --pfr vpfr"
./gradlew -q :esir-cli:run --args="racun --pfr vpfr"
```

V-PFR traži u `config/local.json` deo `vpfr`: putanju do sertifikata (`.p12`/`.pfx`, iz arhive sa portala uzeti
`*.chain.p12`), lozinku i PAC. Adresa V-PFR-a se čita iz samog sertifikata. V-PFR nema PIN.

Kada komanda završi sa greškom, Gradle na kraju ispiše `BUILD FAILED`. To je očekivano: program je vratio kod greške.

## Pravilo za tajne

Token, PIN, lozinke, PAC i sertifikati (`.pfx`, `.p12`) **nikad ne idu u Git**.

1. Kopiraj `config/example.json` u `config/local.json`.
2. Upiši prave vrednosti u `config/local.json`.

Fajl `config/local.json` je u `.gitignore`.
