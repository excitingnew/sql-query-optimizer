import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

enum class TipAtributa { STRING, INT, DOUBLE, DATE }

enum class TipIndeksa(val oznaka: String) {
    B_STABLO("B_PLUS_TREE"),
    HES("HASH");

    companion object {
        // Pretvara oznaku tipa indeksa iz JSON-a u odgovarajucu enum vrednost
        fun izOznake(oznaka: String): TipIndeksa {
            val pronadjeniTip = values().firstOrNull { tipIndeksa ->
                tipIndeksa.oznaka == oznaka
            }
            if (pronadjeniTip != null) {
                return pronadjeniTip
            }
            throw IllegalArgumentException("Nepoznat tip indeksa: $oznaka")
        }
    }
}

data class Atribut(
    val naziv: String,
    val tip: TipAtributa,
    val jedinstven: Boolean,
    val razlicitihVrednosti: Int,
    val minVrednost: Double? = null,
    val maxVrednost: Double? = null,
)

data class Indeks(
    val naziv: String,
    val atributi: List<String>,
    val tip: TipIndeksa,
    val klasterovan: Boolean,
    val visinaStabla: Int? = null,
) {
    // Proverava da li je atribut prvi atribut kompozitnog indeksa
    fun jePrefiksOd(atribut: String): Boolean =
        atributi.isNotEmpty() && atributi[0] == atribut

    // Proverava da li indeks sadrzi zadati atribut na bilo kojoj poziciji
    fun sadrziAtribut(atribut: String): Boolean = atribut in atributi

    // Proverava da li je indeks B+ stablo
    fun jeBStablo(): Boolean = tip == TipIndeksa.B_STABLO

    // Proverava da li je indeks hes indeks
    fun jeHes(): Boolean = tip == TipIndeksa.HES
}

data class Tabela(
    val naziv: String,
    val brojRedova: Int,
    val brojBlokova: Int,
    val redovaPoBloku: Int,
    val atributi: List<Atribut> = emptyList(),
    val indeksi: List<Indeks> = emptyList(),
) {
    // Pronalazi atribut po nazivu ili prijavljuje da atribut ne postoji
    fun dohvatiAtribut(naziv: String): Atribut =
        atributi.firstOrNull { it.naziv == naziv }
            ?: throw NoSuchElementException(
                "Atribut '$naziv' ne postoji u tabeli '${this.naziv}'"
            )

    // Proverava da li tabela ima atribut sa zadatim nazivom
    fun imaAtribut(naziv: String): Boolean = atributi.any { it.naziv == naziv }

    // Vraca indekse kojima je zadati atribut prvi atribut kljuca
    fun indeksiSaPrefiksom(atribut: String): List<Indeks> =
        indeksi.filter { it.jePrefiksOd(atribut) }
}

data class Sema(
    val velicinaBafera: Int,
    val tabele: List<Tabela> = emptyList(),
) {
    // Pronalazi tabelu po nazivu ili prijavljuje da tabela ne postoji
    fun dohvatiTabelu(naziv: String): Tabela =
        tabele.firstOrNull { it.naziv == naziv }
            ?: throw NoSuchElementException("Tabela '$naziv' ne postoji u semi")

    // Proverava da li sema sadrzi tabelu sa zadatim nazivom
    fun imaTabelu(naziv: String): Boolean = tabele.any { it.naziv == naziv }
}

// Cita JSON datoteku i pretvara njen sadrzaj u objekat Sema
fun ucitajSemuIzDatoteke(putanja: Path): Sema {
    val tekst = Files.readString(putanja)
    return ucitajSemuIzObjekta(JSONObject(tekst))
}

// Omogucava ucitavanje seme kada je putanja prosledjena kao tekst
fun ucitajSemuIzDatoteke(putanja: String): Sema = ucitajSemuIzDatoteke(Path.of(putanja))

// Iz JSON objekta ucitava velicinu bafera i sve tabele seme
fun ucitajSemuIzObjekta(sirovo: JSONObject): Sema {
    val bafer = sirovo.getInt("bufferBlocks")
    val tabeleNiz = sirovo.getJSONObject("schema").getJSONArray("tables")
    val tabele = tabeleNiz.objekti().map { napraviTabelu(it) }
    return Sema(velicinaBafera = bafer, tabele = tabele)
}

// Pretvara jedan JSON objekat tabele u objekat Tabela
private fun napraviTabelu(podaci: JSONObject): Tabela {
    val atributi = podaci.optJSONArray("attributes")?.objekti()?.map { napraviAtribut(it) }
        ?: emptyList()
    val indeksi = podaci.optJSONArray("indexes")?.objekti()?.map { napraviIndeks(it) }
        ?: emptyList()
    return Tabela(
        naziv = podaci.getString("name"),
        brojRedova = podaci.getInt("rowCount"),
        brojBlokova = podaci.getInt("blockCount"),
        redovaPoBloku = podaci.getInt("rowsPerBlock"),
        atributi = atributi,
        indeksi = indeksi,
    )
}

// Pretvara jedan JSON objekat atributa u objekat Atribut
private fun napraviAtribut(podaci: JSONObject): Atribut = Atribut(
    naziv = podaci.getString("name"),
    tip = TipAtributa.valueOf(podaci.getString("type")),
    jedinstven = podaci.getBoolean("unique"),
    razlicitihVrednosti = podaci.getInt("distinctValues"),
    minVrednost = podaci.opcioniBroj("minValue"),
    maxVrednost = podaci.opcioniBroj("maxValue"),
)

// Pretvara jedan JSON objekat indeksa u objekat Indeks
private fun napraviIndeks(podaci: JSONObject): Indeks = Indeks(
    naziv = podaci.getString("name"),
    atributi = podaci.getJSONArray("attributes").nizString(),
    tip = TipIndeksa.izOznake(podaci.getString("type")),
    klasterovan = podaci.getBoolean("clustered"),
    visinaStabla = podaci.opcioniInt("treeHeight"),
)

// Pretvara JSON niz objekata u Kotlin listu JSON objekata
internal fun JSONArray.objekti(): List<JSONObject> =
    (0 until length()).map { getJSONObject(it) }

// Pretvara JSON niz tekstova u Kotlin listu tekstova
internal fun JSONArray.nizString(): List<String> =
    (0 until length()).map { getString(it) }

// Cita opcioni decimalni broj i vraca null ako polje ne postoji
internal fun JSONObject.opcioniBroj(kljuc: String): Double? =
    if (has(kljuc) && !isNull(kljuc)) getDouble(kljuc) else null

// Cita opcioni ceo broj i vraca null ako polje ne postoji
internal fun JSONObject.opcioniInt(kljuc: String): Int? =
    if (has(kljuc) && !isNull(kljuc)) getInt(kljuc) else null
