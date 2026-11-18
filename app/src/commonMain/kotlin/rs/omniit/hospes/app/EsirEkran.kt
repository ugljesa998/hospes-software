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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rs.omniit.hospes.esir.Artikal
import rs.omniit.hospes.esir.AvansniPosao
import rs.omniit.hospes.esir.SifraAvansa
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.Esir
import rs.omniit.hospes.esir.IzdatRacun
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.OgranicenjePlacanja
import rs.omniit.hospes.esir.SifarnikKupca
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.naziv
import rs.omniit.hospes.esir.pretrazi
import rs.omniit.hospes.esir.datum
import rs.omniit.hospes.esir.mozePonistavanje
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

    // Prvo pokretanje (nijedan PFR nije podešen): odmah podešavanja.
    var desno by remember {
        mutableStateOf(if (model.podesavanja.lpfr == null && model.podesavanja.vpfr == null) Desno.PODESAVANJA else Desno.RACUN)
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F4F2)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Esir.PROIZVOD} ${Esir.VERSION}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(16.dp))
                    if (model.radi) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.weight(1f))
                    IzborKasira(model) { desno = Desno.PODESAVANJA }
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
                        DesnaStrana(model, desno) { desno = it }
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
            if (m.vrstaPfra == VrstaPfra.LPFR && m.status?.isPinRequired == true) UnosPina(m)
        }
    }
}

@Composable
private fun RacunKartica(m: EsirModel) {
    val konacni = m.konacniPosao
    val avans = konacni == null && m.vrstaRacuna == VrstaRacuna.AVANS
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Račun", fontWeight = FontWeight.SemiBold)
                if (konacni != null) {
                    Text("Konačni račun posle avansa", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = m::otkaziKonacni) { Text("Otkaži") }
                } else if (m.posebanSlucaj == PosebanSlucaj.NE) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Izbor(
                            listOf(VrstaRacuna.OBUKA, VrstaRacuna.PROMET, VrstaRacuna.PREDRACUN, VrstaRacuna.AVANS),
                            m.vrstaRacuna,
                            { it.naziv },
                        ) { m.vrstaRacuna = it }
                    }
                } else {
                    Text("Promet Prodaja · ${m.posebanSlucaj.naziv.lowercase()}", fontWeight = FontWeight.Medium)
                }
            }

            if (konacni != null) {
                Text(
                    "Dodaj sve što se prodaje, po punoj ceni (npr. najam vozila za ceo period). " +
                        "Avansi (${konacni.poslednji.racun.invoiceNumber} i ranije): ${konacni.ukupno.zaStampu()} RSD se oduzimaju, kupac plaća samo ostatak.",
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                )
            }

            val zaNaplatu = if (avans) {
                UnosAvansa(m)
                runCatching { Decimal4.parse(m.avansIznos.trim().replace(',', '.')) }.getOrNull() ?: Decimal4.NULA
            } else {
                var unos by remember { mutableStateOf(NacinUnosa.KATALOG) }
                Izbor(NacinUnosa.entries, unos, { it.naziv }, kompaktno = true) { unos = it }
                when (unos) {
                    NacinUnosa.KATALOG -> IzKataloga(m)
                    NacinUnosa.RUCNO -> UnosStavke(m)
                    NacinUnosa.VAUCER -> UnosVaucera(m)
                }

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
                m.ukupno
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (avans) "Avans" else "Ukupno", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${zaNaplatu.zaStampu()} RSD", style = MaterialTheme.typography.titleLarge)
            }
            val ostatak = m.ostatakPosleAvansa
            if (konacni != null && ostatak != null) {
                Row {
                    Text("Plaćeno avansom", Modifier.weight(1f))
                    Text("− ${konacni.ukupno.zaStampu()} RSD")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ostatak za plaćanje", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    val premalo = ostatak < Decimal4.NULA && m.stavke.isNotEmpty()
                    Text("${ostatak.zaStampu()} RSD", style = MaterialTheme.typography.titleLarge, color = if (premalo) CRVENA else Color.Unspecified)
                }
                if (ostatak < Decimal4.NULA && m.stavke.isNotEmpty()) Text("Avansi su veći od računa: dodaj stavke (delimična realizacija avansa još nije podržana).", color = CRVENA, fontSize = 12.sp)
            }

            if (!avans && konacni == null) PosebanSlucajRacuna(m)

            if (m.posebanSlucaj == PosebanSlucaj.NE) NaplataRacuna(m, ostatak ?: zaNaplatu)

            val kupacSaAvansa = m.kupacSaAvansa
            when {
                konacni != null && kupacSaAvansa != null -> Text("Kupac: $kupacSaAvansa (sa avansa)", fontSize = 13.sp)
                konacni != null -> {
                    Text("Kupac je obavezan (nema ga na avansu): unesi ga ovde.", color = CRVENA, fontSize = 12.sp)
                    KupacNaRacunu(m, otvoriOdmah = true)
                }
                m.posebanSlucaj != PosebanSlucaj.KORPORATIVNA -> KupacNaRacunu(m)
            }

            Text("Štampa: virtuelni štampač, papir ${m.papir.naziv} (Podešavanja)", color = Color.Gray, fontSize = 12.sp)

            Button(
                onClick = m::izdaj,
                enabled = !m.radi && m.status != null && (if (avans) m.avansIznos.isNotBlank() else m.stavke.isNotEmpty()),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    when {
                        konacni != null && konacni.zatvaranje == null -> "Zatvori avanse i izdaj konačni račun"
                        konacni != null -> "Izdaj konačni račun"
                        avans -> "Izdaj avans"
                        else -> "Izdaj račun"
                    },
                    fontSize = 16.sp,
                )
            }
            if (konacni != null) {
                Text(
                    if (konacni.zatvaranje == null) {
                        "Izdaju se dva računa: Avans Refundacija (zatvara avanse, ostaje u radnji) i Promet Prodaja za kupca."
                    } else {
                        "Avansi su već zatvoreni (${konacni.zatvaranje?.racun?.invoiceNumber}); izdaje se Promet Prodaja za kupca."
                    },
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                )
            }

            m.poruka?.let { Text(it.tekst, color = if (it.greska) CRVENA else ZELENA) }
        }
    }
}

