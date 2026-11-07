import kotlin.math.max

private data class IzvorUKontekstu(val alijas: String, val tabela: Tabela)

class KontekstUpita(val upit: Upit, val sema: Sema) {
    private val izvori: Map<String, IzvorUKontekstu>

    init {
        izvori = upit.izvori.associate { izvor ->
            val alijas = izvor.vidljivoIme()
            alijas to IzvorUKontekstu(alijas, sema.dohvatiTabelu(izvor.naziv))
        }
    }

    // Vraca tabelu koja u upitu ima zadati alijas
    fun tabela(alijas: String): Tabela = izvori.getValue(alijas).tabela

    // Vraca skup svih alijasa tabela navedenih u FROM klauzuli
    fun aliasiSvih(): Set<String> = izvori.keys.toSet()

    // Pronalazi tabelu kojoj pripada referenca atributa i proverava dvosmislenost
    fun alijasReference(ref: ReferencaAtributa): String {
        if (ref.izvor != null) {
            val tabelaIzKonteksta = izvori[ref.izvor]
                ?: run {
                    val poznati = if (izvori.isEmpty()) "(prazno)" else izvori.keys.sorted().joinToString(", ")
                    throw NoSuchElementException(
                        "Referenca '$ref' koristi alijas '${ref.izvor}' " +
                            "koji nije u FROM klauzuli. Definisani: $poznati."
                    )
                }
            if (!tabelaIzKonteksta.tabela.imaAtribut(ref.atribut)) {
                throw NoSuchElementException(
                    "Tabela '${tabelaIzKonteksta.tabela.naziv}' (alijas '${ref.izvor}') " +
                        "nema atribut '${ref.atribut}'."
                )
            }
            return ref.izvor
        }
        val pogoci = izvori.filter { it.value.tabela.imaAtribut(ref.atribut) }.keys
        if (pogoci.isEmpty()) {
            val poznati = if (izvori.isEmpty()) "(prazno)" else izvori.keys.sorted().joinToString(", ")
            throw NoSuchElementException(
                "Atribut '${ref.atribut}' nije pronadjen ni u jednoj tabeli iz FROM-a " +
                    "($poznati). Koristi prefiks 'alijas.atribut' za pojasnjenje."
            )
        }
        if (pogoci.size > 1) {
            throw NoSuchElementException(
                "Atribut '${ref.atribut}' je dvosmislen: postoji u alijasima " +
                    "${pogoci.sorted()}. Koristi prefiks 'alijas.atribut'."
            )
        }
        return pogoci.first()
    }

    // Vraca podatke o atributu na osnovu reference iz upita
    fun atribut(ref: ReferencaAtributa): Atribut =
        tabela(alijasReference(ref)).dohvatiAtribut(ref.atribut)

    // Proverava da sve reference iz upita postoje i da OR grupa ne mesa tabele
    fun validirajReference(upit: Upit) {
        for (ref in upit.projekcija) alijasReference(ref)
        for (uslov in upit.uslovi) {
            alijasReference(uslov.leva)
            val desna = uslov.desna
            if (desna is ReferencaAtributa) alijasReference(desna)
        }
        for (grupa in upit.orGrupe) {
            for (uslov in grupa.uslovi) {
                alijasReference(uslov.leva)
                val desna = uslov.desna
                if (desna is ReferencaAtributa) alijasReference(desna)
            }
            val alijasi = grupa.uslovi.map { alijasReference(it.leva) }.toSet()
            if (alijasi.size > 1) {
                throw NoSuchElementException(
                    "OR grupa (${grupa.uslovi.joinToString(" OR ") { "${it.leva} ${it.operator.simbol} ${it.desna}" }}) " +
                        "mesa vise tabela (${alijasi.sorted()}). Podrzano je samo OR " +
                        "unutar iste tabele."
                )
            }
        }
    }

}

class Planer(private val upit: Upit, private val sema: Sema) {
    private val M = sema.velicinaBafera
    private val kontekst: KontekstUpita
    private val kes: MutableMap<Set<String>, CvorPlana> = mutableMapOf()

    init {
        require(upit.izvori.isNotEmpty()) { "Upit mora imati bar jednu tabelu u FROM klauzuli" }
        kontekst = KontekstUpita(upit, sema)
        kontekst.validirajReference(upit)
    }

    // Pravi najbolji plan, dodaje ORDER BY i na kraju Projekciju
    fun planiraj(): CvorPlana {
        var koren = najboljiPlan(kontekst.aliasiSvih())
        upit.sortiranje?.let { koren = mozdaSortiraj(koren) }
        koren = dodajProjekciju(koren)
        return koren
    }

