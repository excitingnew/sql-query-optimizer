import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.floor

const val CENA_HES_PRISTUPA = 1.2
const val SELEKTIVNOST_OPSEGA = 1.0 / 2.0

data class ProcenaOperacije(
    val cena: Double,
    val brojRedova: Int,
    val brojBlokova: Int,
    val obracun: ObracunCene? = null,
    val redovaPoBloku: Int = max(1, ceilInt(brojRedova, brojBlokova)),
    val obracunVelicine: ObracunVelicine? = null,
)

data class ObracunVelicine(
    val pravilo: String,
    val formula: String,
    val racun: String,
)

data class ObracunCene(
    val pravilo: String,
    val formula: String,
    val racun: String,
)

// Procenuje cenu citanja cele tabele linearnim skeniranjem : cena A1 = B
fun fullScan(tabela: Tabela): ProcenaOperacije = ProcenaOperacije(
    cena = tabela.brojBlokova.toDouble(),
    brojRedova = tabela.brojRedova,
    brojBlokova = tabela.brojBlokova,
    obracun = ObracunCene(
        pravilo = "A1 - linearno citanje cele tabele",
        formula = "B (broj blokova tabele)",
        racun = "${tabela.brojBlokova} = ${tabela.brojBlokova}",
    ),
    redovaPoBloku = tabela.redovaPoBloku,
)

// Procenuje koliko spojenih redova staje u blok na osnovu sirine oba ulaza
// fRez = floor((fLevo * fDesno) / (fLevo + fDesno))
fun redovaPoBlokuPosleSpajanja(levo: Int, desno: Int): Int =
    max(1, floor(levo.toDouble() * desno / (levo + desno)).toInt())

// Pravi tekst formule i racuna za velicinu rezultata spajanja
fun obracunVelicineSpajanja(
    spoljnja: ProcenaOperacije,
    unutrasnja: ProcenaOperacije,
    rezultatRedova: Int,
    redovaPoBloku: Int,
): ObracunVelicine {
    // BRez = ceil(NRez / fRez)
    val blokova = max(1, ceilInt(rezultatRedova, redovaPoBloku))
    return ObracunVelicine(
        pravilo = "Spojeni red sadrzi atribute oba ulaza, pa je njegova duzina Llevo + Ldesno",
        formula = "fRez = floor((fLevo * fDesno) / (fLevo + fDesno)); BRez = ceil(NRez / fRez)",
        racun = "fRez = floor((${spoljnja.redovaPoBloku} * ${unutrasnja.redovaPoBloku}) / " +
            "(${spoljnja.redovaPoBloku} + ${unutrasnja.redovaPoBloku})) = $redovaPoBloku; " +
            "BRez = ceil($rezultatRedova / $redovaPoBloku) = $blokova",
    )
}

// Procenuje broj redova koji zadovoljavaju jedan uslov selekcije
fun procenaBrojaPodudaranja(uslov: Uslov, atribut: Atribut, brojRedovaTabele: Int): Int {
    if (uslov.operator == TipPoredjenja.JEDNAKO) {      //Nrez = ceil(N / V(A))
        return max(1, ceilInt(brojRedovaTabele, atribut.razlicitihVrednosti))
    }
    if (uslov.operator == TipPoredjenja.RAZLICITO) {    // Nrez = N - ceil(N / V(A))
        return max(1, brojRedovaTabele - ceilInt(brojRedovaTabele, atribut.razlicitihVrednosti))
    }
    val selektivnost = selektivnostOpsega(uslov, atribut)     // Nrez = ceil(N * selektivnost)
    return max(1, ceil(brojRedovaTabele * selektivnost).toInt())
}