/**
 * Uplata avansa (PURS 9.1): iznos, poreska oznaka i šifra avansa (sama se bira po stopi), datum uplate kada je
 * novac stigao ranije (9.2). Sledeći avans istog posla se započinje iz žurnala („Sledeći avans“).
 */
@Composable
private fun UnosAvansa(m: EsirModel) {
    val oznake = m.stope.map { it.label }
    if (m.avansOznaka == null || m.avansOznaka !in oznake) m.avansOznaka = oznake.firstOrNull()
    val sifra = m.sifraAvansa()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val prethodni = m.prethodniAvans
        if (prethodni == null) {
            Text("Prvi avans novog posla. Sledeći avans istog posla: u žurnalu izaberi avans, pa „Sledeći avans“.", color = Color.Gray, fontSize = 12.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sledeći avans, poziva se na ${prethodni.racun.invoiceNumber}", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                TextButton(onClick = m::noviAvansniPosao) { Text("Novi posao") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(m.avansIznos, { m.avansIznos = it }, label = { Text("Iznos avansa") }, singleLine = true, modifier = Modifier.width(160.dp))
            Text("Oznaka:", fontSize = 13.sp)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Izbor(oznake, m.avansOznaka, { it }, kompaktno = true) { m.avansOznaka = it; m.avansSifra = null }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Šifra:", fontSize = 13.sp)
            Izbor(SifraAvansa.entries, sifra, { it.kod.toString() }, kompaktno = true) { m.avansSifra = it }
            Text(sifra?.opis ?: "izaberi šifru za ovu stopu", color = if (sifra == null) CRVENA else Color.DarkGray, fontSize = 12.sp)
        }
        OutlinedTextField(
            m.avansDatumUplate,
            { m.avansDatumUplate = it },
            label = { Text("Datum uplate (gggg-mm-dd), samo ako je novac stigao pre današnjeg dana") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
            Text("Katalog je prazan. Dodaj artikle ili uvezi CSV desno, u kartici „Katalog“.", color = Color.Gray)
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

private enum class Desno(val naslov: String) {
    RACUN("Račun"),
    ZURNAL("Žurnal (30 dana)"),
    KATALOG("Katalog"),
    PODESAVANJA("Podešavanja"),
}

@Composable
private fun ColumnScope.DesnaStrana(m: EsirModel, desno: Desno, naDesno: (Desno) -> Unit) {
    var izgled by remember { mutableStateOf<SirinaPapira?>(m.papir) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Izbor(Desno.entries, desno, { it.naslov }) {
            naDesno(it)
            if (it == Desno.ZURNAL) m.ucitajZurnal()
            if (it == Desno.KATALOG) m.ucitajKatalog()
        }
    }
    Spacer(Modifier.height(8.dp))
    if (desno == Desno.PODESAVANJA) {
        PodesavanjaKase(m)
    } else if (desno == Desno.KATALOG) {
        KatalogArtikala(m)
    } else if (desno == Desno.ZURNAL) {
        MesecnaRefundacijaKartica(m)
        ZurnalLista(m) { naDesno(Desno.RACUN) }
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
            RadnjeRacuna(m, p.izdat)
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

/** Kasir koji izdaje račune (ime se štampa na računu); bez kasira račun se ne izdaje. */
@Composable
private fun IzborKasira(m: EsirModel, otvoriPodesavanja: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kasir:", fontWeight = FontWeight.SemiBold)
        if (m.podesavanja.kasiri.isEmpty()) {
            OutlinedButton(onClick = otvoriPodesavanja) { Text("Dodaj kasira") }
        } else {
            Row(Modifier.widthIn(max = 640.dp).horizontalScroll(rememberScrollState())) {
                Izbor(m.podesavanja.kasiri, m.kasir, { it }, kompaktno = true, naIzbor = m::izaberiKasira)
            }
            if (m.kasir == null) Text("izaberi", color = CRVENA, fontSize = 13.sp)
        }
    }
}

/** PIN kartice: kasir ga unosi kada L-PFR traži; šalje se jednom, a polje se posle slanja briše. */
@Composable
private fun UnosPina(m: EsirModel) {
    var pin by remember { mutableStateOf("") }
    var greska by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            pin,
            { novi -> if (novi.length <= 4 && novi.all(Char::isDigit)) pin = novi; greska = null },
            label = { Text("PIN kartice") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.width(150.dp),
        )
        Button(onClick = { greska = m.posaljiPin(pin); pin = "" }, enabled = !m.radi && pin.length == 4) { Text("Pošalji PIN") }
    }
    Text("L-PFR traži PIN. PIN se šalje jednom; posle 5 pogrešnih kartica se zaključava.", color = Color.DarkGray, fontSize = 12.sp)
    greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
}

/** Polje za tajnu (adresa sa tokenom, lozinka, PAC): skriveno dok se ne klikne „Prikaži“. */
@Composable
private fun TajnoPolje(vrednost: String, naziv: String, promena: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    var vidljivo by remember { mutableStateOf(false) }
    OutlinedTextField(
        vrednost,
        promena,
        label = { Text(naziv) },
        singleLine = true,
        visualTransformation = if (vidljivo) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { vidljivo = !vidljivo }) { Text(if (vidljivo) "Sakrij" else "Prikaži", fontSize = 12.sp) } },
        modifier = modifier,
    )
}

/**
 * Podešavanja kase: kasiri, L-PFR (adresa), V-PFR (sertifikat, lozinka, PAC) i papir u štampaču.
 * Sve se čuva odmah, na računaru kase (ne u Git-u); posle promene PFR-a veza se ponovo proverava.
 */
@Composable
private fun ColumnScope.PodesavanjaKase(m: EsirModel) {
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        m.porukaPodesavanja?.let { Text(it.tekst, color = if (it.greska) CRVENA else ZELENA, fontSize = 13.sp) }
        KarticaOProgramu(m)
        KarticaKasira(m)
        KarticaLpfr(m)
        KarticaVpfr(m)
        KarticaPlacanja(m)
        KarticaTekstaIspodRacuna(m)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Štampač", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Papir:", fontSize = 13.sp)
                    Izbor(SirinaPapira.entries, m.papir, { it.naziv }, kompaktno = true, naIzbor = m::izaberiPapir)
                }
                Text("Za sada virtuelni štampač: slika računa i ESC/POS bajtovi u folderu stampa/.", color = Color.Gray, fontSize = 12.sp)
            }
        }
        Text("Podaci kase: ${m.folderPodataka}", color = Color.Gray, fontSize = 12.sp)
    }
}

