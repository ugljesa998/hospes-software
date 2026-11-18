package rs.omniit.hospes.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rs.omniit.hospes.esir.Artikal
import rs.omniit.hospes.esir.Esir
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.naziv
import rs.omniit.hospes.esir.pretrazi
import rs.omniit.hospes.esir.ukupno

private val CRVENA = Color(0xFFB3261E)
private val ZELENA = Color(0xFF1B6E3A)

/** ESIR test ekran: stanje PFR-a, obračun i izdavanje računa, prikaz računa i elektronski žurnal. */
@Composable
fun App(okruzenje: Okruzenje) {
    val scope = rememberCoroutineScope()
    val model = remember { EsirModel(okruzenje, scope) }
    LaunchedEffect(Unit) {
        model.ucitajKatalog()
        model.ucitajZurnal()
        model.osvezi()
    }
    DisposableEffect(Unit) { onDispose { model.zatvori() } }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F4F2)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hospes ESIR ${Esir.VERSION}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(12.dp))
                    Text("test ekran · sandbox Poreske uprave", color = Color.Gray)
                    Spacer(Modifier.weight(1f))
                    if (model.radi) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PfrKartica(model)
                        RacunKartica(model)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        DesnaStrana(model)
                    }
                }
            }
        }
    }
}

@Composable
private fun PfrKartica(m: EsirModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PFR", fontWeight = FontWeight.SemiBold)
                Izbor(VrstaPfra.entries, m.vrstaPfra, { it.naziv }, naIzbor = m::izaberiPfr)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = m::osvezi, enabled = !m.radi) { Text("Osveži") }
                if (m.vrstaPfra == VrstaPfra.LPFR) {
                    OutlinedButton(onClick = m::posaljiPin, enabled = !m.radi) { Text("Pošalji PIN") }
                }
            }
            Text(m.opisPfra ?: "Veza još nije otvorena", color = Color.Gray, fontSize = 13.sp)
            val s = m.status
            when {
                m.dostupan == false -> Text("PFR se ne javlja", color = CRVENA)
                s == null -> Text("Stanje još nije učitano", color = Color.Gray)
                else -> {
                    Text(
                        buildString {
                            append("Dostupan · ${s.uid ?: "?"}")
                            s.isPinRequired?.let { append(if (it) " · traži PIN" else " · PIN unet") }
                        },
                        color = if (s.isPinRequired == true) CRVENA else ZELENA,
                    )
                    m.sifreStanja.forEach { Text(it, fontSize = 13.sp) }
                    Text(
                        "Stope: " + m.stope.joinToString("  ") { "${it.label} ${it.rate}%" },
                        fontSize = 13.sp,
                        color = Color.DarkGray,
                    )
                }
            }
        }
    }
}

@Composable
private fun RacunKartica(m: EsirModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Račun", fontWeight = FontWeight.SemiBold)
                Izbor(listOf(VrstaRacuna.OBUKA, VrstaRacuna.PROMET, VrstaRacuna.PREDRACUN), m.vrstaRacuna, { "${it.naziv} Prodaja" }) {
                    m.vrstaRacuna = it
                }
            }

            var rucno by remember { mutableStateOf(false) }
            Izbor(listOf(false, true), rucno, { if (it) "Ručni unos" else "Iz kataloga" }, kompaktno = true) { rucno = it }
            if (rucno) UnosStavke(m) else IzKataloga(m)

            if (m.stavke.isEmpty()) {
                Text("Nema stavki", color = Color.Gray)
            } else {
                m.stavke.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.name} (${s.labels.joinToString()})", Modifier.weight(1f))
                        Text("${s.quantity.toString().replace('.', ',')} × ${s.unitPrice.zaStampu()}", Modifier.width(150.dp), color = Color.DarkGray)
                        Text(s.totalAmount.zaStampu(), Modifier.width(100.dp), fontWeight = FontWeight.Medium)
                        TextButton(onClick = { m.ukloniStavku(i) }) { Text("Ukloni") }
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ukupno", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${m.ukupno.zaStampu()} RSD", style = MaterialTheme.typography.titleLarge)
            }

            Text("Plaćanje", fontWeight = FontWeight.SemiBold)
            Izbor(
                listOf(NacinPlacanja.GOTOVINA, NacinPlacanja.PLATNA_KARTICA, NacinPlacanja.PRENOS_NA_RACUN, NacinPlacanja.INSTANT_PLACANJE),
                m.nacinPlacanja,
                { it.naziv },
            ) { m.nacinPlacanja = it }
            if (m.nacinPlacanja == NacinPlacanja.GOTOVINA) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(m.primljeno, { m.primljeno = it }, label = { Text("Kupac dao") }, singleLine = true, modifier = Modifier.width(160.dp))
                    m.kusur?.let { Text("Kusur: ${it.zaStampu()} RSD", style = MaterialTheme.typography.titleMedium) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Štampač (virtuelni), papir:", fontSize = 13.sp)
                Izbor(SirinaPapira.entries, m.papir, { it.naziv }, kompaktno = true) { m.papir = it }
            }

            Button(
                onClick = m::izdaj,
                enabled = !m.radi && m.stavke.isNotEmpty() && m.status != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Izdaj račun", fontSize = 16.sp) }

            m.poruka?.let { Text(it.tekst, color = if (it.greska) CRVENA else ZELENA) }
        }
    }
}

