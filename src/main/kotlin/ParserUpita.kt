const val MAX_TABELA = 4
const val MAX_USLOVA_WHERE = 6

private val KLJUCNE_RECI = setOf("SELECT", "FROM", "WHERE", "AND", "OR", "ORDER", "BY", "ASC", "DESC")

enum class VrstaTokena { BROJ, NISKA, OPERATOR, ZAREZ, TACKA, LZAGRADA, DZAGRADA, IDENT, KLJUC }

data class Token(val vrsta: VrstaTokena, val sadrzaj: String, val pozicija: Int)

private val SABLON_TOKENA = Regex(
    "\\s+" +
        "|(?<BROJ>-?\\d+\\.\\d+|-?\\d+)" +
        "|(?<NISKA>'[^']*')" +
        "|(?<OPERATOR>>=|<=|!=|<>|=|<|>)" +
        "|(?<ZAREZ>,)" +
        "|(?<TACKA>\\.)" +
        "|(?<LZAGRADA>\\()" +
        "|(?<DZAGRADA>\\))" +
        "|(?<IDENT>[A-Za-z_][A-Za-z0-9_]*)"
)

class GreskaParsiranja(poruka: String) : RuntimeException(poruka)

// Deli SQL tekst na tokene koje parser moze redom da obradi
fun tokenizuj(tekst: String): List<Token> {
    val tokeni = mutableListOf<Token>()
    var pozicija = 0
    while (pozicija < tekst.length) {
        val podudaranje = SABLON_TOKENA.find(tekst, pozicija)
        if (podudaranje == null || podudaranje.range.first != pozicija) {
            throw GreskaParsiranja(
                "Neprepoznat znak na poziciji $pozicija: '${tekst[pozicija]}'"
            )
        }
        val nadjenaGrupa = imenovaneGrupePogodjeneNa(podudaranje)
        if (nadjenaGrupa == null) {
            pozicija = podudaranje.range.last + 1
            continue
        }
        val (ime, sadrzaj) = nadjenaGrupa
        val vrsta = VrstaTokena.valueOf(ime)
        if (vrsta == VrstaTokena.IDENT && sadrzaj.uppercase() in KLJUCNE_RECI) {
            tokeni.add(Token(VrstaTokena.KLJUC, sadrzaj.uppercase(), pozicija))
        } else {
            tokeni.add(Token(vrsta, sadrzaj, pozicija))
        }
        pozicija = podudaranje.range.last + 1
    }
    return tokeni
}

// Pronalazi koja imenovana grupa regularnog izraza je prepoznala deo teksta
private fun imenovaneGrupePogodjeneNa(podudaranje: MatchResult): Pair<String, String>? {
    val grupe = listOf(
        "BROJ", "NISKA", "OPERATOR", "ZAREZ", "TACKA",
        "LZAGRADA", "DZAGRADA", "IDENT",
    )
    for (ime in grupe) {
        val sadrzaj = podudaranje.groups[ime]?.value
        if (sadrzaj != null) return ime to sadrzaj
    }
    return null
}

// Pretvara ceo SQL tekst u internu reprezentaciju Upit
fun parsiraj(tekst: String): Upit = Parser(tokenizuj(tekst)).parsirajUpit()

private class Parser(private val tokeni: List<Token>) {
    private var indeks = 0

    // Parsira SELECT, FROM, opcioni WHERE i opcioni ORDER BY
    fun parsirajUpit(): Upit {
        val upit = Upit()
        procitajKljuc("SELECT")
        upit.projekcija = parsirajListuReferenci()
        procitajKljuc("FROM")
        upit.izvori = parsirajIzvore()
        if (upit.izvori.size > MAX_TABELA) {
            throw GreskaParsiranja(
                "FROM klauzula sme imati najvise $MAX_TABELA tabele, " +
                    "a dobijeno ${upit.izvori.size}."
            )
        }
        if (tekuciJeKljuc("WHERE")) {
            potrosi()
            val (prostiUslovi, orGrupe) = parsirajWhere()
            upit.uslovi = prostiUslovi
            upit.orGrupe = orGrupe
            val ukupno = prostiUslovi.size + orGrupe.sumOf { it.uslovi.size }
            if (ukupno > MAX_USLOVA_WHERE) {
                throw GreskaParsiranja(
                    "WHERE klauzula sme imati najvise $MAX_USLOVA_WHERE uslova " +
                        "(konjunkcija/disjunkcija), a dobijeno $ukupno."
                )
            }
        }
        if (tekuciJeKljuc("ORDER")) {
            potrosi()
            procitajKljuc("BY")
            upit.sortiranje = parsirajSortiranje()
        }
        if (indeks < tokeni.size) {
            throw GreskaParsiranja("Neocekivan token na kraju upita: '${tokeni[indeks].sadrzaj}'")
        }
        return upit
    }

