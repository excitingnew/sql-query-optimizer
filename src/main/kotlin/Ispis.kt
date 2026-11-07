private const val GRANE_NASTAVAK = "|-- "
private const val GRANE_POSLEDNJI = "+-- "
private const val VERTIKALNO = "|   "
private const val PRAZNO = "    "

// Pretvara celo stablo plana u tekst i na kraju dodaje ukupnu cenu
fun formatirajPlan(
    plan: CvorPlana,
    saMaterializacijom: Boolean = true,
    strogo: Boolean = false,
    detaljno: Boolean = false,
): String {
    val redovi = mutableListOf<String>()
    ispisiCvor(
        plan, "", jeRoot = true, jePoslednji = true, redovi, saMaterializacijom,
        roditeljJeProjekcija = false, strogo, detaljno,
    )
    redovi.add("")
    val ukupno = if (saMaterializacijom) plan.kumulativnaCenaSaMaterializacijom(strogo = strogo) else plan.kumulativnaCena()
    val oznaka = when {
        !saMaterializacijom -> "pipeline"
        strogo -> "strogo materijalizovano"
        else -> "materijalizovano"
    }
    redovi.add("UKUPNO: ${formatirajBroj(ukupno)} blok transfera ($oznaka)")
    return redovi.joinToString("\n")
}

// Rekurzivno ispisuje jedan cvor, njegove detalje i svu njegovu decu
private fun ispisiCvor(
    cvor: CvorPlana,
    prefiks: String,
    jeRoot: Boolean,
    jePoslednji: Boolean,
    redovi: MutableList<String>,
    saMaterializacijom: Boolean,
    roditeljJeProjekcija: Boolean,
    strogo: Boolean,
    detaljno: Boolean,
) {
    val zaglavlje: String
    val pomakDece: String
    if (jeRoot) {
        zaglavlje = ""
        pomakDece = ""
    } else {
        val spojnica = if (jePoslednji) GRANE_POSLEDNJI else GRANE_NASTAVAK
        zaglavlje = prefiks + spojnica
        pomakDece = prefiks + (if (jePoslednji) PRAZNO else VERTIKALNO)
    }
    redovi.add(zaglavlje + zaglavljeCvora(cvor, saMaterializacijom, roditeljJeProjekcija, strogo))
    for (red in detaljiCvora(cvor, saMaterializacijom, roditeljJeProjekcija, strogo, detaljno)) {
        redovi.add(pomakDece + red)
    }
    val poslednji = cvor.deca.size - 1
    val jeProjekcija = cvor.operacija == "Projekcija"
    for ((i, dete) in cvor.deca.withIndex()) {
        redovi.add(pomakDece + "------------------------------")
        ispisiCvor(
            dete, pomakDece, jeRoot = false, jePoslednji = i == poslednji,
            redovi, saMaterializacijom, jeProjekcija, strogo, detaljno,
        )
    }
}

// Pravi prvi red cvora sa operacijom, algoritmom, cenom i velicinom izlaza
private fun zaglavljeCvora(cvor: CvorPlana, saMaterializacijom: Boolean, roditeljJeProjekcija: Boolean, strogo: Boolean): String {
    val sastav = if (cvor.algoritam.isNotEmpty()) "${cvor.operacija} [${cvor.algoritam}]" else cvor.operacija
    val cenaOp = formatirajBroj(cvor.cenaOperacije())
    val cenaMat = cvor.cenaMaterializacije(roditeljJeProjekcija, strogo)
    val redova = cvor.izlazRedova()
    val blokova = cvor.izlazBlokova()
    val cenaTekst = if (saMaterializacijom && cenaMat > 0.0) {
        val ukupno = formatirajBroj(cvor.sopstvenaCenaSaMaterializacijom(roditeljJeProjekcija, strogo))
        "cena $ukupno = $cenaOp op + ${formatirajBroj(cenaMat)} mat"
    } else {
        "cena $cenaOp"
    }
    return "$sastav    ($cenaTekst, izlaz $redova redova / $blokova blokova)"
}