/**
 * Prodaja iz kataloga: klik na artikal ga dodaje (ponovni klik povećava količinu).
 * Polje za pretragu prima i bar-kod čitač: čitač ukuca kod i pritisne Enter, pa se dodaje tačno taj artikal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IzKataloga(m: EsirModel) {
    var trazi by remember { mutableStateOf("") }
    var greska by remember { mutableStateOf<String?>(null) }
    val pronadjeni = m.artikli.pretrazi(trazi)

    fun enter() {
        if (trazi.isBlank()) return
        val tacan = m.artikli.firstOrNull { it.sifra == trazi.trim() || it.gtin == trazi.trim() }
        greska = when {
            tacan != null -> m.dodajPoKodu(trazi)
            pronadjeni.size == 1 -> m.dodajArtikal(pronadjeni.single())
            else -> "Nema tačnog poklapanja za „${trazi.trim()}“; izaberi artikal ispod"
        }
        if (greska == null) trazi = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.artikli.isEmpty()) {
            Text("Katalog je prazan. Dodaj artikle ili uvezi CSV desno, u kartici „Katalog artikala“.", color = Color.Gray)
            return@Column
        }
        OutlinedTextField(
            trazi,
            { trazi = it; greska = null },
            label = { Text("Traži: naziv, šifra ili bar-kod (Enter dodaje)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { enter() }),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    enter()
                    true
                } else {
                    false
                }
            },
        )
        val prikaz = pronadjeni.take(MAKS_DUGMADI)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            prikaz.forEach { a -> DugmeArtikla(a) { greska = m.dodajArtikal(a) } }
        }
        when {
            pronadjeni.isEmpty() -> Text("Nijedan artikal ne odgovara pretrazi.", color = Color.Gray, fontSize = 13.sp)
            pronadjeni.size > prikaz.size -> Text("Prikazano ${prikaz.size} od ${pronadjeni.size}; suzi pretragu.", color = Color.Gray, fontSize = 13.sp)
        }
        greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
    }
}

private const val MAKS_DUGMADI = 40

@Composable
private fun DugmeArtikla(a: Artikal, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Column {
            Text(a.naziv, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("${a.cena.zaStampu()} /${a.jedinicaMere} · ${a.oznaka}", fontSize = 12.sp, color = Color.Gray, maxLines = 1)
        }
    }
}

@Composable
private fun UnosStavke(m: EsirModel) {
    var naziv by remember { mutableStateOf("") }
    var kolicina by remember { mutableStateOf("1") }
    var cena by remember { mutableStateOf("") }
    var popust by remember { mutableStateOf("") }
    var oznaka by remember { mutableStateOf<String?>(null) }
    var greska by remember { mutableStateOf<String?>(null) }

    val oznake = m.stope.map { it.label }
    if (oznaka == null || oznaka !in oznake) oznaka = oznake.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(naziv, { naziv = it }, label = { Text("Naziv") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(kolicina, { kolicina = it }, label = { Text("Kol.") }, singleLine = true, modifier = Modifier.width(80.dp))
            OutlinedTextField(cena, { cena = it }, label = { Text("Cena") }, singleLine = true, modifier = Modifier.width(110.dp))
            OutlinedTextField(popust, { popust = it }, label = { Text("Popust", maxLines = 1) }, suffix = { Text("%") }, singleLine = true, modifier = Modifier.width(110.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Oznaka:", fontSize = 13.sp)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Izbor(oznake, oznaka, { it }, kompaktno = true) { oznaka = it }
            }
            Button(onClick = {
                greska = m.dodajStavku(naziv, kolicina, cena, oznaka, popust)
                if (greska == null) {
                    naziv = ""; kolicina = "1"; cena = ""; popust = ""
                }
            }) { Text("Dodaj stavku") }
        }
        greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
    }
}

private enum class Desno(val naslov: String) { RACUN("Prikaz računa"), ZURNAL("Žurnal (30 dana)"), KATALOG("Katalog artikala") }

@Composable
private fun ColumnScope.DesnaStrana(m: EsirModel) {
    var desno by remember { mutableStateOf(Desno.RACUN) }
    var izgled by remember { mutableStateOf<SirinaPapira?>(m.papir) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Izbor(Desno.entries, desno, { it.naslov }) {
            desno = it
            if (it == Desno.ZURNAL) m.ucitajZurnal()
            if (it == Desno.KATALOG) m.ucitajKatalog()
        }
    }
    Spacer(Modifier.height(8.dp))
    if (desno == Desno.KATALOG) {
        KatalogArtikala(m)
    } else if (desno == Desno.ZURNAL) {
        ZurnalLista(m) { desno = Desno.RACUN }
    } else {
        val p = m.prikazan
        if (p == null) {
            Text("Izdaj račun ili ga izaberi u žurnalu.", color = Color.Gray)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.naslov, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Izbor(listOf(null, SirinaPapira.MM58, SirinaPapira.MM80), izgled, { it?.let { "Papir ${it.naziv}" } ?: "Tekst" }, kompaktno = true) {
                    izgled = it
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                val papir = izgled
                if (papir == null) Papir(p) else OtisakNaPapiru(m, p, papir)
            }
        }
    }
}

/** Račun kako izgleda na papiru: tekst od PFR-a, QR kod pre završne linije, pa ESIR dodatak. */
@Composable
private fun Papir(p: PrikazRacuna) {
    val redovi = p.tekst.trimEnd().lines()
    val krajPfra = redovi.indexOfLast { it.startsWith("=====") && ("КРАЈ" in it || "ОВО НИЈЕ ФИСКАЛНИ РАЧУН" in it) }
    val qr = remember(p) { p.qr?.uSliku() }

    Surface(color = Color.White, border = BorderStroke(1.dp, Color(0xFFDDDDDD)), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp).horizontalScroll(rememberScrollState())) {
            redovi.forEachIndexed { i, red ->
                if (i == krajPfra && qr != null) {
                    Image(qr, contentDescription = "QR kod za proveru računa", modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                }
                RedRacuna(red)
            }
        }
    }
}

