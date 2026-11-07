import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(izvrsi(args))
}

fun izvrsi(args: Array<String>): Int {
    val opcije = try {
        raspakuj(args)
    } catch (e: GreskaArgumenata) {
        System.err.println(e.message)
        ispisiPomoc(System.err)
        return 1
    }
    if (opcije.pomoc) {
        ispisiPomoc(System.out)
        return 0
    }

    val sema = try {
        ucitajSemuIzDatoteke(opcije.sema!!)
    } catch (e: Exception) {
        System.err.println("Greska pri ucitavanju seme: ${e.message}")
        return 2
    }

    val tekstUpita = try {
        ucitajTekstUpita(opcije)
    } catch (e: Exception) {
        System.err.println("Greska pri ucitavanju upita: ${e.message}")
        return 2
    }

    val upit = try {
        parsiraj(tekstUpita)
    } catch (e: GreskaParsiranja) {
        System.err.println("Greska pri parsiranju upita: ${e.message}")
        return 3
    }

    val plan = try {
        Planer(upit, sema).planiraj()
    } catch (e: Exception) {
        System.err.println("Greska pri planiranju: ${e.message}")
        return 4
    }

    println(
        formatirajPlan(
            plan,
            saMaterializacijom = !opcije.pipeline,
            strogo = opcije.strogo,
            detaljno = opcije.detaljno,
        )
    )
    return 0
}

private data class Opcije(
    val sema: Path? = null,
    val upit: String? = null,
    val upitFajl: Path? = null,
    val pomoc: Boolean = false,
    val pipeline: Boolean = false,
    val strogo: Boolean = false,
    val detaljno: Boolean = false,
)

private class GreskaArgumenata(poruka: String) : RuntimeException(poruka)

private fun raspakuj(args: Array<String>): Opcije {
    var sema: Path? = null
    var upit: String? = null
    var upitFajl: Path? = null
    var pomoc = false
    var pipeline = false
    var strogo = false
    var detaljno = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--sema" -> {
                if (i + 1 >= args.size) throw GreskaArgumenata("--sema zahteva putanju")
                sema = Path.of(args[i + 1])
                i += 2
            }
            "--upit" -> {
                if (i + 1 >= args.size) throw GreskaArgumenata("--upit zahteva SQL string")
                upit = args[i + 1]
                i += 2
            }
            "--upit-fajl" -> {
                if (i + 1 >= args.size) throw GreskaArgumenata("--upit-fajl zahteva putanju")
                upitFajl = Path.of(args[i + 1])
                i += 2
            }
            "--pipeline" -> {
                pipeline = true
                i += 1
            }
            "--strogo" -> {
                strogo = true
                i += 1
            }
            "--detaljno" -> {
                detaljno = true
                i += 1
            }
            "-h", "--help" -> {
                pomoc = true
                i += 1
            }
            else -> throw GreskaArgumenata("Nepoznat argument: $arg")
        }
    }
    if (!pomoc) {
        if (sema == null) throw GreskaArgumenata("Nedostaje --sema")
        if (upit == null && upitFajl == null) {
            throw GreskaArgumenata("Nedostaje --upit ili --upit-fajl")
        }
        if (upit != null && upitFajl != null) {
            throw GreskaArgumenata("Koristi ili --upit ili --upit-fajl, ne oba")
        }
        if (pipeline && strogo) {
            throw GreskaArgumenata("Koristi ili --pipeline ili --strogo, ne oba")
        }
    }
    return Opcije(sema, upit, upitFajl, pomoc, pipeline, strogo, detaljno)
}

private fun ucitajTekstUpita(opcije: Opcije): String =
    opcije.upit ?: Files.readString(opcije.upitFajl!!)

private fun ispisiPomoc(izlaz: java.io.PrintStream) {
    izlaz.println(
        """
        Pokretanje:
          gradle run --args="--sema PUT_DO_SEME.json --upit 'SELECT ...'"
          gradle run --args="--sema PUT_DO_SEME.json --upit-fajl upit.sql"

        Argumenti:
          --sema PATH        JSON datoteka sa opisom seme
          --upit "SQL"       SQL upit kao string
          --upit-fajl PATH   Datoteka koja sadrzi SQL upit
          --pipeline         Ignorisi cenu upisa medjurezultata (protocna evaluacija)
          --strogo           Stroga materijalizacija: sve osim Projekcije mat.
                             Default: umerena materijalizacija (scan/Sortiranje kao
                             dete Projekcije preskacu mat, sve ostalo materijalizuje)
          --detaljno         Prikazi razmatrane kandidate i njihove cene
          -h, --help         Prikazi ovu pomoc
        """.trimIndent()
    )
}