// Procenuje selektivnost uslova opsega pomocu min i max statistika.
private fun selektivnostOpsega(uslov: Uslov, atribut: Atribut): Double {
    val minV = atribut.minVrednost ?: return SELEKTIVNOST_OPSEGA
    val maxV = atribut.maxVrednost ?: return SELEKTIVNOST_OPSEGA
    val desnaBroj = when (val d = uslov.desna) {
        is Vrednost.Celi -> d.v.toDouble()
        is Vrednost.Decimalni -> d.v
        else -> return SELEKTIVNOST_OPSEGA
    }
    if (maxV <= minV) return SELEKTIVNOST_OPSEGA
    val diskretno = atribut.tip == TipAtributa.INT
    // velicina domena = max - min (+ 1 za celobrojni domen)
    val velicinaDomena = if (diskretno) (maxV - minV + 1) else (maxV - minV)
    val pomeraj = if (diskretno) 1.0 else 0.0
    val brojilac = when (uslov.operator) {
        TipPoredjenja.MANJE -> desnaBroj - minV
        TipPoredjenja.MANJE_JEDNAKO -> desnaBroj - minV + pomeraj
        TipPoredjenja.VECE -> maxV - desnaBroj
        TipPoredjenja.VECE_JEDNAKO -> maxV - desnaBroj + pomeraj
        else -> return SELEKTIVNOST_OPSEGA
    }
    // selektivnost = pokriveni deo domena / velicina domena
    return (brojilac / velicinaDomena).coerceIn(0.0, 1.0)
}

// Procenuje cenu i izlaz selekcije koja koristi B+ ili hes indeks
fun filterPrekoIndeksa(
    indeks: Indeks,
    brojPodudaranja: Int,
    redovaPoBloku: Int,
    operator: TipPoredjenja? = null,
): ProcenaOperacije {
    val cena = cenaPristupaIndeksu(indeks, brojPodudaranja, redovaPoBloku, operator)
    //  Brez = ceil(Nrez / f)
    val brojBlokova = max(1, ceilInt(brojPodudaranja, redovaPoBloku))
    val citaOdPocetka = indeks.klasterovan && indeks.jeBStablo() &&
        (operator == TipPoredjenja.MANJE || operator == TipPoredjenja.MANJE_JEDNAKO)
    // select b za klasterovani, odnosno n za neklasterovani indeks
    val cenaCitanja = if (indeks.klasterovan) brojBlokova.toDouble() else brojPodudaranja.toDouble()
    val obracun = when {
        citaOdPocetka -> ObracunCene(
            pravilo = "A5 - uslov < ili <=: sekvencijalno citanje od pocetka",
            formula = "b (broj blokova od pocetka do granice opsega)",
            racun = "${formatBrojFormule(cenaCitanja)} = ${formatBrojFormule(cena)}",
        )
        indeks.jeHes() -> ObracunCene(
            pravilo = "Hash indeks - direktan pristup baketu i citanje pogodaka",
            formula = "cHash + ${if (indeks.klasterovan) "b (blokovi rezultata)" else "n (redovi rezultata)"}",
            racun = "${formatBrojFormule(CENA_HES_PRISTUPA)} + ${formatBrojFormule(cenaCitanja)} = ${formatBrojFormule(cena)}",
        )
        else -> {
            val visina = indeks.visinaStabla ?: 0
            val oznaka = if (indeks.klasterovan) "b" else "n"
            val opis = if (operator == TipPoredjenja.VECE || operator == TipPoredjenja.VECE_JEDNAKO) {
                "A5 - uslov > ili >=: B+ stablo nalazi pocetak opsega"
            } else {
                "B+ indeks - prolazak kroz stablo i citanje pogodaka"
            }
            ObracunCene(
                pravilo = opis,
                formula = "h (visina stabla) + $oznaka (${if (indeks.klasterovan) "blokovi" else "redovi"} rezultata)",
                racun = "$visina + ${formatBrojFormule(cenaCitanja)} = ${formatBrojFormule(cena)}",
            )
        }
    }
    return ProcenaOperacije(cena, brojPodudaranja, brojBlokova, obracun, redovaPoBloku)
}