/** Proizvođač, proizvod, verzija i serijski broj (upitnik: operativne funkcije, pitanje 6). */
@Composable
private fun KarticaOProgramu(m: EsirModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("O programu", fontWeight = FontWeight.SemiBold)
            listOf(
                "Proizvođač" to Esir.PROIZVODJAC,
                "Proizvod" to Esir.PROIZVOD,
                "Verzija softvera" to Esir.VERSION,
                "Serijski broj" to m.serijskiBroj,
                "Protokol sa PFR-om" to "TaxCore ${Esir.PROTOCOL}",
                "ESIR broj" to "dodeljuje Poreska uprava odobrenjem",
            ).forEach { (naziv, vrednost) ->
                Row {
                    Text(naziv, Modifier.width(170.dp), color = Color.DarkGray, fontSize = 13.sp)
                    Text(vrednost, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Član 6, stav 2 Pravilnika: kasa bez kartice, čeka i instant plaćanja (kasir ih unosi kao gotovinu). */
@Composable
private fun KarticaPlacanja(m: EsirModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Načini plaćanja", fontWeight = FontWeight.SemiBold)
            Izbor(
                listOf(false, true),
                m.ogranicenje != OgranicenjePlacanja.NEMA,
                { if (it) "Bez kartice, čeka i instanta" else "Svih 7 načina" },
                kompaktno = true,
                naIzbor = m::postaviOgranicenje,
            )
            Text(
                "Po Pravilniku (član 6, stav 2) neki obveznici ne evidentiraju platnu karticu, ček i instant plaćanje; " +
                    "kasir ih tada unosi kao gotovinu. Kasa radi na jedan od ova dva načina.",
                color = Color.Gray,
                fontSize = 12.sp,
            )
        }
    }
}

/** Tekst ispod „КРАЈ ФИСКАЛНОГ РАЧУНА“ (polje za reklamu; obavezno za Napredni ESIR). */
@Composable
private fun KarticaTekstaIspodRacuna(m: EsirModel) {
    var tekst by remember(m.podesavanja.tekstIspodRacuna) { mutableStateOf(m.podesavanja.tekstIspodRacuna.orEmpty()) }
    var greska by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tekst ispod računa", fontWeight = FontWeight.SemiBold)
            Text(
                "Štampa se ispod „КРАЈ ФИСКАЛНОГ РАЧУНА“ na svakom računu (polje za reklamu), npr. „Hvala na poseti!“. " +
                    "Najviše 10 redova po 40 znakova.",
                color = Color.Gray,
                fontSize = 12.sp,
            )
            OutlinedTextField(tekst, { tekst = it; greska = null }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = { greska = m.sacuvajTekstIspodRacuna(tekst) }) { Text("Sačuvaj tekst") }
            }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun KarticaKasira(m: EsirModel) {
    var novi by remember { mutableStateOf("") }
    var greska by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kasiri", fontWeight = FontWeight.SemiBold)
            Text("Ime izabranog kasira štampa se na računu („Касир“).", color = Color.Gray, fontSize = 12.sp)
            m.podesavanja.kasiri.forEach { ime ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ime, Modifier.weight(1f))
                    TextButton(onClick = { m.ukloniKasira(ime) }) { Text("Ukloni", color = CRVENA) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(novi, { novi = it; greska = null }, label = { Text("Ime kasira") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    greska = m.dodajKasira(novi)
                    if (greska == null) novi = ""
                }) { Text("Dodaj") }
            }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun KarticaLpfr(m: EsirModel) {
    var adresa by remember(m.podesavanja.lpfr) { mutableStateOf(m.podesavanja.lpfr?.baseUrl.orEmpty()) }
    var greska by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("L-PFR (lokalni PFR)", fontWeight = FontWeight.SemiBold)
            Text(
                "Adresa L-PFR-a, završava se sa /api, npr. http://192.168.1.20:7555/api. " +
                    "Adresa razvojnog L-PFR-a sa portala sadrži tajni token, zato je skrivena.",
                color = Color.Gray,
                fontSize = 12.sp,
            )
            TajnoPolje(adresa, "Adresa", { adresa = it; greska = null })
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = { greska = m.sacuvajLpfr(adresa) }) { Text("Sačuvaj L-PFR") }
            }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun KarticaVpfr(m: EsirModel) {
    val v = m.podesavanja.vpfr
    var putanja by remember(v) { mutableStateOf(v?.pfxPath) }
    var lozinka by remember(v) { mutableStateOf(v?.pfxPassword.orEmpty()) }
    var pac by remember(v) { mutableStateOf(v?.pac.orEmpty()) }
    var opis by remember(v) { mutableStateOf<String?>(null) }
    var greska by remember(v) { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("V-PFR (virtuelni PFR Poreske uprave)", fontWeight = FontWeight.SemiBold)
            Text("Sertifikat (.p12/.pfx) sa portala, lozinka i PAC. Adresa V-PFR-a se čita iz sertifikata.", color = Color.Gray, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sertifikat: ${putanja ?: "nije izabran"}", Modifier.weight(1f), fontSize = 13.sp)
                OutlinedButton(onClick = {
                    m.izaberiSertifikat()?.let { putanja = it; opis = null; greska = null }
                }) { Text("Izaberi sertifikat") }
            }
            TajnoPolje(lozinka, "Lozinka sertifikata", { lozinka = it; opis = null; greska = null })
            TajnoPolje(pac, "PAC", { pac = it; greska = null })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val r = m.opisSertifikata(putanja, lozinka)
                    opis = r.getOrNull()
                    greska = r.exceptionOrNull()?.message
                }) { Text("Proveri sertifikat") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { greska = m.sacuvajVpfr(putanja, lozinka, pac) }) { Text("Sačuvaj V-PFR") }
            }
            opis?.let { Text(it, color = ZELENA, fontSize = 13.sp) }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
        }
    }
}

