#!/usr/bin/env python3
"""Pravi PDF dokumentaciju za prijavu ESIR-a (docs/pdf/) iz Markdown fajlova u docs/.

Pokretanje (iz korena projekta):  python3 -I docs/napravi-pdf.py
Potreban je Google Chrome (za drugu putanju: promenljiva okruženja CHROME).

Podržan je samo deo Markdown-a koji koriste uputstva: naslovi, pasusi, liste (i ugnježdene), tabele,
slike (sama u redu, sa opisom ispod), blok koda, **podebljano**, `kod` i [link](adresa).
"""
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

DOCS = Path(__file__).resolve().parent
IZLAZ = DOCS / "pdf"
DOKUMENTI = [
    ("opis-proizvoda.md", "Hospes-POS-opis-proizvoda.pdf"),
    ("korisnicko-uputstvo.md", "Hospes-POS-korisnicko-uputstvo.pdf"),
    ("uputstvo-instalacija.md", "Hospes-POS-uputstvo-za-instalaciju.pdf"),
    ("uputstvo-konfiguracija.md", "Hospes-POS-uputstvo-za-konfiguraciju.pdf"),
]
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

CSS = """
@page {
  size: A4;
  margin: 18mm 17mm 20mm 17mm;
  @bottom-left { content: "PODNOZJE"; font: 8pt "Helvetica Neue", Arial, sans-serif; color: #777; }
  @bottom-right { content: "Strana " counter(page) " od " counter(pages); font: 8pt "Helvetica Neue", Arial, sans-serif; color: #777; }
}
html { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
body { font: 10.5pt/1.45 "Helvetica Neue", Arial, sans-serif; color: #1a1a1a; margin: 0; }
h1 { font-size: 20pt; margin: 0 0 4pt; }
h1 + p.meta { color: #555; font-size: 9.5pt; margin: 0 0 14pt; padding-bottom: 8pt; border-bottom: 1.5pt solid #1f4e79; }
h2 { font-size: 14pt; color: #1f4e79; margin: 18pt 0 6pt; border-bottom: 0.5pt solid #c8d3df; padding-bottom: 2pt;
     break-after: avoid; }
h3 { font-size: 11.5pt; margin: 12pt 0 4pt; break-after: avoid; }
p { margin: 0 0 6pt; }
ul, ol { margin: 0 0 6pt; padding-left: 18pt; }
li { margin: 1.5pt 0; }
li > ul, li > ol { margin: 2pt 0; }
code { font: 9pt Menlo, Consolas, monospace; background: #f1f3f5; padding: 0 2pt; border-radius: 2pt; }
pre { font: 9pt/1.4 Menlo, Consolas, monospace; background: #f1f3f5; padding: 6pt 8pt; border-radius: 3pt;
      white-space: pre-wrap; break-inside: avoid; }
pre code { background: none; padding: 0; }
table { border-collapse: collapse; width: 100%; margin: 4pt 0 10pt; font-size: 9.5pt; break-inside: auto; }
tr { break-inside: avoid; }
th, td { border: 0.5pt solid #b8c2cc; padding: 3pt 5pt; text-align: left; vertical-align: top; }
th { background: #e8eef4; }
td:empty::after { content: "\\00a0"; }
table.kv td:first-child { width: 30%; font-weight: 600; background: #f5f7f9; }
figure { margin: 6pt 0 12pt; text-align: center; break-inside: avoid; }
figure img { max-width: 100%; max-height: 105mm; border: 0.5pt solid #b8c2cc; }
figcaption { font-size: 8.5pt; color: #555; margin-top: 3pt; }
a { color: #1f4e79; text-decoration: none; }
"""

LISTA = re.compile(r"^(\s*)([-*]|\d+\.)\s+(.*)$")
SLIKA = re.compile(r"^!\[([^\]]*)\]\(([^)\s]+)\)$")


def inline(tekst: str) -> str:
    """Escape + **podebljano**, `kod`, [link](adresa), ![slika](adresa)."""
    kodovi = []

    def sacuvaj_kod(m):
        kodovi.append("<code>" + html.escape(m.group(1)) + "</code>")
        return f"\x00{len(kodovi) - 1}\x00"

    t = re.sub(r"`([^`]+)`", sacuvaj_kod, tekst)
    t = html.escape(t, quote=False)
    t = re.sub(r"!\[([^\]]*)\]\(([^)\s]+)\)", r'<img alt="\1" src="\2">', t)
    t = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', t)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    return re.sub(r"\x00(\d+)\x00", lambda m: kodovi[int(m.group(1))], t)