    // vraca najjeftiniji plan za skup tabela, cuva u kesu
    private fun najboljiPlan(alijasi: Set<String>): CvorPlana {
        kes[alijasi]?.let { return it }
        val plan = if (alijasi.size == 1) {
            najboljiPristup(alijasi.first())
        } else {
            najboljeSpajanje(alijasi)
        }
        kes[alijasi] = plan
        return plan
    }

    // Poredi skeniranje i dostupne indekse za pristup jednoj tabeli
    private fun najboljiPristup(alijas: String): CvorPlana {
        val tabela = kontekst.tabela(alijas)
        val filteri = filteriZaAlijas(alijas)
        val orGrupe = orGrupeZaAlijas(alijas)
        val andSelektivnost = proizvodSelektivnosti(filteri, tabela)
        val orSelektivnost = orGrupe.map { selektivnostOrGrupe(it, tabela) }.fold(1.0) { a, b -> a * b }
        val izlaznihRedova = max(1, ceil(tabela.brojRedova * andSelektivnost * orSelektivnost))

        val kandidati = mutableListOf<CvorPlana>()
        kandidati.add(planSkena(alijas, tabela, filteri, orGrupe, izlaznihRedova))
        if (orGrupe.size == 1) {
            planA10(alijas, tabela, filteri, orGrupe.first(), izlaznihRedova)?.let {
                kandidati.add(it)
            }
        }

        val vidjeniHesIndeksi = mutableSetOf<String>()
        for (vodeci in filteri) {
            val atributPolje = tabela.dohvatiAtribut(vodeci.leva.atribut)
            for (indeks in tabela.indeksiSaPrefiksom(vodeci.leva.atribut)) {
                if (indeks.jeHes()) {
                    if (indeks.naziv in vidjeniHesIndeksi) continue
                    val pokriveni = pokrijHashIndeks(indeks, filteri)
                        ?: continue // HASH mora imati sve atribute pokrivene jednakoscu (A8)
                    vidjeniHesIndeksi.add(indeks.naziv)
                    kandidati.add(
                        planKompozitnogHashPristupa(alijas, tabela, indeks, pokriveni, filteri, orGrupe, izlaznihRedova)
                    )
                } else {
                    kandidati.add(
                        planIndeksnogPristupa(alijas, tabela, vodeci, atributPolje, indeks, filteri, orGrupe, izlaznihRedova)
                    )
                }
            }
        }
        val izabrani = kandidati.minBy { it.kumulativnaCenaSaMaterializacijom() }
        val razmatrani = kandidati.map { kandidat ->
            KandidatPlana(
                opis = opisKandidata(kandidat),
                cena = kandidat.kumulativnaCenaSaMaterializacijom(),
                izabran = kandidat === izabrani,
            )
        }.sortedWith(compareByDescending<KandidatPlana> { it.izabran }.thenBy { it.cena })
        return izabrani.saDodatnimDetaljima("razmatraniKandidati" to razmatrani)
    }

    // Pravi kandidat koji OR uslove izvrsava unijom rezultata vise indeksa
    private fun planA10(
        alijas: String,
        tabela: Tabela,
        filteri: List<Uslov>,
        grupa: OrGrupa,
        izlaznihRedova: Int,
    ): CvorPlana? {
        val indeksi = mutableListOf<Indeks>()
        for (uslov in grupa.uslovi) {
            val kandidati = tabela.indeksiSaPrefiksom(uslov.leva.atribut).filter { indeks ->
                indeks.jeBStablo() ||
                    (indeks.jeHes() && indeks.atributi.size == 1 && uslov.operator == TipPoredjenja.JEDNAKO)
            }
            if (kandidati.isEmpty()) return null
            indeksi.add(kandidati.minBy { indeks ->
                if (indeks.jeHes()) CENA_HES_PRISTUPA else indeks.visinaStabla?.toDouble() ?: Double.MAX_VALUE
            })
        }
        val cena = cenaUnijeIndeksa(indeksi, izlaznihRedova, tabela.redovaPoBloku)
        val procena = ProcenaOperacije(
            cena = cena,
            brojRedova = izlaznihRedova,
            brojBlokova = max(1, ceilInt(izlaznihRedova, tabela.redovaPoBloku)),
            obracun = ObracunCene(
                pravilo = "A10 - OR uslovi: unija pokazivaca iz svih indeksa",
                formula = "broj indeksa * cena pristupa + citanje jedinstvenih rezultata",
                racun = "${indeksi.size} * $CENA_HES_PRISTUPA + $izlaznihRedova = $cena",
            ),
            redovaPoBloku = tabela.redovaPoBloku,
        )
        return CvorPlana(
            operacija = "Pretraga indeksa",
            algoritam = "A10 - unija indeksa",
            procena = procena,
            detalji = mapOf(
                "tabela" to tabela.naziv,
                "alijas" to alijas,
                "indeksi" to indeksi.map { it.naziv },
                "orGrupaIndeksa" to grupa,
                "rezidualniUslovi" to filteri,
            ),
        )
    }