// Procenuje cenu i izlaz selekcije linearnim skeniranjem tabele
fun filterPrekoSkena(
    tabela: Tabela,
    ocekivanoRedova: Int,
    ranoZaustavljanje: Boolean = false,
): ProcenaOperacije {
    // Brez = ceil(Nrez / f)
    val ocekivanoBlokova = max(1, ceilInt(ocekivanoRedova, tabela.redovaPoBloku))
    // cena A1 = B, a za jednakost po kljucu prosecno B / 2
    val cena = if (ranoZaustavljanje) tabela.brojBlokova / 2.0 else tabela.brojBlokova.toDouble()
    val obracun = if (ranoZaustavljanje) {
        ObracunCene(
            pravilo = "A1 - jednakost po jedinstvenom atributu: prosecno rano zaustavljanje",
            formula = "B / 2 (B je broj blokova tabele)",
            racun = "${tabela.brojBlokova} / 2 = ${formatBrojFormule(cena)}",
        )
    } else {
        ObracunCene(
            pravilo = "A1 - linearno citanje cele tabele",
            formula = "B (broj blokova tabele)",
            racun = "${tabela.brojBlokova} = ${formatBrojFormule(cena)}",
        )
    }
    return ProcenaOperacije(
        cena = cena,
        brojRedova = ocekivanoRedova,
        brojBlokova = ocekivanoBlokova,
        obracun = obracun,
        redovaPoBloku = tabela.redovaPoBloku,
    )
}

// Sabira cenu pristupa indeksima i citanja rezultata za OR uslove
fun cenaUnijeIndeksa(
    indeksi: List<Indeks>,
    brojRezultata: Int,
    redovaPoBloku: Int,
): Double {
    // zbir cena pristupa svim indeksima
    val cenaPokazivaca = indeksi.sumOf { indeks ->
        if (indeks.jeHes()) {
            CENA_HES_PRISTUPA
        } else {
            indeks.visinaStabla?.toDouble()
                ?: throw IllegalStateException("B+ indeksu '${indeks.naziv}' nedostaje visina stabla")
        }
    }
    // b ako su svi klasterovani, inace n
    val cenaCitanja = if (indeksi.all { it.klasterovan }) {
        max(1, ceilInt(brojRezultata, redovaPoBloku)).toDouble()
    } else {
        brojRezultata.toDouble()
    }
    // cena = cena pokazivaca + cena citanja rezultata
    return cenaPokazivaca + cenaCitanja
}

// Procenuje cenu sortiranja u memoriji ili spoljnim sort-merge algoritmom
fun eksternoSortiranje(ulaz: ProcenaOperacije, velicinaBafera: Int): ProcenaOperacije {
    if (ulaz.brojBlokova <= velicinaBafera) {
        //ako je B <= M, cena sortiranja u memoriji je 0
        return ProcenaOperacije(
            0.0, ulaz.brojRedova, ulaz.brojBlokova,
            ObracunCene(
                pravilo = "Sortiranje u memoriji - ulaz staje u bafer",
            formula = "B <= M (ulazni blokovi staju u bafer)",
                racun = "${ulaz.brojBlokova} <= $velicinaBafera, cena = 0",
            ),
            redovaPoBloku = ulaz.redovaPoBloku,
        )
    }
    // ceil(B / M)
    val pocetneRune = ceilInt(ulaz.brojBlokova, velicinaBafera)
    // p = ceil(log_(M-1)(broj pocetnih run-ova))
    val brojObjedinjavanja = max(
        1,
        ceil(ln(pocetneRune.toDouble()) / ln((velicinaBafera - 1).toDouble())).toInt()
    )
    // B * (2 * p + 1)
    val cena = ulaz.brojBlokova.toDouble() * (2 * brojObjedinjavanja + 1)
    return ProcenaOperacije(
        cena, ulaz.brojRedova, ulaz.brojBlokova,
        ObracunCene(
            pravilo = "Spoljno sortiranje - kreiranje run-ova i objedinjavanje",
            formula = "B * (2 * p + 1), B = ulazni blokovi, p = prolazi objedinjavanja",
            racun = "${ulaz.brojBlokova} * (2 * $brojObjedinjavanja + 1) = ${formatBrojFormule(cena)}",
        ),
        redovaPoBloku = ulaz.redovaPoBloku,
    )
}