def lista(redovi, i):
    """Lista koja počinje u redu i; vraća (html, sledeći red). Uvučeni redovi bez oznake nastavljaju stavku."""
    m = LISTA.match(redovi[i])
    uvlacenje = len(m.group(1))
    tag = "ol" if m.group(2)[0].isdigit() else "ul"
    pocetak = f' start="{m.group(2)[:-1]}"' if tag == "ol" and m.group(2) != "1." else ""
    stavke = []
    while i < len(redovi):
        m = LISTA.match(redovi[i])
        if m and len(m.group(1)) == uvlacenje:
            stavke.append([m.group(3), ""])
            i += 1
        elif m and len(m.group(1)) > uvlacenje and stavke:
            ugnjezdeno, i = lista(redovi, i)
            stavke[-1][1] += ugnjezdeno
        elif redovi[i].strip() and redovi[i].startswith(" ") and stavke and not m:
            stavke[-1][0] += " " + redovi[i].strip()
            i += 1
        else:
            break
    telo = "".join(f"<li>{inline(t)}{pod}</li>" for t, pod in stavke)
    return f"<{tag}{pocetak}>{telo}</{tag}>", i


def tabela(redovi):
    def celije(red):
        return [c.strip() for c in red.strip().strip("|").split("|")]

    zaglavlje, telo = celije(redovi[0]), [celije(r) for r in redovi[2:]]
    out = "<table>" if any(zaglavlje) else '<table class="kv">'
    if any(zaglavlje):
        out += "<thead><tr>" + "".join(f"<th>{inline(c)}</th>" for c in zaglavlje) + "</tr></thead>"
    out += "<tbody>"
    for red in telo:
        out += "<tr>" + "".join(f"<td>{inline(c)}</td>" for c in red) + "</tr>"
    return out + "</tbody></table>"


def u_html(md: str) -> tuple[str, str]:
    """Markdown → (naslov, telo HTML-a)."""
    redovi = md.splitlines()
    delovi, naslov, i = [], "", 0
    while i < len(redovi):
        red = redovi[i]
        s = red.strip()
        if not s:
            i += 1
        elif s.startswith("```"):
            kraj = next(j for j in range(i + 1, len(redovi)) if redovi[j].strip().startswith("```"))
            delovi.append("<pre><code>" + html.escape("\n".join(redovi[i + 1:kraj])) + "</code></pre>")
            i = kraj + 1
        elif m := re.match(r"^(#{1,3})\s+(.*)$", s):
            nivo = len(m.group(1))
            if nivo == 1 and not naslov:
                naslov = m.group(2)
            delovi.append(f"<h{nivo}>{inline(m.group(2))}</h{nivo}>")
            i += 1
        elif s.startswith("|") and i + 1 < len(redovi) and re.match(r"^\|[\s:|-]+\|$", redovi[i + 1].strip()):
            j = i
            while j < len(redovi) and redovi[j].strip().startswith("|"):
                j += 1
            delovi.append(tabela(redovi[i:j]))
            i = j
        elif m := SLIKA.match(s):
            opis, izvor = m.groups()
            delovi.append(f'<figure><img src="{html.escape(izvor)}" alt="{html.escape(opis)}">'
                          f"<figcaption>{inline(opis)}</figcaption></figure>")
            i += 1
        elif LISTA.match(red):
            deo, i = lista(redovi, i)
            delovi.append(deo)
        else:
            pasus = []
            while i < len(redovi) and redovi[i].strip() and not LISTA.match(redovi[i]) \
                    and not re.match(r"^(#{1,3}\s|```|\||!\[)", redovi[i].strip()):
                pasus.append(redovi[i].strip())
                i += 1
            klasa = ' class="meta"' if delovi and delovi[-1].startswith("<h1>") and len(delovi) == 1 else ""
            delovi.append(f"<p{klasa}>{inline(' '.join(pasus))}</p>")
    return naslov, "\n".join(delovi)


def stranica(naslov: str, telo: str) -> str:
    podnozje = naslov.replace("\\", "").replace('"', "'")
    return (
        '<!doctype html><html lang="sr-Latn"><head><meta charset="utf-8">'
        f'<base href="{DOCS.as_uri()}/"><title>{html.escape(naslov)}</title>'
        f"<style>{CSS.replace('PODNOZJE', podnozje)}</style></head><body>{telo}</body></html>"
    )


def main():
    if not Path(CHROME).exists():
        sys.exit(f"Nema Chrome-a na putanji {CHROME} (podesite promenljivu CHROME).")
    IZLAZ.mkdir(exist_ok=True)
    privremeno = Path(tempfile.mkdtemp(prefix="hospes-pdf-"))
    try:
        for md, pdf in DOKUMENTI:
            naslov, telo = u_html((DOCS / md).read_text(encoding="utf-8"))
            strana = privremeno / (Path(md).stem + ".html")
            strana.write_text(stranica(naslov, telo), encoding="utf-8")
            subprocess.run(
                [CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", "--allow-file-access-from-files",
                 f"--user-data-dir={privremeno / 'chrome'}", f"--print-to-pdf={IZLAZ / pdf}", strana.as_uri()],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120,
            )
            print(f"{md} → pdf/{pdf}")
    finally:
        shutil.rmtree(privremeno, ignore_errors=True)


if __name__ == "__main__":
    main()