    // Proverava da li su svi atributi kompozitnog hes indeksa zadati jednakoscu
    private fun pokrijHashIndeks(indeks: Indeks, filteri: List<Uslov>): List<Uslov>? {
        val pokriveni = mutableListOf<Uslov>()
        for (atr in indeks.atributi) {
            val odgovarajuci = filteri.firstOrNull { u ->
                u.leva.atribut == atr &&
                    u.operator == TipPoredjenja.JEDNAKO &&
                    u !in pokriveni
            } ?: return null
            pokriveni.add(odgovarajuci)
        }
        return pokriveni
    }

    // Pravi kandidat za linearno skeniranje i primenu filtera nad tabelom
    private fun planSkena(
        alijas: String,
        tabela: Tabela,
        filteri: List<Uslov>,
        orGrupe: List<OrGrupa>,
        izlaznihRedova: Int,
    ): CvorPlana {
        val imaJedinstvenuJednakost = filteri.any { uslov ->
            uslov.operator == TipPoredjenja.JEDNAKO &&
                tabela.dohvatiAtribut(uslov.leva.atribut).jedinstven &&
                uslov.desna !is ReferencaAtributa
        }
        val procena = filterPrekoSkena(tabela, izlaznihRedova, imaJedinstvenuJednakost)
        val detalji = mutableMapOf<String, Any>(
            "tabela" to tabela.naziv,
            "alijas" to alijas,
            "filteri" to filteri,
        )
        if (orGrupe.isNotEmpty()) detalji["orGrupe"] = orGrupe
        return CvorPlana(
            operacija = "Skeniranje tabele",
            algoritam = "A1 - linearno",
            procena = procena,
            detalji = detalji,
        )
    }

    // Pravi kandidat za pristup preko kompozitnog hes indeksa
    private fun planKompozitnogHashPristupa(
        alijas: String,
        tabela: Tabela,
        indeks: Indeks,
        pokriveni: List<Uslov>,
        sviFilteri: List<Uslov>,
        orGrupe: List<OrGrupa>,
        konacnoRedova: Int,
    ): CvorPlana {
        var pokrivenaSel = 1.0
        for (u in pokriveni) {
            val atr = tabela.dohvatiAtribut(u.leva.atribut)
            val podud = procenaBrojaPodudaranja(u, atr, tabela.brojRedova)
            pokrivenaSel *= podud.toDouble() / tabela.brojRedova
        }
        val redovaPosleIndeksa = max(1, ceil(tabela.brojRedova * pokrivenaSel))
        val procenaIndeksa = filterPrekoIndeksa(indeks, redovaPosleIndeksa, tabela.redovaPoBloku)
        val konacnaProcena = ProcenaOperacije(
            cena = procenaIndeksa.cena,
            brojRedova = konacnoRedova,
            brojBlokova = max(1, ceilInt(konacnoRedova, tabela.redovaPoBloku)),
            obracun = procenaIndeksa.obracun,
            redovaPoBloku = tabela.redovaPoBloku,
        )
        val detalji = mutableMapOf<String, Any>(
            "tabela" to tabela.naziv,
            "alijas" to alijas,
            "indeks" to indeks.naziv,
            "kompozitniUslovi" to pokriveni.toList(),
            "rezidualniUslovi" to sviFilteri.filter { it !in pokriveni },
        )
        if (orGrupe.isNotEmpty()) detalji["orGrupe"] = orGrupe
        return CvorPlana(
            operacija = "Pretraga indeksa",
            algoritam = opisIndeksa(indeks),
            procena = konacnaProcena,
            detalji = detalji,
        )
    }