// Procenuje cenu spajanja obicnom ugnjezdenom petljom po redovima
fun ugnjezdenoSpajanje(
    spoljnja: ProcenaOperacije,
    unutrasnja: ProcenaOperacije,
    rezultatRedova: Int,
    redovaPoBloku: Int,
): ProcenaOperacije {
    // NL = Bsp + Nsp * Bun
    val cena = spoljnja.brojBlokova.toDouble() + spoljnja.brojRedova * unutrasnja.brojBlokova
    return procenaRezultata(
        cena, rezultatRedova, redovaPoBloku,
        ObracunCene(
            "NL spajanje - ugnjezdena petlja po redovima",
            "Bsp + Nsp * Bun (blokovi spoljne + redovi spoljne * blokovi unutrasnje)",
            "${spoljnja.brojBlokova} + ${spoljnja.brojRedova} * ${unutrasnja.brojBlokova} = ${formatBrojFormule(cena)}",
        ),
        obracunVelicineSpajanja(spoljnja, unutrasnja, rezultatRedova, redovaPoBloku),
    )
}

// Procenuje cenu blokovskog ugnjezdenog spajanja koristeci M-2 bafera
fun blokovskoUgnjezdenoSpajanje(
    spoljnja: ProcenaOperacije,
    unutrasnja: ProcenaOperacije,
    velicinaBafera: Int,
    rezultatRedova: Int,
    redovaPoBloku: Int,
): ProcenaOperacije {
    // ceil(Bsp / (M - 2))
    val raspolozivo = max(1, velicinaBafera - 2)
    val prolaza = ceilInt(spoljnja.brojBlokova, raspolozivo)
    //  BNL = Bsp + broj prolaza * Bun
    val cena = spoljnja.brojBlokova.toDouble() + unutrasnja.brojBlokova * prolaza
    return procenaRezultata(
        cena, rezultatRedova, redovaPoBloku,
        ObracunCene(
            "BNL spajanje - blokovi spoljne relacije koriste M-2 bafera",
            "Bsp + ceil(Bsp / (M-2)) * Bun (blokovsko ugnjezdenje)",
            "${spoljnja.brojBlokova} + $prolaza * ${unutrasnja.brojBlokova} = ${formatBrojFormule(cena)}",
        ),
        obracunVelicineSpajanja(spoljnja, unutrasnja, rezultatRedova, redovaPoBloku),
    )
}

// Procenuje cenu INL spajanja sa indeksnim pristupom unutrasnjoj tabeli
fun indeksnoUgnjezdenoSpajanje(
    spoljnja: ProcenaOperacije,
    indeksUnutrasnje: Indeks,
    brojPodudaranjaPoUlazu: Int,
    redovaPoBloku: Int,
    rezultatRedova: Int,
    redovaPoBlokuRezultata: Int = redovaPoBloku,
): ProcenaOperacije {
    val cenaPoUlazu = cenaPristupaIndeksu(indeksUnutrasnje, brojPodudaranjaPoUlazu, redovaPoBloku)
    // INL = Bsp + Nsp * cIndeks
    val cena = spoljnja.brojBlokova + spoljnja.brojRedova * cenaPoUlazu
    return procenaRezultata(
        cena, rezultatRedova, redovaPoBlokuRezultata,
        ObracunCene(
            "INL spajanje - indeksna pretraga za svaki red spoljne relacije",
            "Bsp + Nsp * cIndeks (citanje spoljne relacije i indeksna pretraga za svaki njen red)",
            "${spoljnja.brojBlokova} + ${spoljnja.brojRedova} * ${formatBrojFormule(cenaPoUlazu)} = ${formatBrojFormule(cena)}",
        ),
        obracunVelicineSpajanja(
            spoljnja,
            ProcenaOperacije(0.0, 1, 1, redovaPoBloku = redovaPoBloku),
            rezultatRedova,
            redovaPoBlokuRezultata,
        ),
    )
}