/** Avansni posao: svi avansi, zbir, stanje, i radnje „Sledeći avans“ i „Konačni račun“ (konačni se pravi levo). */
@Composable
private fun AvansniPosaoPanel(m: EsirModel, posao: AvansniPosao) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Avansni posao", fontWeight = FontWeight.SemiBold)
            posao.lanac.forEach { a ->
                Row {
                    Text("${a.racun.invoiceNumber} · ${a.datum ?: ""}", Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text("${a.ukupno.zaStampu()} RSD", fontSize = 13.sp)
                }
            }
            Row {
                Text("Ukupno avansa", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${posao.ukupno.zaStampu()} RSD", fontWeight = FontWeight.SemiBold)
            }
            val zatvaranje = posao.zatvaranje
            val praviSeLevo = m.konacniPosao?.poslednji?.racun?.invoiceNumber == posao.poslednji.racun.invoiceNumber
            when {
                posao.ponisten -> Text("Poništen računom ${zatvaranje?.racun?.invoiceNumber} (9.3).", color = Color.Gray, fontSize = 13.sp)
                posao.konacni != null -> Text(
                    "Zatvoren računom ${zatvaranje?.racun?.invoiceNumber}; konačni račun ${posao.konacni?.racun?.invoiceNumber}.",
                    color = ZELENA,
                    fontSize = 13.sp,
                )
                praviSeLevo -> Text("← Konačni račun se pravi levo: dodaj stavke, pa „Zatvori avanse i izdaj konačni račun“.", color = ZELENA, fontSize = 13.sp)
                else -> {
                    if (zatvaranje != null) {
                        Text("Avansi su zatvoreni računom ${zatvaranje.racun.invoiceNumber}, a konačni račun još nije izdat.", color = CRVENA, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (posao.mozeSledeciAvans) {
                            OutlinedButton(onClick = { m.zapocniSledeciAvans(posao) }, enabled = !m.radi) { Text("Sledeći avans") }
                        }
                        Button(onClick = { m.zapocniKonacni(posao) }, enabled = !m.radi) { Text("Konačni račun") }
                    }
                }
            }
        }
    }
}

private enum class NacinUnosa(val naziv: String) { KATALOG("Iz kataloga"), RUCNO("Ručni unos"), VAUCER("Vaučer") }

/**
 * Poseban slučaj računa koji se pravi (PURS 9.4, 9.7). Uvek se izdaje Promet Prodaja; plaćanje je određeno pravilom,
 * pa se izbor plaćanja ne prikazuje.
 */
