# SQL Query Optimizer

A Kotlin command-line tool that parses a restricted SQL query, estimates disk I/O costs, and produces a physical query plan from table and index statistics.

Developed as a university coursework project to explore query parsing, selectivity estimation, access-path selection, and join ordering. The tool models query execution; it does not connect to a database or execute the SQL query.

## Features

- Custom SQL tokenizer and parser for `SELECT`, `FROM`, `WHERE` and `ORDER BY`.
- Memoized dynamic programming over left-deep join orders for up to **4 tables**.
- Up to **6 WHERE conditions**, with comparisons, conjunctions and supported OR groups.
- Cost models for full scans and B+ tree or hash index access.
- Comparison of **6 join strategies**: in-memory, hash, block nested-loop, nested-loop, merge, and index nested-loop joins, where applicable.
- Cardinality and selectivity estimates based on schema statistics.
- A text-based plan tree with estimated rows, blocks, operation cost and intermediate-result write costs.
- A detailed mode showing candidate plans and their estimated costs.

## Requirements

- JDK 17.
- Internet access for the first Gradle build to download Gradle and dependencies.

The repository includes a Gradle wrapper. A separate Kotlin or Gradle installation is not required. Kotlin 1.9.22 and the application entry point `GlavniKt` are configured in `build.gradle.kts`.

## Quick Start

Run the following commands from the directory containing `build.gradle.kts`, `gradlew` and `src/`.

On macOS or Linux:

```sh
bash ./gradlew build
bash ./gradlew run --args="--sema primeri/input.json --upit 'SELECT ime, prosek FROM Student WHERE godinaUpisa >= 2022' --detaljno"
```

On Windows, use `gradlew.bat` instead of `bash ./gradlew`.

The program prints a plan and an estimated cost in block transfers. These are model estimates, not measured database runtimes.

### Join Example

```sh
bash ./gradlew run --args="--sema primeri/input.json --upit 'SELECT s.ime, i.ocena FROM Student s, Ispit i WHERE s.indeks = i.studentIndeks AND i.ocena >= 8' --detaljno"
```

Tables are comma-separated in `FROM`; join predicates belong in `WHERE`. Table aliases are written directly after table names, without `AS`.

### Build a Standalone JAR

```sh
bash ./gradlew fatJar
java -jar build/libs/baze-kt-fat.jar --sema primeri/input.json --upit "SELECT ime FROM Student"
```

The generated JAR contains the runtime dependencies and can be run with Java 17 without an IDE or Gradle installation.

## Command-Line Options

| Option | Purpose |
| --- | --- |
| `--sema PATH` | Load the JSON schema and statistics file. Required. |
| `--upit "SQL"` | Supply the SQL query directly. |
| `--upit-fajl PATH` | Read SQL from a file instead of `--upit`. |
| `--detaljno` | Include candidate plans and cost details in the output. |
| `--pipeline` | Report costs without intermediate-result write costs. |
| `--strogo` | Report costs using stricter intermediate-result materialization. |
| `--help` | Print usage information. |

Use exactly one of `--upit` and `--upit-fajl`. The `--pipeline` and `--strogo` flags are mutually exclusive. They change the cost breakdown of the selected plan; they do not rerun join-order selection under a different cost policy.

When using a query file, include only SQL text. Terminal commands do not belong in the query file.

## Schema Input

The files in `primeri/` demonstrate the JSON format. A schema specifies:

- Available buffer blocks.
- Table names, row counts, block counts, and rows per block.
- Attribute types, distinct-value counts, uniqueness, and optional value ranges.
- Index type, indexed attributes, clustering, and B+ tree height where relevant.

To use another schema, provide its JSON path with `--sema` and use the same table and attribute names in the query.

## Project Structure

| File or directory | Responsibility |
| --- | --- |
| `src/main/kotlin/Glavni.kt` | Command-line arguments, input loading, and application entry point. |
| `src/main/kotlin/ParserUpita.kt` | Tokenization and SQL parsing. |
| `src/main/kotlin/Upit.kt` | Query representation. |
| `src/main/kotlin/Sema.kt` | Schema and statistics loading. |
| `src/main/kotlin/Planer.kt` | Access-path selection and join-order planning. |
| `src/main/kotlin/Algoritmi.kt` | Cost models for physical operations. |
| `src/main/kotlin/Plan.kt` | Plan nodes, cardinality estimates, and cumulative costs. |
| `src/main/kotlin/Ispis.kt` | Plan formatting and detailed cost output. |
| `primeri/` | Example schema files. |

## Scope and Limitations

- This is an educational cost-based planner for a SQL subset, not a production database optimizer.
- Use explicit column names. `SELECT *`, explicit `JOIN ... ON`, aggregates, subqueries, and trailing semicolons are not supported by the parser.
- `ORDER BY` accepts one attribute, optionally followed by `ASC` or `DESC`.
- OR groups are restricted to filter conditions; join predicates inside OR groups are rejected.
- Estimates depend on the supplied statistics and the implemented cost assumptions.
- The search considers left-deep join plans rather than every possible plan shape.
- Source identifiers and console messages are primarily in Serbian; SQL keywords use their standard English spelling.