    // Pravi kandidat za pristup preko jednog B+ ili jednostavnog hes indeksa
    private fun planIndeksnogPristupa(
        alijas: String,
        tabela: Tabela,
        vodeci: Uslov,
        atributPolje: Atribut,
        indeks: Indeks,
        sviFilteri: List<Uslov>,
        orGrupe: List<OrGrupa>,
        konacnoRedova: Int,
    ): CvorPlana {
        val brojPodudaranja = procenaBrojaPodudaranja(vodeci, atributPolje, tabela.brojRedova)
        val procenaIndeksa = filterPrekoIndeksa(
            indeks,
            brojPodudaranja,
            tabela.redovaPoBloku,
            vodeci.operator,
        )
        val konacnaProcena = ProcenaOperacije(
            cena = procenaIndeksa.cena,
            brojRedova = konacnoRedova,
            brojBlokova = max(1, ceilInt(konacnoRedova, tabela.redovaPoBloku)),
            obracun = procenaIndeksa.obracun,
            redovaPoBloku = tabela.redovaPoBloku,
        )
        val detalji = mutableMapOf<String, Any>(
            "tabela" to tabela.naziv,
            "alijas" to alijas,
            "indeks" to indeks.naziv,
            "vodeciUslov" to vodeci,
            "rezidualniUslovi" to sviFilteri.filter { it !== vodeci },
        )
        if (orGrupe.isNotEmpty()) detalji["orGrupe"] = orGrupe
        return CvorPlana(
            operacija = "Pretraga indeksa",
            algoritam = opisIndeksa(indeks),
            procena = konacnaProcena,
            detalji = detalji,
        )
    }

    // Poredi sve podele, redoslede i algoritme spajanja za zadate tabele
    private fun najboljeSpajanje(alijasi: Set<String>): CvorPlana {
        val kandidati = mutableListOf<CvorPlana>()
        for (desniAlijas in alijasi) {
            val leviAlijasi = alijasi - desniAlijas
            val leviPlan = najboljiPlan(leviAlijasi)
            val uslovi = joinUsloviIzmedju(leviAlijasi, desniAlijas)
            val desniPlan = najboljiPristup(desniAlijas)
            for (konstruktor in kandidatiSpajanja()) {
                konstruktor(leviPlan, desniPlan, desniAlijas, uslovi)?.let { kandidat ->
                    val levaStrana = leviAlijasi.sorted().joinToString("+")
                    kandidati.add(kandidat.saDodatnimDetaljima("podelaKandidata" to "$levaStrana -> $desniAlijas"))
                }
            }
        }
        if (kandidati.isEmpty()) {
            throw IllegalStateException("Ne mogu napraviti plan spajanja za ${alijasi.sorted()}")
        }
        val izabrani = kandidati.minBy { it.kumulativnaCenaSaMaterializacijom() }
        val razmatrani = kandidati.map { kandidat ->
            val podela = kandidat.detalji["podelaKandidata"] as? String
            KandidatPlana(
                opis = listOfNotNull(podela, opisKandidata(kandidat)).joinToString(" | "),
                cena = kandidat.kumulativnaCenaSaMaterializacijom(),
                izabran = kandidat === izabrani,
            )
        }.sortedWith(compareByDescending<KandidatPlana> { it.izabran }.thenBy { it.cena })
        return izabrani.saDodatnimDetaljima("razmatraniKandidati" to razmatrani)
    }

    // Vraca konstruktore svih podrzanih algoritama spajanja
    private fun kandidatiSpajanja(): List<(CvorPlana, CvorPlana, String, List<Uslov>) -> CvorPlana?> = listOf(
        ::spojiUMemorijiAkoMozes,
        ::spojiHesSpajanjem,
        ::spojiBnl,
        ::spojiNl,
        ::spojiObjedinjenim,
        ::spojiIndeksiranim,
    )

    // Pravi kratak naziv kandidata za detaljni ispis
    private fun opisKandidata(kandidat: CvorPlana): String =
        if (kandidat.algoritam.isEmpty()) kandidat.operacija
        else "${kandidat.operacija} [${kandidat.algoritam}]"

    // Pravi spajanje u memoriji kada oba ulaza staju u bafer
    private fun spojiUMemorijiAkoMozes(
        leviPlan: CvorPlana, desniPlan: CvorPlana, desniAlijas: String, uslovi: List<Uslov>,
    ): CvorPlana? {
        if (leviPlan.izlazBlokova() + desniPlan.izlazBlokova() > M) return null
        if (uslovi.isEmpty()) return null
        val izlaznihRedova = procenaIzlazaSpajanja(leviPlan, desniPlan, desniAlijas, uslovi)
        val procena = hesSpajanje(
            spoljnja = leviPlan.procena,
            unutrasnja = desniPlan.procena,
            velicinaBafera = M,
            rezultatRedova = izlaznihRedova,
            redovaPoBloku = redovaPoBlokuPosleSpajanja(leviPlan.procena.redovaPoBloku, desniPlan.procena.redovaPoBloku),
        )
        return cvorSpajanja("Spajanje u memoriji", "", leviPlan, desniPlan, procena, uslovi)
    }

