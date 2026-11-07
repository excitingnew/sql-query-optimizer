data class KandidatPlana(
    val opis: String,
    val cena: Double,
    val izabran: Boolean = false,
)

data class CvorPlana(
    val operacija: String,
    val algoritam: String,
    val procena: ProcenaOperacije,
    val deca: List<CvorPlana> = emptyList(),
    val detalji: Map<String, Any> = emptyMap(),
) {
    // Vraca cenu algoritma ovog cvora bez materijalizacije i cena dece
    fun cenaOperacije(): Double = procena.cena

    // Odredjuje da li se izlaz cvora upisuje na disk i vraca cenu tog upisa
    fun cenaMaterializacije(
        roditeljJeProjekcija: Boolean = false,
        strogo: Boolean = false,
    ): Double {
        if (operacija == "Projekcija") return 0.0
        if (operacija == "Pristup po indeksu (inner)") return 0.0
        if (!strogo && roditeljJeProjekcija) {
            if (operacija == "Skeniranje tabele" || operacija == "Linearni skena") return 0.0
            if (operacija == "Pretraga indeksa") return 0.0
            if (operacija == "Sortiranje") return 0.0
            if (operacija.endsWith("spajanje")) return 0.0
        }
        return procena.brojBlokova.toDouble()
    }

    // Vraca samo operacionu cenu ovog cvora
    fun sopstvenaCena(): Double = cenaOperacije()

    // Formula: sopstvena cena = cena operacije + cena materijalizacije
    fun sopstvenaCenaSaMaterializacijom(
        roditeljJeProjekcija: Boolean = false,
        strogo: Boolean = false,
    ): Double = cenaOperacije() + cenaMaterializacije(roditeljJeProjekcija, strogo)

    // Vraca procenjeni broj redova na izlazu cvora
    fun izlazRedova(): Int = procena.brojRedova

    // Vraca procenjeni broj blokova na izlazu cvora
    fun izlazBlokova(): Int = procena.brojBlokova

    // Pravi kopiju cvora sa dodatim podacima koji se koriste u ispisu
    fun saDodatnimDetaljima(vararg parovi: Pair<String, Any>): CvorPlana {
        val prosireni = detalji.toMutableMap()
        for ((k, v) in parovi) prosireni[k] = v
        return copy(detalji = prosireni)
    }

    // Formula:sopstvena cena + zbir kumulativnih cena dece
    fun kumulativnaCena(): Double = sopstvenaCena() + deca.sumOf { it.kumulativnaCena() }

    // Sabira cenu celog podstabla uz odgovarajuca pravila materijalizacije
    fun kumulativnaCenaSaMaterializacijom(
        roditeljJeProjekcija: Boolean = false,
        strogo: Boolean = false,
    ): Double {
        val jeProjekcija = operacija == "Projekcija"
        return sopstvenaCenaSaMaterializacijom(roditeljJeProjekcija, strogo) +
            deca.sumOf { it.kumulativnaCenaSaMaterializacijom(jeProjekcija, strogo) }
    }
}