/** Račun tačno onako kako ga štampač odštampa (svaka tačka štampača), u umanjenoj veličini; samo prikaz. */
@Composable
private fun OtisakNaPapiru(m: EsirModel, p: PrikazRacuna, papir: SirinaPapira) {
    val rezultat = remember(p.broj, papir) { m.pregledOtiska(p.broj, papir) }
    val slika = rezultat.getOrNull()
    when {
        rezultat.isFailure -> Text("Otisak nije moguće nacrtati: ${rezultat.exceptionOrNull()?.message}", color = CRVENA)
        slika == null -> Text("Za ovaj račun nema teksta od PFR-a", color = Color.Gray)
        else -> Image(
            slika,
            contentDescription = "Račun na papiru ${papir.naziv}",
            modifier = Modifier.size((slika.width * 0.75f).dp, (slika.height * 0.75f).dp),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** „ОВО НИЈЕ ФИСКАЛНИ РАЧУН“ se štampa najmanje duplo većim slovima (ESIR upitnik, deo 16, P10). */
@Composable
private fun RedRacuna(red: String) {
    val upozorenje = "ОВО НИЈЕ ФИСКАЛНИ РАЧУН" in red
    Text(
        red,
        fontFamily = FontFamily.Monospace,
        fontSize = if (upozorenje) 22.sp else 11.sp,
        fontWeight = if (upozorenje) FontWeight.Bold else FontWeight.Normal,
        softWrap = false,
        maxLines = 1,
    )
}

/**
 * Katalog artikala (ESIR upitnik deo 11): novi artikal (P1), izmena i promena cene (P3),
 * pretraga po nazivu ili GTIN-u (P5), uvoz i izvoz CSV (P6).
 */
@Composable
private fun ColumnScope.KatalogArtikala(m: EsirModel) {
    var menja by remember { mutableStateOf<String?>(null) }
    var sifra by remember { mutableStateOf("") }
    var naziv by remember { mutableStateOf("") }
    var jm by remember { mutableStateOf("kom") }
    var cena by remember { mutableStateOf("") }
    var oznaka by remember { mutableStateOf<String?>(null) }
    var gtin by remember { mutableStateOf("") }
    var kategorija by remember { mutableStateOf("") }
    var greska by remember { mutableStateOf<String?>(null) }
    var trazi by remember { mutableStateOf("") }

    fun ocisti() {
        menja = null; sifra = ""; naziv = ""; jm = "kom"; cena = ""; gtin = ""; kategorija = ""; greska = null
    }

    fun izmeni(a: Artikal) {
        menja = a.sifra; sifra = a.sifra; naziv = a.naziv; jm = a.jedinicaMere; cena = a.cena.toString().replace('.', ',')
        oznaka = a.oznaka; gtin = a.gtin.orEmpty(); kategorija = a.kategorija.orEmpty(); greska = null
    }

    // Oznake iz PFR-a; oznaka artikla koji se menja ostaje vidljiva i ako je PFR više ne vraća.
    val oznake = (m.stope.map { it.label } + listOfNotNull(oznaka.takeIf { menja != null })).distinct()
    if (oznaka == null || oznaka !in oznake) oznaka = oznake.firstOrNull()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(menja?.let { "Izmena artikla $it" } ?: "Novi artikal", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sifra, { sifra = it }, label = { Text("Šifra") }, singleLine = true, enabled = menja == null, modifier = Modifier.width(100.dp))
                OutlinedTextField(naziv, { naziv = it }, label = { Text("Naziv") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(jm, { jm = it }, label = { Text("JM") }, singleLine = true, modifier = Modifier.width(80.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cena, { cena = it }, label = { Text("Cena") }, singleLine = true, modifier = Modifier.width(120.dp))
                OutlinedTextField(gtin, { gtin = it }, label = { Text("GTIN (bar-kod)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(kategorija, { kategorija = it }, label = { Text("Kategorija") }, singleLine = true, modifier = Modifier.width(150.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Oznaka:", fontSize = 13.sp)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    if (oznake.isEmpty()) {
                        Text("oznake stižu iz PFR-a; osveži PFR", color = Color.Gray, fontSize = 13.sp)
                    } else {
                        Izbor(oznake, oznaka, { it }, kompaktno = true) { oznaka = it }
                    }
                }
                if (menja != null) TextButton(onClick = ::ocisti) { Text("Odustani") }
                Button(onClick = {
                    greska = m.sacuvajArtikal(menja == null, sifra, naziv, jm, cena, oznaka, gtin, kategorija)
                    if (greska == null) ocisti()
                }) { Text("Sačuvaj") }
            }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(trazi, { trazi = it }, label = { Text("Traži: naziv, šifra, GTIN, kategorija") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = m::uveziIzFajla) { Text("Uvoz CSV") }
        OutlinedButton(onClick = m::izveziUFajl, enabled = m.artikli.isNotEmpty()) { Text("Izvoz CSV") }
    }
    m.porukaKataloga?.let { Text(it.tekst, color = if (it.greska) CRVENA else ZELENA, fontSize = 13.sp) }
    Spacer(Modifier.height(6.dp))
    val prikaz = m.artikli.pretrazi(trazi)
    Text("${prikaz.size} od ${m.artikli.size} artikala", color = Color.Gray, fontSize = 12.sp)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(prikaz, key = { it.sifra }) { a ->
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${a.sifra} · ${a.nazivNaRacunu}")
                    Text(
                        listOfNotNull("oznaka ${a.oznaka}", a.gtin?.let { "GTIN $it" }, a.kategorija).joinToString(" · "),
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                }
                Text("${a.cena.zaStampu()} RSD", fontWeight = FontWeight.Medium)
                TextButton(onClick = { izmeni(a) }) { Text("Izmeni") }
                TextButton(onClick = { m.ukloniArtikal(a.sifra); if (menja == a.sifra) ocisti() }) { Text("Ukloni", color = CRVENA) }
            }
        }
    }
}

@Composable
private fun ZurnalLista(m: EsirModel, posleIzbora: () -> Unit) {
    if (m.zurnal.isEmpty()) {
        Text("U žurnalu nema računa za poslednjih 30 dana.", color = Color.Gray)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(m.zurnal) { r ->
            Row(
                Modifier.fillMaxWidth().background(Color.White).clickable { m.prikazi(r); posleIzbora() }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.racun.invoiceNumber, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("${r.racun.sdcDateTime?.take(16)?.replace('T', ' ') ?: "-"} · ${r.oznaka}", fontSize = 12.sp, color = Color.Gray)
                }
                Text("${r.ukupno.zaStampu()} RSD", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Mali izbor jedne od ponuđenih vrednosti (dugmad); [kompaktno] za kratke oznake (npr. poreske). */
@Composable
private fun <T> Izbor(opcije: List<T>, izabrano: T?, tekst: (T) -> String, kompaktno: Boolean = false, naIzbor: (T) -> Unit) {
    val razmak = if (kompaktno) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding
    val visina = if (kompaktno) Modifier.height(34.dp) else Modifier
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opcije.forEach { o ->
            if (o == izabrano) {
                Button(onClick = { naIzbor(o) }, contentPadding = razmak, modifier = visina) { Text(tekst(o)) }
            } else {
                OutlinedButton(onClick = { naIzbor(o) }, contentPadding = razmak, modifier = visina) { Text(tekst(o)) }
            }
        }
    }
}