    // Parsira listu atributa razdvojenih zarezima iz SELECT klauzule
    private fun parsirajListuReferenci(): List<ReferencaAtributa> {
        val reference = mutableListOf(parsirajReferencu())
        while (tekuciJeZarez()) {
            potrosi()
            reference.add(parsirajReferencu())
        }
        return reference
    }

    // Parsira atribut zapisan kao ime ili alijas.ime
    private fun parsirajReferencu(): ReferencaAtributa {
        val prvi = procitajIdent()
        if (tekuciJeTacka()) {
            potrosi()
            val drugi = procitajIdent()
            return ReferencaAtributa(izvor = prvi, atribut = drugi)
        }
        return ReferencaAtributa(izvor = null, atribut = prvi)
    }

    // Parsira listu tabela razdvojenih zarezima iz FROM klauzule
    private fun parsirajIzvore(): List<IzvornaTabela> {
        val izvori = mutableListOf(parsirajIzvor())
        while (tekuciJeZarez()) {
            potrosi()
            izvori.add(parsirajIzvor())
        }
        return izvori
    }

    // Parsira naziv jedne tabele i njen opcioni alijas
    private fun parsirajIzvor(): IzvornaTabela {
        val naziv = procitajIdent()
        val alijas = if (tekuciJeIdent()) procitajIdent() else null
        return IzvornaTabela(naziv = naziv, alijas = alijas)
    }

    // Parsira WHERE klauzulu i razdvaja proste uslove od OR grupa
    private fun parsirajWhere(): Pair<List<Uslov>, List<OrGrupa>> {
        val prosti = mutableListOf<Uslov>()
        val orGrupe = mutableListOf<OrGrupa>()
        val prvaGrupa = parsirajOrGrupu()
        rasporediGrupu(prvaGrupa, prosti, orGrupe)
        while (tekuciJeKljuc("AND")) {
            potrosi()
            val grupa = parsirajOrGrupu()
            rasporediGrupu(grupa, prosti, orGrupe)
        }
        return prosti to orGrupe
    }

    // Smesta jedan uslov u proste uslove, a vise OR uslova u OR grupu
    private fun rasporediGrupu(
        grupa: List<Uslov>,
        prosti: MutableList<Uslov>,
        orGrupe: MutableList<OrGrupa>,
    ) {
        if (grupa.size == 1) {
            prosti.add(grupa.first())
        } else {
            if (grupa.any { it.jeSpajanje() }) {
                throw GreskaParsiranja(
                    "OR grupa ne sme sadrzati uslov spajanja (samo filter uslove)."
                )
            }
            orGrupe.add(OrGrupa(grupa))
        }
    }

    // Parsira jedan uslov ili niz uslova povezanih operatorom OR
    private fun parsirajOrGrupu(): List<Uslov> {
        val imaZagrade = tekuciJe(VrstaTokena.LZAGRADA)
        if (imaZagrade) potrosi()
        val uslovi = mutableListOf(parsirajUslov())
        while (tekuciJeKljuc("OR")) {
            potrosi()
            uslovi.add(parsirajUslov())
        }
        if (imaZagrade) procitajToken(VrstaTokena.DZAGRADA, "zatvorena zagrada")
        return uslovi
    }

    // Parsira levi atribut, operator poredjenja i desni operand uslova
    private fun parsirajUslov(): Uslov {
        val leva = parsirajReferencu()
        if (!tekuciJeOperator()) {
            throw GreskaParsiranja(
                "Ocekivan operator poredjenja, dobijeno: ${opisTekuceg()}"
            )
        }
        val operatorToken = potrosi()
        val operator = mapirajOperator(operatorToken.sadrzaj)
        val desna = parsirajOperandDesneStrane()
        return Uslov(leva, operator, desna)
    }

    // Parsira broj, tekst ili referencu atributa sa desne strane uslova
    private fun parsirajOperandDesneStrane(): Any {
        val token = pogledaj()
        when (token.vrsta) {
            VrstaTokena.BROJ -> {
                potrosi()
                return if ("." in token.sadrzaj) Vrednost.Decimalni(token.sadrzaj.toDouble())
                else Vrednost.Celi(token.sadrzaj.toLong())
            }
            VrstaTokena.NISKA -> {
                potrosi()
                return Vrednost.Tekst(token.sadrzaj.substring(1, token.sadrzaj.length - 1))
            }
            VrstaTokena.IDENT -> return parsirajReferencu()
            else -> throw GreskaParsiranja(
                "Ocekivana vrednost ili referenca atributa, dobijeno: ${opisTekuceg()}"
            )
        }
    }