@Composable
private fun PosebanSlucajRacuna(m: EsirModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Poseban slučaj:", fontSize = 13.sp)
        Izbor(PosebanSlucaj.entries, m.posebanSlucaj, { it.naziv }, kompaktno = true) { m.posebanSlucaj = it }
    }
    when (m.posebanSlucaj) {
        PosebanSlucaj.NE -> {}
        PosebanSlucaj.BEZ_NAKNADE -> Text(
            "Promet bez naknade (PURS 9.7): stavke dobijaju „00:“ ispred naziva, plaćanje je „drugo bezgotovinsko“, " +
                "a ispod računa se štampa „*Промет без накнаде“.",
            color = Color.DarkGray,
            fontSize = 12.sp,
        )
        PosebanSlucaj.KORPORATIVNA -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(m.korporativniPib, { m.korporativniPib = it }, label = { Text("PIB firme") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(m.korporativnaKartica, { m.korporativnaKartica = it }, label = { Text("Broj kartice") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text(
                "Korporacijska kartica (PURS 9.4): plaćanje je uvek vaučer. Jednom mesečno izdaj zbirnu refundaciju (Žurnal).",
                color = Color.DarkGray,
                fontSize = 12.sp,
            )
        }
    }
}

/** Prodaja jednonamenskog vaučera (PURS 9.5): stavka „20: Једнонаменски ваучер (oznaka)“. */
@Composable
private fun UnosVaucera(m: EsirModel) {
    var iznos by remember { mutableStateOf("") }
    var oznaka by remember { mutableStateOf<String?>(null) }
    var greska by remember { mutableStateOf<String?>(null) }
    val oznake = m.stope.map { it.label }
    if (oznaka == null || oznaka !in oznake) oznaka = oznake.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(iznos, { iznos = it; greska = null }, label = { Text("Iznos vaučera") }, singleLine = true, modifier = Modifier.width(160.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Izbor(oznake, oznaka, { it }, kompaktno = true) { oznaka = it }
            }
            Button(onClick = {
                greska = m.dodajVaucer(iznos, oznaka)
                if (greska == null) iznos = ""
            }) { Text("Dodaj vaučer") }
        }
        Text(
            "Najviše jedan vaučer po poreskoj stopi na računu. Kada kupac plati vaučerom, fiskalni račun se ne izdaje (PURS 9.5).",
            color = Color.DarkGray,
            fontSize = 12.sp,
        )
        greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
    }
}

/** Zbirna mesečna refundacija korporacijskih kartica (PURS 9.4): kupac (PIB) i mesec, pa pregled pre izdavanja. */
@Composable
private fun MesecnaRefundacijaKartica(m: EsirModel) {
    var otvoreno by remember { mutableStateOf(false) }
    var pib by remember { mutableStateOf("") }
    var mesec by remember { mutableStateOf(m.ovajMesec()) }
    var greska by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { otvoreno = !otvoreno }) {
        Text("Korporativne kartice: mesečna refundacija " + if (otvoreno) "▲" else "▼")
    }
    if (!otvoreno) return
    val pregled = remember(pib, mesec, m.zurnal) { if (pib.trim().length == 9) m.pregledMesecneRefundacije(pib, mesec) else null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pib, { pib = it; greska = null }, label = { Text("PIB firme") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(mesec, { mesec = it; greska = null }, label = { Text("Mesec (gggg-mm)") }, singleLine = true, modifier = Modifier.width(170.dp))
            }
            val p = pregled?.getOrNull()
            when {
                pregled == null -> Text("Upiši PIB firme (9 cifara).", color = Color.Gray, fontSize = 13.sp)
                p == null -> Text(pregled.exceptionOrNull()?.message.orEmpty(), color = CRVENA, fontSize = 13.sp)
                else -> Text(
                    "Računa korporativnom karticom od ${p.od} do ${p.doDana}: ${p.racuni.size}, ukupno ${p.iznos.zaStampu()} RSD",
                    fontSize = 13.sp,
                )
            }
            Text("Izdaje se jedna Promet Refundacija za sve te račune, plaćanje vaučer, uz poziv na poslednji račun.", color = Color.DarkGray, fontSize = 12.sp)
            Row {
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { greska = m.izdajMesecnuRefundaciju(pib, mesec) },
                    enabled = !m.radi && m.status != null && p != null && p.racuni.isNotEmpty(),
                ) { Text("Izdaj zbirnu refundaciju") }
            }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(6.dp))
}


/** Količina za prikaz i unos: decimalni zarez, bez nepotrebnih nula (npr. „0,25“). */
private fun Decimal4.uTekst(): String = toString().replace('.', ',')