    // Pravi kandidat za hes spajanje kada postoji uslov spajanja
    private fun spojiHesSpajanjem(
        leviPlan: CvorPlana, desniPlan: CvorPlana, desniAlijas: String, uslovi: List<Uslov>,
    ): CvorPlana? {
        if (uslovi.isEmpty()) return null
        val izlaznihRedova = procenaIzlazaSpajanja(leviPlan, desniPlan, desniAlijas, uslovi)
        val procena = hesSpajanje(
            spoljnja = leviPlan.procena,
            unutrasnja = desniPlan.procena,
            velicinaBafera = M,
            rezultatRedova = izlaznihRedova,
            redovaPoBloku = redovaPoBlokuPosleSpajanja(leviPlan.procena.redovaPoBloku, desniPlan.procena.redovaPoBloku),
        )
        return cvorSpajanja(
            "Hes spajanje", "hash join", leviPlan, desniPlan, procena, uslovi,
            // Kada je cena 0, ulazi samo staju u memoriju i njihova skeniranja i dalje moraju da se plate
            apsorbujNefiltriranaSkeniranja = procena.cena > 0.0,
        )
    }

    // Pravi kandidat za blokovsko ugnjezdeno spajanje
    private fun spojiBnl(
        leviPlan: CvorPlana, desniPlan: CvorPlana, desniAlijas: String, uslovi: List<Uslov>,
    ): CvorPlana {
        val izlaznihRedova = procenaIzlazaSpajanja(leviPlan, desniPlan, desniAlijas, uslovi)
        val procena = blokovskoUgnjezdenoSpajanje(
            spoljnja = leviPlan.procena,
            unutrasnja = desniPlan.procena,
            velicinaBafera = M,
            rezultatRedova = izlaznihRedova,
            redovaPoBloku = redovaPoBlokuPosleSpajanja(leviPlan.procena.redovaPoBloku, desniPlan.procena.redovaPoBloku),
        )
        return cvorSpajanja(
            "Blok ugnjezdeno spajanje", "BNL", leviPlan, desniPlan, procena, uslovi,
            apsorbujNefiltriranaSkeniranja = true,
        )
    }

    // Pravi kandidat za obicno ugnjezdeno spajanje
    private fun spojiNl(
        leviPlan: CvorPlana, desniPlan: CvorPlana, desniAlijas: String, uslovi: List<Uslov>,
    ): CvorPlana {
        val izlaznihRedova = procenaIzlazaSpajanja(leviPlan, desniPlan, desniAlijas, uslovi)
        val procena = ugnjezdenoSpajanje(
            spoljnja = leviPlan.procena,
            unutrasnja = desniPlan.procena,
            rezultatRedova = izlaznihRedova,
            redovaPoBloku = redovaPoBlokuPosleSpajanja(leviPlan.procena.redovaPoBloku, desniPlan.procena.redovaPoBloku),
        )
        return cvorSpajanja(
            "Ugnjezdeno spajanje", "NL", leviPlan, desniPlan, procena, uslovi,
            apsorbujNefiltriranaSkeniranja = true,
        )
    }

    // Pravi merge join kandidat i proverava koji ulazi su vec sortirani
    private fun spojiObjedinjenim(
        leviPlan: CvorPlana, desniPlan: CvorPlana, desniAlijas: String, uslovi: List<Uslov>,
    ): CvorPlana? {
        if (uslovi.isEmpty()) return null
        val (leviAtribut, desniAtribut) = orijentisi(uslovi.first(), desniAlijas)
        if (leviAtribut == null || desniAtribut == null) return null
        val leviVecSortiran = planVecSortiran(leviPlan, leviAtribut)
        val desniVecSortiran = planVecSortiran(desniPlan, desniAtribut)
        val izlaznihRedova = procenaIzlazaSpajanja(leviPlan, desniPlan, desniAlijas, uslovi)
        val procena = objedinjenoSpajanje(
            spoljnja = leviPlan.procena,
            unutrasnja = desniPlan.procena,
            velicinaBafera = M,
            spoljnjaSortiranja = leviVecSortiran,
            unutrasnjaSortiranja = desniVecSortiran,
            rezultatRedova = izlaznihRedova,
            redovaPoBloku = redovaPoBlokuPosleSpajanja(leviPlan.procena.redovaPoBloku, desniPlan.procena.redovaPoBloku),
        )
        val sortiraniUlazi = buildList {
            if (leviVecSortiran) add(leviAtribut.toString())
            if (desniVecSortiran) add(desniAtribut.toString())
        }
        return cvorSpajanja(
            "Objedinjeno spajanje", "sort-merge", leviPlan, desniPlan, procena, uslovi,
            dodatniDetalji = mapOf("vecSortiraniUlazi" to sortiraniUlazi),
            apsorbujNefiltriranaSkeniranja = true,
        )
    }

