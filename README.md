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
Plaćanje: svih 7 načina (gotovina, platna kartica, prenos na račun, instant, ček, vaučer, drugo bezgotovinsko) i
podeljeno plaćanje („+ Podeli plaćanje“: delovi sa iznosom, glavni način dobija ostatak; kusur od gotovine).
U Podešavanjima „Načini plaćanja“: kasa bez kartice, čeka i instanta (Pravilnik, član 6, stav 2; unose se kao gotovina).

Kupac na računu („na firmu“): ispod plaćanja „Kupac“, šifra po šifarniku 9.8 (10 = PIB, 9 cifara) i broj, uz opciono
polje kupca (šifre 20–33); važi za jedan račun.

Svaki račun (aplikacija i komandna linija) nosi „ЕСИР број“ `1795/0.0.1` = ИБ број prijave/verzija softvera
(PURS tehničko uputstvo v1.17, upitnik „Текстуални приказ“, pitanje 5); PFR ga štampa u zaglavlju. Nova verzija posle
odobrenja ide na novu prijavu (uputstvo 7.9): menjaju se `Esir.IB_BROJ` i `Esir.VERSION`.

Podešavanja, „O programu“: proizvođač, proizvod (Hospes POS), verzija, ESIR broj, serijski broj instalacije (dodeljuje se
pri prvom pokretanju). „Tekst ispod računa“: štampa se ispod „КРАЈ ФИСКАЛНОГ РАЧУНА“ na svakom računu (polje za reklamu).

Radnje nad računom iz žurnala (izaberi račun, dugmad ispod naslova):
- „Naplati“ (predračun): Promet Prodaja sa istim stavkama, uz poziv na predračun; predračun se naplaćuje jednom.
- „Refundacija“: ceo račun ili samo neke stavke i količine, uz ID kupca koji prima novac (9.6). Ne može se vratiti
  više nego što je ostalo posle ranijih refundacija. Posle povraćaja gotovine (vrsta Promet) aplikacija odmah izdaje
  i Kopiju sa redom za potpis kupca.
- „Kopija“: kopija računa vrste Promet (potvrđuje se drugim klikom).
- „Poništi (greška)“ (9.3): refundacija celog Promet ili Avans računa na sopstveni PIB, bez povraćaja novca; zatim novi račun.

Avansi (PURS 9.1, 9.2), vrsta računa „Avans“:
- Prvi avans: iznos, poreska oznaka, šifra avansa (sama po stopi: 20% → 10, 10% → 11, 0% → 12; 13 se bira),
  kupac i plaćanje; „Datum uplate“ samo kada je novac stigao ranije (9.2, „ESIR vreme“).
- U žurnalu, na avansu: „Avansni posao“ (svi avansi i zbir), „Sledeći avans“ (poziva se na poslednji) i „Konačni račun“.
- „Konačni račun“ prebacuje levu stranu u režim konačnog računa: stavke po punoj ceni, plaćeno avansom, ostatak, kupac
  (obavezan; sa avansa ili se unosi). Jedno dugme „Zatvori avanse i izdaj konačni račun“ izdaje Avans Refundaciju
  (ostaje u radnji) i Promet Prodaju na kojoj PFR-u ide samo ostatak; ispod računa: plaćeno avansom, PDV na avans,
  preostalo, poslednji avans. „Otkaži“ vraća običan račun.
- Delimična realizacija avansa (avans veći od računa) i refundacija konačnog računa posle avansa još nisu podržane.

Specijalni slučajevi (PURS 9.4, 9.5, 9.7), na računu koji se pravi:
- „Poseban slučaj: Bez naknade“ (9.7): stavke sa „00:“, plaćanje drugo bezgotovinsko, ispod računa „*Промет без накнаде“.
- „Poseban slučaj: Korporativna kartica“ (9.4): PIB firme i broj kartice, plaćanje vaučer. Jednom mesečno u Žurnalu:
  „Korporativne kartice: mesečna refundacija“ (zbirna Promet Refundacija, jednom po firmi i mesecu); računi koje ona
  obuhvata više se ne refundiraju ni ne poništavaju pojedinačno.
- Kartica „Vaučer“ (9.5): stavka „20: Једнонаменски ваучер (oznaka)“, najviše jedan po poreskoj stopi; kada kupac plati
  vaučerom, fiskalni račun se ne izdaje.
Koristi isti `config/local.json`, isti žurnal (`racuni/`) i isti katalog (`podaci/katalog.json`) kao komandna linija.

Kasir, PIN i podešavanja (kartica „Podešavanja“):
- Kasiri: spisak imena; kasir se bira gore desno, a njegovo ime se štampa na računu („Касир“). Bez kasira račun se ne izdaje.
- PIN kartice unosi kasir kada L-PFR traži PIN; šalje se jednom (posle 5 pogrešnih kartica se zaključava).
  Aplikacija PIN ne čita iz fajla; `lpfr.pin` u `config/local.json` koristi samo komandna linija (`pin`).
- L-PFR: adresa; V-PFR: sertifikat (kopira se u `config/`), lozinka i PAC, uz proveru sertifikata. Tajne su skrivene na ekranu.
- Papir u štampaču (58/80 mm). Sve se čuva u `config/local.json` (nije u Git-u).

Gde su podaci kase: iz razvoja (`./gradlew :app:run`) u folderu projekta, kao komandna linija; instalirana aplikacija u
sistemskom folderu (Windows `%APPDATA%\Hospes ESIR`, macOS `~/Library/Application Support/Hospes ESIR`); može i `HOSPES_KOREN`.
Pri prvom pokretanju bez podešavanja aplikacija otvara „Podešavanja“.

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