// Pravi dodatne redove cvora sa kandidatima, formulama, uslovima i indeksima
private fun detaljiCvora(
    cvor: CvorPlana,
    saMaterializacijom: Boolean,
    roditeljJeProjekcija: Boolean,
    strogo: Boolean,
    detaljno: Boolean,
): List<String> {
    val linije = mutableListOf<String>()
    val d = cvor.detalji
    if (detaljno) {
        @Suppress("UNCHECKED_CAST")
        val kandidati = d["razmatraniKandidati"] as? List<KandidatPlana>
        if (!kandidati.isNullOrEmpty()) {
            linije.add("  razmatrani kandidati (kumulativna cena podplana):")
            for (kandidat in kandidati) {
                val oznaka = if (kandidat.izabran) "[IZABRAN]" else "         "
                linije.add("  $oznaka ${kandidat.opis}: ${formatirajBroj(kandidat.cena)}")
            }
            linije.add("  razlog izbora: najmanja procenjena kumulativna cena")
        }
    }
    cvor.procena.obracun?.let { obracun ->
        linije.add("  pravilo: ${obracun.pravilo}")
        linije.add("  formula: ${obracun.formula}")
        linije.add("  racun: ${obracun.racun}")
    }
    cvor.procena.obracunVelicine?.let { obracun ->
        linije.add("  procena izlaza: ${obracun.pravilo}")
        linije.add("  formula izlaza: ${obracun.formula}")
        linije.add("  racun izlaza: ${obracun.racun}")
    }
    val cenaMat = cvor.cenaMaterializacije(roditeljJeProjekcija, strogo)
    if (saMaterializacijom && cenaMat > 0.0) {
        linije.add("  materijalizacija: cena operacije + broj izlaznih blokova")
        linije.add(
            "  racun sa materijalizacijom: ${formatirajBroj(cvor.cenaOperacije())} + " +
                "${formatirajBroj(cenaMat)} = " +
                formatirajBroj(cvor.sopstvenaCenaSaMaterializacijom(roditeljJeProjekcija, strogo))
        )
    }
    val tabela = d["tabela"] as? String
    if (tabela != null) {
        val alijas = d["alijas"] as? String
        if (alijas != null && alijas != tabela) {
            linije.add("  tabela: $tabela (alijas $alijas)")
        } else {
            linije.add("  tabela: $tabela")
        }
    }
    @Suppress("UNCHECKED_CAST")
    val ulazneTabele = d["ulazneTabele"] as? List<String>
    if (!ulazneTabele.isNullOrEmpty()) {
        linije.add("  ulazne tabele: ${ulazneTabele.joinToString(", ")} (citanje ukljuceno u cenu spajanja)")
    }
    val indeks = d["indeks"] as? String
    if (indeks != null) linije.add("  indeks: $indeks")
    @Suppress("UNCHECKED_CAST")
    val vecSortiraniUlazi = d["vecSortiraniUlazi"] as? List<String>
    if (!vecSortiraniUlazi.isNullOrEmpty()) {
        linije.add("  vec sortirani ulazi: ${vecSortiraniUlazi.joinToString(", ")} (nije potrebno dodatno sortiranje)")
    }
    @Suppress("UNCHECKED_CAST")
    val indeksi = d["indeksi"] as? List<String>
    if (!indeksi.isNullOrEmpty()) linije.add("  indeksi: ${indeksi.joinToString(", ")}")
    val vodeci = d["vodeciUslov"] as? Uslov
    if (vodeci != null) linije.add("  vodeci uslov: ${formatirajUslov(vodeci)}")

    @Suppress("UNCHECKED_CAST")
    val kompozitni = d["kompozitniUslovi"] as? List<Uslov>
    if (!kompozitni.isNullOrEmpty()) {
        linije.add("  kljuc pretrage: ${kompozitni.joinToString(", ") { formatirajUslov(it) }}")
    }

    @Suppress("UNCHECKED_CAST")
    val filteri = d["filteri"] as? List<Uslov>
    if (!filteri.isNullOrEmpty()) {
        linije.add("  filteri: ${filteri.joinToString(", ") { formatirajUslov(it) }}")
    }

    @Suppress("UNCHECKED_CAST")
    val rezidualni = d["rezidualniUslovi"] as? List<Uslov>
    if (!rezidualni.isNullOrEmpty()) {
        linije.add("  rezidualni filteri (u memoriji): ${rezidualni.joinToString(", ") { formatirajUslov(it) }}")
    }

    @Suppress("UNCHECKED_CAST")
    val usloviSp = d["usloviSpajanja"] as? List<Uslov>
    if (!usloviSp.isNullOrEmpty()) {
        linije.add("  uslov spajanja: ${usloviSp.joinToString(", ") { formatirajUslov(it) }}")
    }

    @Suppress("UNCHECKED_CAST")
    val orGrupe = d["orGrupe"] as? List<OrGrupa>
    if (!orGrupe.isNullOrEmpty()) {
        for (grupa in orGrupe) {
            val tekst = grupa.uslovi.joinToString(" OR ") { formatirajUslov(it) }
            linije.add("  OR filter (u memoriji): ($tekst)")
        }
    }
    val orGrupaIndeksa = d["orGrupaIndeksa"] as? OrGrupa
    if (orGrupaIndeksa != null) {
        val tekst = orGrupaIndeksa.uslovi.joinToString(" OR ") { formatirajUslov(it) }
        linije.add("  OR uslovi preko indeksa: ($tekst)")
    }

    @Suppress("UNCHECKED_CAST")
    val atributi = d["atributi"] as? List<String>
    if (atributi != null) linije.add("  atributi: ${atributi.joinToString(", ")}")

    val atributRef = d["atribut"] as? ReferencaAtributa
    if (atributRef != null) {
        val smer = d["smer"] as? String ?: ""
        val sufiks = if (smer.isNotEmpty()) " $smer" else ""
        linije.add("  po: $atributRef$sufiks")
    }
    if (d["zaSortiranje"] == true) {
        val sortiranPo = d["sortiranPo"] as? ReferencaAtributa
        if (sortiranPo != null) {
            linije.add("  napomena: vec sortirano po klasterovanom indeksu ($sortiranPo)")
        }
    }
    return linije
}

// Pretvara interni objekat Uslov u citljiv SQL oblik
private fun formatirajUslov(uslov: Uslov): String {
    val desniDeo = when (val d = uslov.desna) {
        is ReferencaAtributa -> d.toString()
        is Vrednost.Tekst -> "'${d.v}'"
        is Vrednost.Celi -> d.v.toString()
        is Vrednost.Decimalni -> d.v.toString()
        else -> d.toString()
    }
    return "${uslov.leva} ${uslov.operator.simbol} $desniDeo"
}

// Ispisuje ceo broj bez decimala, a ostale brojeve sa dve decimale
private fun formatirajBroj(vrednost: Double): String {
    val celo = vrednost.toInt()
    return if (vrednost == celo.toDouble()) celo.toString()
    else String.format(java.util.Locale.US, "%.2f", vrednost)
}