    // Trazi odgovarajuci indeks unutrasnje tabele i pravi najjeftiniji INL kandidat
    private fun spojiIndeksiranim(
        leviPlan: CvorPlana, @Suppress("UNUSED_PARAMETER") desniPlan: CvorPlana,
        desniAlijas: String, uslovi: List<Uslov>,
    ): CvorPlana? {
        if (uslovi.isEmpty()) return null
        val desnaTabela = kontekst.tabela(desniAlijas)
        val desniFilteri = filteriZaAlijas(desniAlijas)
        val desnaSelektivnost = proizvodSelektivnosti(desniFilteri, desnaTabela)
        var najjeftinije: CvorPlana? = null
        for (uslov in uslovi) {
            val (leva, desna) = orijentisi(uslov, desniAlijas)
            if (leva == null || desna == null) continue
            for (indeks in desnaTabela.indeksiSaPrefiksom(desna.atribut)) {
                if (indeks.jeHes()) {
                    if (uslov.operator != TipPoredjenja.JEDNAKO) continue
                    // INL preko HASH indeksa sa vise atributa nije podrzan jer ostali atributi kljuca nisu poznati u vreme lookupa
                    if (indeks.atributi.size > 1) continue
                }
                val kandidat = napraviInlCvor(
                    leviPlan, desniAlijas, desnaTabela, indeks, desna, uslovi, desniFilteri, desnaSelektivnost,
                )
                if (najjeftinije == null || kandidat.kumulativnaCenaSaMaterializacijom() < najjeftinije.kumulativnaCenaSaMaterializacijom()) {
                    najjeftinije = kandidat
                }
            }
        }
        return najjeftinije
    }

    // Pravi konkretan INL cvor za izabrani indeks i procenjuje njegov izlaz
    private fun napraviInlCvor(
        leviPlan: CvorPlana,
        desniAlijas: String,
        desnaTabela: Tabela,
        indeks: Indeks,
        desnaRef: ReferencaAtributa,
        uslovi: List<Uslov>,
        desniFilteri: List<Uslov>,
        desnaSelektivnost: Double,
    ): CvorPlana {
        val brojPodudaranjaPoUlazu = max(
            1, ceilInt(desnaTabela.brojRedova, kontekst.atribut(desnaRef).razlicitihVrednosti)
        )
        val izlaznihRedova = max(
            1, ceil(leviPlan.izlazRedova() * brojPodudaranjaPoUlazu * desnaSelektivnost)
        )
        val procena = indeksnoUgnjezdenoSpajanje(
            spoljnja = leviPlan.procena,
            indeksUnutrasnje = indeks,
            brojPodudaranjaPoUlazu = brojPodudaranjaPoUlazu,
            redovaPoBloku = desnaTabela.redovaPoBloku,
            rezultatRedova = izlaznihRedova,
            redovaPoBlokuRezultata = redovaPoBlokuPosleSpajanja(
                leviPlan.procena.redovaPoBloku,
                desnaTabela.redovaPoBloku,
            ),
        )
        val unutrasnjiCvor = CvorPlana(
            operacija = "Pristup po indeksu (inner)",
            algoritam = opisIndeksa(indeks),
            procena = ProcenaOperacije(
                cena = 0.0,
                brojRedova = desnaTabela.brojRedova,
                brojBlokova = desnaTabela.brojBlokova,
            ),
            detalji = mapOf(
                "tabela" to desnaTabela.naziv,
                "alijas" to desniAlijas,
                "indeks" to indeks.naziv,
                "rezidualniUslovi" to desniFilteri,
            ),
        )
        val algoritam = "INL preko ${opisIndeksa(indeks)}"
        return cvorSpajanja(
            "Indeksno ugnjezdeno spajanje", algoritam, leviPlan, unutrasnjiCvor, procena, uslovi,
            dodatniDetalji = mapOf("indeks" to indeks.naziv),
            apsorbujNefiltriranaSkeniranja = true,
        )
    }