    // Parsira ORDER BY atribut i opcioni smer ASC ili DESC
    private fun parsirajSortiranje(): Sortiranje {
        val atribut = parsirajReferencu()
        var smer = Smer.RASTUCE
        if (tekuciJeKljuc("ASC")) {
            potrosi()
        } else if (tekuciJeKljuc("DESC")) {
            potrosi()
            smer = Smer.OPADAJUCE
        }
        return Sortiranje(atribut, smer)
    }

    // Vraca tekuci token bez pomeranja na sledeci token
    private fun pogledaj(): Token {
        if (indeks >= tokeni.size) throw GreskaParsiranja("Neocekivan kraj upita")
        return tokeni[indeks]
    }

    // Vraca tekuci token i pomera parser na sledeci token
    private fun potrosi(): Token = pogledaj().also { indeks++ }

    // Cita ocekivanu SQL kljucnu rec ili prijavljuje gresku
    private fun procitajKljuc(ocekivano: String): Token {
        val token = pogledaj()
        if (token.vrsta != VrstaTokena.KLJUC || token.sadrzaj != ocekivano) {
            throw GreskaParsiranja(
                "Ocekivana kljucna rec '$ocekivano', dobijeno: ${opisTekuceg()}"
            )
        }
        return potrosi()
    }

    // Cita identifikator koji predstavlja naziv tabele, alijasa ili atributa
    private fun procitajIdent(): String {
        val token = pogledaj()
        if (token.vrsta != VrstaTokena.IDENT) {
            throw GreskaParsiranja("Ocekivan identifikator, dobijeno: ${opisTekuceg()}")
        }
        return potrosi().sadrzaj
    }

    // Cita token zadate vrste ili prijavljuje sta je bilo ocekivano
    private fun procitajToken(vrsta: VrstaTokena, opis: String): Token {
        val token = pogledaj()
        if (token.vrsta != vrsta) {
            throw GreskaParsiranja("Ocekivana $opis, dobijeno: ${opisTekuceg()}")
        }
        return potrosi()
    }

    // Proverava da li je tekuci token zadate vrste
    private fun tekuciJe(vrsta: VrstaTokena): Boolean =
        indeks < tokeni.size && tokeni[indeks].vrsta == vrsta

    // Proverava da li je tekuci token odredjena SQL kljucna rec
    private fun tekuciJeKljuc(vrednost: String): Boolean =
        indeks < tokeni.size &&
            tokeni[indeks].vrsta == VrstaTokena.KLJUC &&
            tokeni[indeks].sadrzaj == vrednost

    // Proverava da li je tekuci token zarez
    private fun tekuciJeZarez(): Boolean =
        indeks < tokeni.size && tokeni[indeks].vrsta == VrstaTokena.ZAREZ

    // Proverava da li je tekuci token tacka izmedju alijasa i atributa
    private fun tekuciJeTacka(): Boolean =
        indeks < tokeni.size && tokeni[indeks].vrsta == VrstaTokena.TACKA

    // Proverava da li je tekuci token operator poredjenja
    private fun tekuciJeOperator(): Boolean =
        indeks < tokeni.size && tokeni[indeks].vrsta == VrstaTokena.OPERATOR

    // Proverava da li je tekuci token identifikator
    private fun tekuciJeIdent(): Boolean =
        indeks < tokeni.size && tokeni[indeks].vrsta == VrstaTokena.IDENT

    // Pravi citljiv opis tekuceg tokena za poruku o gresci
    private fun opisTekuceg(): String {
        if (indeks >= tokeni.size) return "kraj upita"
        val t = tokeni[indeks]
        return "${t.vrsta.name}('${t.sadrzaj}')"
    }
}

// Pretvara tekstualni SQL operator u odgovarajuci TipPoredjenja
private fun mapirajOperator(simbol: String): TipPoredjenja = when (simbol) {
    "=" -> TipPoredjenja.JEDNAKO
    "!=", "<>" -> TipPoredjenja.RAZLICITO
    "<" -> TipPoredjenja.MANJE
    ">" -> TipPoredjenja.VECE
    "<=" -> TipPoredjenja.MANJE_JEDNAKO
    ">=" -> TipPoredjenja.VECE_JEDNAKO
    else -> throw GreskaParsiranja("Nepoznat operator: '$simbol'")
}