// Procenuje cenu merge join-a i potrebnog sortiranja oba ulaza
fun objedinjenoSpajanje(
    spoljnja: ProcenaOperacije,
    unutrasnja: ProcenaOperacije,
    velicinaBafera: Int,
    spoljnjaSortiranja: Boolean,
    unutrasnjaSortiranja: Boolean,
    rezultatRedova: Int,
    redovaPoBloku: Int,
): ProcenaOperacije {
    //Bsp + Bun + sort(spoljna) + sort(unutrasnja)
    var cena = spoljnja.brojBlokova.toDouble() + unutrasnja.brojBlokova
    if (!spoljnjaSortiranja) cena += eksternoSortiranje(spoljnja, velicinaBafera).cena
    if (!unutrasnjaSortiranja) cena += eksternoSortiranje(unutrasnja, velicinaBafera).cena
    return procenaRezultata(
        cena, rezultatRedova, redovaPoBloku,
        ObracunCene(
            "Merge join - citanje obe relacije i potrebna sortiranja",
            "Bsp + Bun + sort(sp) + sort(un) (citanje i potrebna sortiranja)",
            "${spoljnja.brojBlokova} + ${unutrasnja.brojBlokova}" +
                " + ${if (spoljnjaSortiranja) 0 else formatBrojFormule(eksternoSortiranje(spoljnja, velicinaBafera).cena)}" +
                " + ${if (unutrasnjaSortiranja) 0 else formatBrojFormule(eksternoSortiranje(unutrasnja, velicinaBafera).cena)}" +
                " = ${formatBrojFormule(cena)}",
        ),
        obracunVelicineSpajanja(spoljnja, unutrasnja, rezultatRedova, redovaPoBloku),
    )
}

// Procenuje cenu hes spajanja prema tome koliko ulazi staju u bafer
fun hesSpajanje(
    spoljnja: ProcenaOperacije,
    unutrasnja: ProcenaOperacije,
    velicinaBafera: Int,
    rezultatRedova: Int,
    redovaPoBloku: Int,
): ProcenaOperacije {
    val sortirano = listOf(spoljnja, unutrasnja).sortedBy { it.brojBlokova }
    val manji = sortirano[0]
    val veci = sortirano[1]

    val ukupno = manji.brojBlokova + veci.brojBlokova       // Bsp + Bun
    val cena = when {
        //ako oba ulaza staju u memoriju, cena samog spajanja je 0
        ukupno <= velicinaBafera -> 0.0
        // ako manji ulaz staje u bafer, cena = Bsp + Bun
        manji.brojBlokova <= velicinaBafera - 1 -> ukupno.toDouble()
        else -> {
            val osnova = max(2, velicinaBafera - 1)
            val odnos = manji.brojBlokova.toDouble() / velicinaBafera
            // p = ceil(log_(M-1)(Bmanji / M))
            val brojParticionisanja = max(1, ceil(ln(odnos) / ln(osnova.toDouble())).toInt())
            //rekurzivni hash = 2 * (Bsp + Bun) * p + Bsp + Bun
            2.0 * ukupno * brojParticionisanja + ukupno
        }
    }
    val obracun = when {
        ukupno <= velicinaBafera -> ObracunCene(
            "Hash join - obe relacije staju u bafer", "0", "0 = 0"
        )
        manji.brojBlokova <= velicinaBafera - 1 -> ObracunCene(
            "Hash join - manja relacija staje u bafer", "Bsp + Bun (blokovi obe relacije)",
            "${manji.brojBlokova} + ${veci.brojBlokova} = ${formatBrojFormule(cena)}",
        )
        else -> {
            val osnova = max(2, velicinaBafera - 1)
            val odnos = manji.brojBlokova.toDouble() / velicinaBafera
            val brojParticionisanja = max(1, ceil(ln(odnos) / ln(osnova.toDouble())).toInt())
            ObracunCene(
                "Hash join - rekurzivno particionisanje obe relacije",
                "2 * (Bsp + Bun) * p + Bsp + Bun, p = broj nivoa particionisanja",
                "2 * $ukupno * $brojParticionisanja + $ukupno = ${formatBrojFormule(cena)}",
            )
        }
    }
    return procenaRezultata(
        cena, rezultatRedova, redovaPoBloku, obracun,
        obracunVelicineSpajanja(spoljnja, unutrasnja, rezultatRedova, redovaPoBloku),
    )
}