    // Sastavlja cvor spajanja i uklanja skeniranja vec ukljucena u cenu algoritma
    private fun cvorSpajanja(
        operacija: String, algoritam: String,
        leviPlan: CvorPlana, desniPlan: CvorPlana,
        procena: ProcenaOperacije, uslovi: List<Uslov>,
        dodatniDetalji: Map<String, Any> = emptyMap(),
        apsorbujNefiltriranaSkeniranja: Boolean = false,
    ): CvorPlana {
        val detalji = mutableMapOf<String, Any>("usloviSpajanja" to uslovi.toList())
        detalji.putAll(dodatniDetalji)
        val ulazi = listOf(leviPlan, desniPlan)
        val apsorbovani = if (apsorbujNefiltriranaSkeniranja) {
            ulazi.filter { jeNefiltriranoSkeniranje(it) }
        } else {
            emptyList()
        }
        if (apsorbovani.isNotEmpty()) {
            detalji["ulazneTabele"] = apsorbovani.mapNotNull { it.detalji["tabela"] as? String }
        }
        return CvorPlana(
            operacija = operacija,
            algoritam = algoritam,
            deca = ulazi.filterNot { it in apsorbovani },
            procena = procena,
            detalji = detalji,
        )
    }

    //ORDER BY sortiranje se dodaje samo ako plan vec nije sortiran po atributu
    private fun mozdaSortiraj(plan: CvorPlana): CvorPlana {
        val sort = upit.sortiranje ?: return plan
        if (planVecSortiran(plan, sort.atribut)) {
            return plan.saDodatnimDetaljima("zaSortiranje" to true, "sortiranPo" to sort.atribut)
        }
        val procena = eksternoSortiranje(plan.procena, M)
        val direktnoNadTabelom = jeNefiltriranoSkeniranje(plan)
        val detalji = mutableMapOf<String, Any>(
            "atribut" to sort.atribut,
            "smer" to sort.smer.oznaka,
        )
        if (direktnoNadTabelom) {
            detalji["tabela"] = plan.detalji.getValue("tabela")
            plan.detalji["alijas"]?.let { detalji["alijas"] = it }
            detalji["direktnoNadTabelom"] = true
        }
        return CvorPlana(
            operacija = "Sortiranje",
            algoritam = "spoljno sortiranje",
            deca = if (direktnoNadTabelom) emptyList() else listOf(plan),
            procena = procena,
            detalji = detalji,
        )
    }

    // Proverava da li je plan obicno skeniranje cele tabele bez filtera
    private fun jeNefiltriranoSkeniranje(plan: CvorPlana): Boolean {
        if (plan.operacija != "Skeniranje tabele" && plan.operacija != "Linearni skena") return false
        @Suppress("UNCHECKED_CAST")
        val filteri = plan.detalji["filteri"] as? List<Uslov>
        @Suppress("UNCHECKED_CAST")
        val orGrupe = plan.detalji["orGrupe"] as? List<OrGrupa>
        return filteri.isNullOrEmpty() && orGrupe.isNullOrEmpty()
    }

    // Proverava da li plan cuva redosled klasterovanog B+ indeksa
    private fun planVecSortiran(plan: CvorPlana, sortAtribut: ReferencaAtributa): Boolean {
        // pretraga indeksa preko klasterovanog indeksa (redovi izlaze sortirani po vodecim atributima indeksa)
        if (plan.operacija == "Pretraga indeksa") {
            val indeksNaziv = plan.detalji["indeks"] as? String
            if (indeksNaziv != null) {
                val tabela = kontekst.tabela(plan.detalji["alijas"] as String)
                val indeks = tabela.indeksi.firstOrNull { it.naziv == indeksNaziv }
                if (indeks != null && indeks.klasterovan && indeks.jePrefiksOd(sortAtribut.atribut)) {
                    return true
                }
            }
        }
        // Skeniranje cuva redosled tabele uredjene klasterovanim B+ indeksom
        if (plan.operacija == "Skeniranje tabele" || plan.operacija == "Linearni skena") {
            val alijas = plan.detalji["alijas"] as? String ?: return false
            val tabela = kontekst.tabela(alijas)
            for (indeks in tabela.indeksi) {
                if (indeks.klasterovan && !indeks.jeHes() && indeks.jePrefiksOd(sortAtribut.atribut)) {
                    return true
                }
            }
        }
        return false
    }

    // Dodaje zavrsnu Projekciju iznad izabranog plana
    private fun dodajProjekciju(plan: CvorPlana): CvorPlana {
        val procena = ProcenaOperacije(
            cena = 0.0,
            brojRedova = plan.izlazRedova(),
            brojBlokova = plan.izlazBlokova(),
            redovaPoBloku = plan.procena.redovaPoBloku,
        )
        val atributi = upit.projekcija.map { it.toString() }
        return CvorPlana(
            operacija = "Projekcija",
            algoritam = "",
            deca = listOf(plan),
            procena = procena,
            detalji = mapOf("atributi" to atributi),
        )
    }

