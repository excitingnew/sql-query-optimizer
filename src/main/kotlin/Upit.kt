enum class TipPoredjenja(val simbol: String) {
    JEDNAKO("="),
    RAZLICITO("!="),
    MANJE("<"),
    VECE(">"),
    MANJE_JEDNAKO("<="),
    VECE_JEDNAKO(">="),
}

enum class Smer(val oznaka: String) {
    RASTUCE("ASC"),
    OPADAJUCE("DESC"),
}

data class ReferencaAtributa(val izvor: String?, val atribut: String) {
    // Ispisuje atribut sa alijasom tabele ako je alijas naveden
    override fun toString(): String = if (izvor != null) "$izvor.$atribut" else atribut
}

data class IzvornaTabela(val naziv: String, val alijas: String? = null) {
    // Vraca ime kojim se tabela koristi u upitu
    fun vidljivoIme(): String = alijas ?: naziv
}

sealed class Vrednost {
    data class Celi(val v: Long) : Vrednost() { override fun toString() = v.toString() }
    data class Decimalni(val v: Double) : Vrednost() { override fun toString() = v.toString() }
    data class Tekst(val v: String) : Vrednost() { override fun toString() = "'$v'" }
}

data class Uslov(
    val leva: ReferencaAtributa,
    val operator: TipPoredjenja,
    val desna: Any,
) {
    init {
        require(desna is ReferencaAtributa || desna is Vrednost) {
            "Desni operand uslova mora biti ReferencaAtributa ili Vrednost, dobijeno: ${desna::class.simpleName}"
        }
    }

    // Uslov je spajanje ako se sa obe strane nalaze atributi
    fun jeSpajanje(): Boolean {
        return desna is ReferencaAtributa
    }
    // Uslov je filter ako se atribut poredi sa konkretnom vrednoscu
    fun jeFilter(): Boolean = !jeSpajanje()
}

data class Sortiranje(
    val atribut: ReferencaAtributa,
    val smer: Smer = Smer.RASTUCE,
)

data class OrGrupa(val uslovi: List<Uslov>) {
    init {
        require(uslovi.size >= 2) { "OR grupa mora imati bar 2 uslova" }
        require(uslovi.all { it.jeFilter() }) { "OR grupa moze sadrzati samo filter uslove, ne spajanja" }
    }

    // Vraca sve alijase tabela koji se pojavljuju u OR grupi
    fun alijasi(): Set<String> = uslovi.mapNotNull { it.leva.izvor }.toSet()
}

data class Upit(
    var projekcija: List<ReferencaAtributa> = emptyList(),
    var izvori: List<IzvornaTabela> = emptyList(),
    var uslovi: List<Uslov> = emptyList(),
    var orGrupe: List<OrGrupa> = emptyList(),
    var sortiranje: Sortiranje? = null,
) {
    // Izdvaja uslove koji povezuju dve tabele
    fun uslovZaSpajanja(): List<Uslov> = uslovi.filter { it.jeSpajanje() }

    // Izdvaja uslove koji filtriraju redove jedne tabele
    fun uslovZaFiltere(): List<Uslov> = uslovi.filter { it.jeFilter() }
}