/** Kupac: šifra iz šifarnika 9.8 (PURS uputstvo) i broj dokumenta. */
@Composable
private fun UnosKupca(sifra: String, broj: String, oznakaBroja: String, naSifru: (String) -> Unit, naBroj: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Izbor(SifarnikKupca.idKupca.keys.toList(), sifra, { it }, kompaktno = true, naIzbor = naSifru)
    }
    Text("$sifra: ${SifarnikKupca.idKupca[sifra].orEmpty()}", color = Color.DarkGray, fontSize = 12.sp)
    OutlinedTextField(broj, naBroj, label = { Text(oznakaBroja) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

/** Kupac na računu koji se izdaje („na firmu“: PIB); bez broja račun nema kupca. */
@Composable
private fun KupacNaRacunu(m: EsirModel, otvoriOdmah: Boolean = false) {
    var otvoren by remember(otvoriOdmah) { mutableStateOf(otvoriOdmah) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { otvoren = !otvoren }) { Text(if (otvoren) "Kupac ▲" else "Kupac (na firmu, PIB…) ▼") }
        if (m.brojKupca.isNotBlank()) {
            Text("${m.sifraKupca}:${m.brojKupca.trim()}", fontWeight = FontWeight.Medium)
            TextButton(onClick = { m.brojKupca = "" }) { Text("Bez kupca") }
        }
    }
    if (otvoren) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UnosKupca(m.sifraKupca, m.brojKupca, "Broj (npr. PIB firme)", { m.sifraKupca = it }, { m.brojKupca = it })
            Text("Opciono polje kupca (nije obavezno):", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Izbor(SIFRE_OPCIONOG_POLJA, m.sifraOpcionog, { it }, kompaktno = true) { m.sifraOpcionog = it }
            }
            Text("${m.sifraOpcionog}: ${SifarnikKupca.opcionoPoljeKupca[m.sifraOpcionog].orEmpty()}", color = Color.DarkGray, fontSize = 12.sp)
            OutlinedTextField(m.brojOpcionog, { m.brojOpcionog = it }, label = { Text("Broj (opciono polje kupca)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    } else if (m.brojOpcionog.isNotBlank()) {
        Text("Opciono polje kupca: ${m.sifraOpcionog}:${m.brojOpcionog.trim()}", fontSize = 13.sp)
    }
}

/**
 * Plaćanje: svih 7 načina iz „Tehničkog vodiča“ (ili bez kartice, čeka i instanta, po podešavanju) i podeljeno
 * plaćanje: delovi sa iznosom, a glavni način dobija ostatak. Kod gotovine: koliko je kupac dao i kusur.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NaplataRacuna(m: EsirModel, zaNaplatu: Decimal4) {
    Text("Plaćanje", fontWeight = FontWeight.SemiBold)
    m.dodatnaPlacanja.forEachIndexed { i, deo ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Izbor(m.dozvoljeniNacini, deo.nacin, { it.naziv }, kompaktno = true) { m.promeniDeoPlacanja(i, deo.copy(nacin = it)) }
            }
            OutlinedTextField(deo.iznos, { m.promeniDeoPlacanja(i, deo.copy(iznos = it)) }, label = { Text("Iznos") }, singleLine = true, modifier = Modifier.width(120.dp))
            TextButton(onClick = { m.ukloniDeoPlacanja(i) }) { Text("Ukloni") }
        }
    }
    if (m.dodatnaPlacanja.isNotEmpty()) {
        Text("Ostatak ${m.ostatakNaplate(zaNaplatu)?.zaStampu() ?: "?"} RSD plaća se:", fontSize = 13.sp)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.dozvoljeniNacini.forEach { n ->
            val razmak = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            if (n == m.nacinPlacanja) {
                Button(onClick = { m.nacinPlacanja = n }, contentPadding = razmak, modifier = Modifier.height(34.dp)) { Text(n.naziv) }
            } else {
                OutlinedButton(onClick = { m.nacinPlacanja = n }, contentPadding = razmak, modifier = Modifier.height(34.dp)) { Text(n.naziv) }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = m::dodajDeoPlacanja) { Text("+ Podeli plaćanje (više načina)") }
        if (m.ogranicenje != OgranicenjePlacanja.NEMA) {
            Text("Kartica, ček i instant su isključeni (Podešavanja).", color = Color.Gray, fontSize = 12.sp)
        }
    }
    val gotovina = m.nacinPlacanja == NacinPlacanja.GOTOVINA || m.dodatnaPlacanja.any { it.nacin == NacinPlacanja.GOTOVINA }
    if (gotovina) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(m.primljeno, { m.primljeno = it }, label = { Text("Kupac dao") }, singleLine = true, modifier = Modifier.width(160.dp))
            m.kusurNaplate(zaNaplatu)?.let { Text("Kusur: ${it.zaStampu()} RSD", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

private enum class PanelRacuna { REFUNDACIJA, NAPLATA }

/**
 * Radnje nad prikazanim računom:
 * - Naplati: predračun → Promet Prodaja (jednom);
 * - Refundacija: cela ili deo računa, dok ima šta da se vrati (prodaja vrste Promet, Obuka, Predračun);
 * - Kopija: račun vrste Promet; potvrđuje se drugim klikom, jer se izdaje preko PFR-a.
 */
@Composable
private fun RadnjeRacuna(m: EsirModel, izdat: IzdatRacun) {
    val broj = izdat.racun.invoiceNumber
    var panel by remember(broj) { mutableStateOf<PanelRacuna?>(null) }
    var potvrdaKopije by remember(broj) { mutableStateOf(false) }
    var greska by remember(broj) { mutableStateOf<String?>(null) }
    val refundacije = remember(broj, m.zurnal) { if (izdat.mozeRefundacija) m.refundacijeZa(izdat) else emptyList() }
    val preostalo = remember(broj, m.zurnal) { if (izdat.mozeRefundacija) m.preostaloZaRefundaciju(izdat) else emptyList() }
    val zbirna = remember(broj, m.zurnal) { m.zbirnaRefundacijaZa(izdat) }
    val konacniPosleAvansa = remember(broj, m.zurnal) { m.jeKonacniPosleAvansa(izdat) }
    val imaZaRefundaciju = zbirna == null && !konacniPosleAvansa && preostalo.any { it > Decimal4.NULA }
    val predracun = izdat.zahtev.invoiceType == VrstaRacuna.PREDRACUN && izdat.zahtev.transactionType == TipTransakcije.PRODAJA
    val naplacen = remember(broj, m.zurnal) { if (predracun) m.prometIzPredracuna(izdat) else null }
    val mozeSlati = !m.radi && m.status != null
    var potvrdaPonistavanja by remember(broj) { mutableStateOf(false) }
    val posaoAvansa = remember(broj, m.zurnal) { m.avansniPosao(izdat) }
    // Avans se poništava samo dok je poslednji u poslu koji nije zatvoren.
    val mozePonistiti = izdat.mozePonistavanje && !konacniPosleAvansa &&
        (posaoAvansa == null || (posaoAvansa.mozeSledeciAvans && posaoAvansa.poslednji.racun.invoiceNumber == broj))
    if (!izdat.mozeRefundacija && !izdat.mozeKopija && !predracun && !mozePonistiti && posaoAvansa == null) return

    fun prebaci(p: PanelRacuna) {
        panel = if (panel == p) null else p
        potvrdaKopije = false
        greska = null
    }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (predracun) {
            if (naplacen != null) {
                Text("Naplaćen računom ${naplacen.racun.invoiceNumber}", color = Color.Gray, fontSize = 13.sp)
            } else {
                Button(onClick = { prebaci(PanelRacuna.NAPLATA) }, enabled = mozeSlati) { Text("Naplati") }
            }
        }
        if (imaZaRefundaciju) {
            OutlinedButton(onClick = { prebaci(PanelRacuna.REFUNDACIJA) }, enabled = mozeSlati) {
                Text(if (refundacije.isEmpty()) "Refundacija" else "Refundacija ostatka")
            }
        }
        if (izdat.mozeKopija && !potvrdaPonistavanja) {
            if (potvrdaKopije) {
                Text("Izdati kopiju?", fontSize = 13.sp)
                Button(onClick = { greska = m.izdajKopiju(izdat); potvrdaKopije = false }, enabled = mozeSlati) { Text("Da, izdaj kopiju") }
                TextButton(onClick = { potvrdaKopije = false }) { Text("Ne") }
            } else {
                OutlinedButton(onClick = { potvrdaKopije = true; panel = null; greska = null }, enabled = mozeSlati) { Text("Kopija") }
            }
        }
        // 9.3: poništava se samo račun koji još nije refundiran.
        if (mozePonistiti && refundacije.isEmpty() && zbirna == null && !potvrdaKopije) {
            if (potvrdaPonistavanja) {
                Text("Poništiti račun?", fontSize = 13.sp)
                Button(
                    onClick = { greska = m.ponisti(izdat); potvrdaPonistavanja = false },
                    enabled = mozeSlati,
                    colors = ButtonDefaults.buttonColors(containerColor = CRVENA),
                ) { Text("Da, poništi") }
                TextButton(onClick = { potvrdaPonistavanja = false }) { Text("Ne") }
            } else {
                TextButton(onClick = { potvrdaPonistavanja = true; panel = null; greska = null }, enabled = mozeSlati) {
                    Text("Poništi (greška)", color = CRVENA)
                }
            }
        }
    }
    if (potvrdaPonistavanja) {
        Text(
            "Poništavanje (PURS 9.3): izdaje se refundacija celog računa na sopstveni PIB, novac se ne vraća. " +
                "Posle toga izdaj novi, ispravan račun.",
            color = Color.DarkGray,
            fontSize = 12.sp,
        )
    }
    if (zbirna != null) {
        Text("Obuhvaćen zbirnom mesečnom refundacijom ${zbirna.racun.invoiceNumber}", color = Color.Gray, fontSize = 13.sp)
    } else if (refundacije.isNotEmpty()) {
        Text(
            (if (imaZaRefundaciju) "Delimično refundiran: " else "Refundiran: ") + refundacije.joinToString { it.racun.invoiceNumber },
            color = Color.Gray,
            fontSize = 13.sp,
        )
    }
    if (m.status == null) Text("Za izdavanje PFR mora biti dostupan (Osveži).", color = Color.Gray, fontSize = 12.sp)
    posaoAvansa?.let {
        Spacer(Modifier.height(6.dp))
        AvansniPosaoPanel(m, it)
    }
    when (panel) {
        PanelRacuna.REFUNDACIJA -> if (imaZaRefundaciju) {
            Spacer(Modifier.height(6.dp))
            PanelRefundacije(m, izdat, preostalo, prva = refundacije.isEmpty()) { panel = null }
        }
        PanelRacuna.NAPLATA -> if (naplacen == null) {
            Spacer(Modifier.height(6.dp))
            PanelNaplate(m, izdat) { panel = null }
        }
        null -> {}
    }
    greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
}

/**
 * Refundacija: stavke i količine koje se vraćaju (unapred: sve što je ostalo), način povraćaja novca
 * i ID kupca koji prima novac (obavezno, PURS 9.6).
 */
@Composable
private fun PanelRefundacije(m: EsirModel, izdat: IzdatRacun, preostalo: List<Decimal4>, prva: Boolean, zatvori: () -> Unit) {
    val ranije = izdat.zahtev.buyerId?.split(":", limit = 2)?.takeIf { it.size == 2 && it[0] in SifarnikKupca.idKupca }
    var sifra by remember { mutableStateOf(ranije?.get(0) ?: "20") }
    var broj by remember { mutableStateOf(ranije?.get(1) ?: "") }
    var greska by remember { mutableStateOf<String?>(null) }
    val kolicine = remember { mutableStateListOf(*preostalo.map { it.uTekst() }.toTypedArray()) }
    val naciniRacuna = izdat.zahtev.payment.map { it.paymentType }.distinct()
    var nacin by remember { mutableStateOf(naciniRacuna.first()) }

    val trazeno = m.kolicineIzTeksta(kolicine)
    val cela = prva && trazeno == izdat.zahtev.items.map { it.quantity }
    val iznos = m.iznosRefundacije(izdat, kolicine)
    // Kopija za potpis kupca: refundacija računa vrste Promet kada se vraća gotovina (9.6).
    val gotovina = if (cela) izdat.zahtev.payment.any { it.paymentType == NacinPlacanja.GOTOVINA } else nacin == NacinPlacanja.GOTOVINA
    val kopijaZaPotpis = izdat.mozeKopija && gotovina

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Refundacija računa ${izdat.racun.invoiceNumber}", fontWeight = FontWeight.SemiBold)
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                izdat.zahtev.items.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, maxLines = 1)
                            Text(
                                "${s.unitPrice.zaStampu()} · prodato ${s.quantity.uTekst()}, može da se vrati ${preostalo[i].uTekst()}",
                                color = Color.Gray,
                                fontSize = 12.sp,
                            )
                        }
                        OutlinedTextField(
                            kolicine[i],
                            { kolicine[i] = it; greska = null },
                            singleLine = true,
                            enabled = preostalo[i] > Decimal4.NULA,
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { preostalo.forEachIndexed { i, q -> kolicine[i] = q.uTekst() } }) { Text("Sve") }
                TextButton(onClick = { kolicine.indices.forEach { kolicine[it] = "0" } }) { Text("Ništa") }
                Spacer(Modifier.weight(1f))
                Text(iznos?.let { "Vraća se ${it.zaStampu()} RSD" } ?: "Neispravna količina", fontWeight = FontWeight.SemiBold)
            }
            if (cela) {
                Text("Novac se vraća kao na računu: " + izdat.zahtev.payment.joinToString { "${it.paymentType.naziv} ${it.amount.zaStampu()}" }, fontSize = 13.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Novac se vraća:", fontSize = 13.sp)
                    Izbor(naciniRacuna, nacin, { it.naziv }, kompaktno = true) { nacin = it }
                }
            }
            Text("Kupac koji prima novac (obavezno):", fontSize = 13.sp)
            UnosKupca(sifra, broj, "Broj", { sifra = it; greska = null }, { broj = it; greska = null })
            if (kopijaZaPotpis) {
                Text(
                    "Gotovina: odmah posle refundacije izdaje se i Kopija sa redom za potpis kupca. Kupac je potpisuje i ona ostaje u radnji.",
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = zatvori) { Text("Odustani") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        greska = m.refundiraj(izdat, sifra, broj, kolicine.toList(), if (cela) null else nacin)
                        if (greska == null) zatvori()
                    },
                    enabled = !m.radi && m.status != null,
                    colors = ButtonDefaults.buttonColors(containerColor = CRVENA),
                ) { Text("Izdaj refundaciju") }
            }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
        }
    }
}