    // Vraca sve proste filtere koji pripadaju jednoj tabeli
    private fun filteriZaAlijas(alijas: String): List<Uslov> =
        upit.uslovZaFiltere().filter { kontekst.alijasReference(it.leva) == alijas }

    // Vraca OR grupe koje u potpunosti pripadaju jednoj tabeli
    private fun orGrupeZaAlijas(alijas: String): List<OrGrupa> =
        upit.orGrupe.filter { grupa ->
            grupa.uslovi.all { kontekst.alijasReference(it.leva) == alijas }
        }

    // sel(OR) = 1 - proizvod(1 - sel svakog uslova).
    private fun selektivnostOrGrupe(grupa: OrGrupa, tabela: Tabela): Double {
        var neuspeh = 1.0
        for (u in grupa.uslovi) {
            val atr = tabela.dohvatiAtribut(u.leva.atribut)
            val s = when (u.operator) {
                TipPoredjenja.JEDNAKO -> 1.0 / atr.razlicitihVrednosti
                TipPoredjenja.RAZLICITO -> 1.0 - 1.0 / atr.razlicitihVrednosti
                else -> procenaBrojaPodudaranja(u, atr, tabela.brojRedova).toDouble() / tabela.brojRedova
            }
            neuspeh *= (1.0 - s)
        }
        return 1.0 - neuspeh
    }

    // Pronalazi uslove spajanja izmedju levog podplana i desne tabele
    private fun joinUsloviIzmedju(leviAlijasi: Set<String>, desniAlijas: String): List<Uslov> {
        val rezultat = mutableListOf<Uslov>()
        for (uslov in upit.uslovZaSpajanja()) {
            val desnaRef = uslov.desna as ReferencaAtributa
            val leva = kontekst.alijasReference(uslov.leva)
            val desna = kontekst.alijasReference(desnaRef)
            when {
                leva in leviAlijasi && desna == desniAlijas -> rezultat.add(uslov)
                desna in leviAlijasi && leva == desniAlijas -> rezultat.add(
                    Uslov(leva = desnaRef, operator = uslov.operator, desna = uslov.leva)
                )
            }
        }
        return rezultat
    }

    // Okrece join uslov tako da prvi atribut pripada levom, a drugi desnom ulazu
    private fun orijentisi(uslov: Uslov, desniAlijas: String): Pair<ReferencaAtributa?, ReferencaAtributa?> {
        val desnaRef = uslov.desna as? ReferencaAtributa ?: return null to null
        val leva = kontekst.alijasReference(uslov.leva)
        val desna = kontekst.alijasReference(desnaRef)
        if (desna == desniAlijas) return uslov.leva to desnaRef
        if (leva == desniAlijas) return desnaRef to uslov.leva
        return null to null
    }

    // Procenuje broj redova spajanja ili Dekartovog proizvoda
    private fun procenaIzlazaSpajanja(
        leviPlan: CvorPlana, desniPlan: CvorPlana, desniAlijas: String, uslovi: List<Uslov>,
    ): Int {
        if (uslovi.isEmpty()) {
            return max(1, leviPlan.izlazRedova() * desniPlan.izlazRedova())
        }
        val uslov = uslovi[0]
        val leviAtribut = kontekst.atribut(uslov.leva)
        val desniRef = uslov.desna as ReferencaAtributa
        val desniAtribut = kontekst.atribut(desniRef)
        val v = max(leviAtribut.razlicitihVrednosti, desniAtribut.razlicitihVrednosti)
        return procenaBrojaRezultataSpajanja(leviPlan.procena, desniPlan.procena, v)
    }

    // sel(AND) = proizvod selektivnosti svih filtera
    private fun proizvodSelektivnosti(filteri: List<Uslov>, tabela: Tabela): Double {
        var proizvod = 1.0
        for (f in filteri) {
            val atributPolje = tabela.dohvatiAtribut(f.leva.atribut)
            val podudaranja = procenaBrojaPodudaranja(f, atributPolje, tabela.brojRedova)
            proizvod *= podudaranja.toDouble() / tabela.brojRedova
        }
        return proizvod
    }

    // Pravi citljiv opis tipa i klasterovanosti indeksa
    private fun opisIndeksa(indeks: Indeks): String {
        val vrsta = if (indeks.jeHes()) "Hash" else "B+ stablo"
        val klaster = if (indeks.klasterovan) "klasterovan" else "neklasterovan"
        return "$vrsta ($klaster)"
    }
}

// Zaokruzuje decimalni broj navise i vraca ceo broj
private fun ceil(x: Double): Int = kotlin.math.ceil(x).toInt()
