# hospes-software

Hospes POS je sopstveni POS i fiskalni sistem kompanije OmniIT za tržište Srbije: ESIR, LPFR i poslovni deo.

## Struktura

| Folder | Šta je | Tehnologija |
|---|---|---|
| `esir/` | ESIR modul: komunikacija sa L-PFR i V-PFR (TaxCore v3) | Kotlin Multiplatform |
| `esir-cli/` | Komandna linija za ručno testiranje ESIR modula | Kotlin (JVM) |
| `config/` | Primer podešavanja (`example.json`). Pravo podešavanje ide u `config/local.json`, koje nije u Git-u | — |

Sledeći delovi (LPFR, aplikacija, hub, server, admin) dodaju se kada dođemo do njih.

## Zahtevi

- JDK 17 ili noviji
- Internet, za razvojni LPFR Poreske

Gradle ne treba instalirati: `./gradlew` sam preuzima odgovarajuću verziju.

## Komande

```bash
./gradlew build                 # prevođenje i svi testovi
./gradlew :esir:allTests        # samo testovi ESIR modula
./gradlew :esir-cli:run         # pokretanje komandne linije (spisak komandi)
./gradlew -q :esir-cli:run --args="status"   # stanje L-PFR-a, šifre stanja, poreske stope
./gradlew -q :esir-cli:run --args="pin"      # PIN iz config/local.json, šalje se najviše jednom
./gradlew -q :esir-cli:run --args="racun"    # Obuka Prodaja: „Test kafa“ 1 × 300 RSD, gotovina
./gradlew -q :esir-cli:run --args="racun --vrsta promet"                          # Promet Prodaja
./gradlew -q :esir-cli:run --args="racun --vrsta predracun --placanje prenos"     # Predračun Prodaja
./gradlew -q :esir-cli:run --args="iz-predracuna <PFR broj> --placanje kartica"    # Promet Prodaja iz predračuna
./gradlew -q :esir-cli:run --args="kopija <PFR broj računa>"                      # kopija računa vrste Promet
./gradlew -q :esir-cli:run --args="refundacija <PFR broj računa> --kupac 20:123456789"   # refundacija uz ID kupca
./gradlew -q :esir-cli:run --args="lista"    # računi sačuvani u racuni/
./gradlew -q :esir-cli:run --args="provera <RequestId>"   # da li je poslednji račun sa tim RequestId izdat
```

Izdati računi se čuvaju u folderu `racuni/` (tekst računa, QR kod kao GIF, ceo odgovor PFR-a). Folder nije u Git-u.

Kada komanda završi sa greškom, Gradle na kraju ispiše `BUILD FAILED`. To je očekivano: program je vratio kod greške.

## Pravilo za tajne

Token, PIN, lozinke, PAC i sertifikati (`.pfx`, `.p12`) **nikad ne idu u Git**.

1. Kopiraj `config/example.json` u `config/local.json`.
2. Upiši prave vrednosti u `config/local.json`.

Fajl `config/local.json` je u `.gitignore`.