// Procenuje broj redova ekvi-spajanja pomocu broja razlicitih vrednosti
fun procenaBrojaRezultataSpajanja(
    levaProcena: ProcenaOperacije,
    desnaProcena: ProcenaOperacije,
    razlicitihVrednostiUManjojStrani: Int,
): Int = max(
    1,
    //  Nrez = ceil(Nlevo * Ndesno / V)
    ceil(levaProcena.brojRedova.toDouble() * desnaProcena.brojRedova / razlicitihVrednostiUManjojStrani).toInt()
)

// Racuna samo cenu pronalazenja i citanja pogodaka preko jednog indeksa
private fun cenaPristupaIndeksu(
    indeks: Indeks,
    brojPodudaranja: Int,
    redovaPoBloku: Int,
    operator: TipPoredjenja? = null,
): Double {
    val citaOdPocetka = indeks.klasterovan &&
        !indeks.jeHes() &&
        (operator == TipPoredjenja.MANJE || operator == TipPoredjenja.MANJE_JEDNAKO)
    val cenaPristupa = if (citaOdPocetka) {
        // <= ili <: cena dolaska preko stabla je 0
        0.0
    } else if (indeks.jeHes()) {
        //  cena dolaska do hes indeksa = 1.2
        CENA_HES_PRISTUPA
    } else {
        val visina = indeks.visinaStabla
            ?: throw IllegalStateException("B+ indeksu '${indeks.naziv}' nedostaje visina stabla")
        // cena dolaska do lista B+ stabla = h
        visina.toDouble()
    }
    // b za klasterovani, odnosno n za neklasterovani indeks
    val cenaCitanja = if (indeks.klasterovan) {
        max(1, ceilInt(brojPodudaranja, redovaPoBloku)).toDouble()
    } else {
        brojPodudaranja.toDouble()
    }
    // indeks = cena pristupa + cena citanja pogodaka
    return cenaPristupa + cenaCitanja
}

// Pravi zajednicku procenu operacije i racuna broj izlaznih blokova
private fun procenaRezultata(
    cena: Double,
    brojRedova: Int,
    redovaPoBloku: Int,
    obracun: ObracunCene? = null,
    obracunVelicine: ObracunVelicine? = null,
): ProcenaOperacije =
    ProcenaOperacije(
        cena = cena,
        brojRedova = brojRedova,
        // Brez = ceil(Nrez / f)
        brojBlokova = max(1, ceilInt(brojRedova, redovaPoBloku)),
        obracun = obracun,
        redovaPoBloku = redovaPoBloku,
        obracunVelicine = obracunVelicine,
    )

// Deli dva cela broja i zaokruzuje rezultat navise.
internal fun ceilInt(brojilac: Int, imenilac: Int): Int =
    ceil(brojilac.toDouble() / imenilac).toInt()

// Formatira broj za citljiv prikaz u racunu formule
private fun formatBrojFormule(vrednost: Double): String {
    val ceo = vrednost.toInt()
    return if (vrednost == ceo.toDouble()) ceo.toString()
    else String.format(java.util.Locale.US, "%.2f", vrednost)
}
