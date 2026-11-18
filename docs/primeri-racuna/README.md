# Primeri računa za upitnik (prijava 1795, deo „Примери рачуна са ЕСИР-а према класификацији“)

Izdati 18.11.2026. aplikacijom Hospes POS 0.0.1 na razvojnom L-PFR-u Poreske uprave (sandbox), papir 80 mm.
Ime svake slike je PFR broj računa, kako upitnik traži (8 znakova-8 znakova-broj).

Na svim računima: kasir „Marko Petrović“, ЕСИР број `1795/0.0.1`, QR kod 48,5 mm i tekst ispod
„КРАЈ ФИСКАЛНОГ РАЧУНА“ („Hvala na poseti! / Hospes POS“; polje za reklamu, obavezno za Napredni ESIR).
Na računima sa kupcem: ИД купца `10:123456789` i Опционо поље купца `20:1234567`.

| Pitanje | Vrsta računa (upitnik) | Slika za prilog |
|---|---|---|
| 2 | Промет Продаја са идентификацијом купца | `7EHXTU6T-7EHXTU6T-70.png` |
| 3 | Промет Рефундација са идентификацијом купца | `7EHXTU6T-7EHXTU6T-71.png` |
| 4 | Копија Продаја са идентификацијом купца | `7EHXTU6T-7EHXTU6T-73.png` |
| 5 | Копија Рефундација са идентификацијом купца | `7EHXTU6T-7EHXTU6T-72.png` |
| 6 | Предрачун Продаја са идентификацијом купца | `7EHXTU6T-7EHXTU6T-74.png` |
| 7 | Предрачун Рефундација са идентификацијом купца | `7EHXTU6T-7EHXTU6T-75.png` |
| 8 | Обука Продаја са идентификацијом купца | `7EHXTU6T-7EHXTU6T-76.png` |
| 9 | Обука Рефундација са идентификацијом купца | `7EHXTU6T-7EHXTU6T-77.png` |
| 10 | Аванс Продаја са идентификацијом купца | `7EHXTU6T-7EHXTU6T-78.png` |
| 11 | Аванс Рефундација са идентификацијом купца | `7EHXTU6T-7EHXTU6T-79.png` |
| 12 | Промет Продаја | `7EHXTU6T-7EHXTU6T-81.png` |
| 13 | Копија Продаје | `7EHXTU6T-7EHXTU6T-82.png` |
| 14 | Предрачун Продаја | `7EHXTU6T-7EHXTU6T-83.png` |
| 15 | Обука Продаја | `7EHXTU6T-7EHXTU6T-84.png` |
| 16 | Аванс Продаја | `7EHXTU6T-7EHXTU6T-85.png` |

Dodatno (nije posebno pitanje, ali pokazuje ceo avansni tok iz 9.1): `7EHXTU6T-7EHXTU6T-80.png`,
konačni račun Promet Prodaja posle avansa: poziv na Avans Refundaciju #79, PFR-u ide samo ostatak, a ispod računa
„Плаћено авансом“, „ПДВ на аванс“, „Преостало за плаћање“ i poslednji avansni račun.

Kako su nastali (redosled, isti kao u upitniku):

- #70 Promet Prodaja sa kupcem, gotovina, kupac dao 1.000 (ispod računa „Повраћај“).
- #71 refundacija celog #70 gotovinom; odmah zatim #72 Kopija Refundacija sa redom za potpis kupca (PURS 9.6).
- #73 Kopija računa #70.
- #74 Predračun Prodaja sa kupcem, #75 njegova refundacija.
- #76 Obuka Prodaja sa kupcem (kartica), #77 njena refundacija.
- #78 Avans Prodaja sa kupcem (prenos na račun, uplata pre dva dana: „ЕСИР време“, PURS 9.2).
- #79 Avans Refundacija (zatvaranje avansa) i #80 konačni račun.
- #81 Promet Prodaja (kartica), #82 njegova kopija, #83 Predračun Prodaja, #84 Obuka Prodaja, #85 Avans Prodaja.

Provereno pre priloga: na svakoj slici QR kod daje adresu za proveru tog računa (ZXing), a tekst računa sadrži
ЕСИР број, kasira, tekst ispod računa, kod računa sa kupcem ИД купца i Опционо поље купца, kod #78 ЕСИР време,
a kod kopija i refundacija Реф. број.