/** Naplata po predračunu: način plaćanja i kusur; izdaje se Promet Prodaja uz poziv na predračun. */
@Composable
private fun PanelNaplate(m: EsirModel, predracun: IzdatRacun, zatvori: () -> Unit) {
    var nacin by remember { mutableStateOf(NacinPlacanja.GOTOVINA) }
    var primljeno by remember { mutableStateOf("") }
    var greska by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Naplata po predračunu ${predracun.racun.invoiceNumber}", fontWeight = FontWeight.SemiBold)
            Text(
                "Za naplatu ${predracun.ukupno.zaStampu()} RSD, stavki: ${predracun.zahtev.items.size}" +
                    (predracun.zahtev.buyerId?.let { ", kupac $it" } ?: ""),
                fontSize = 13.sp,
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Izbor(m.dozvoljeniNacini, nacin, { it.naziv }, kompaktno = true) { nacin = it; greska = null }
            }
            if (nacin == NacinPlacanja.GOTOVINA) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(primljeno, { primljeno = it; greska = null }, label = { Text("Kupac dao") }, singleLine = true, modifier = Modifier.width(160.dp))
                    m.kusurZaPredracun(predracun, primljeno)?.let { Text("Kusur: ${it.zaStampu()} RSD", fontWeight = FontWeight.SemiBold) }
                }
            }
            Text("Izdaje se Promet Prodaja sa istim stavkama, uz poziv na predračun.", color = Color.DarkGray, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = zatvori) { Text("Odustani") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        greska = m.naplatiPredracun(predracun, nacin, primljeno)
                        if (greska == null) zatvori()
                    },
                    enabled = !m.radi && m.status != null,
                ) { Text("Izdaj Promet Prodaju") }
            }
            greska?.let { Text(it, color = CRVENA, fontSize = 13.sp) }
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
