# sqlcj

sqlcj is a SQL compiler and type-safe Java code generator for PostgreSQL,
inspired by [sqlc](https://github.com/sqlc-dev/sqlc).

You write a PostgreSQL schema snapshot and named SQL queries. sqlcj analyzes
them against the schema and generates readable Java classes with typed
parameters and typed result records, which execute through a small JDBC runtime:

```text
schema + named SQL  ->  sqlcj generate  ->  generated Java  ->  JDBC
```

```sql
-- name: GetAuthor :one
SELECT id, name, bio
FROM authors
WHERE id = $1;
```

```java
GetAuthor.GetAuthorResult author = new GetAuthor(executor).getAuthor(1L);
```

The SQL stays visible and owned by the application. sqlcj is not an ORM, a
migration tool, or a query builder: it does not run migrations, inspect a live
database, or build queries at runtime.

## Project status

sqlcj is pre-release. The current version is `0.1.0-SNAPSHOT`, and no artifact
has been published to Maven Central or any other public repository yet, so the
CLI and runtime must be built from this repository. There is no released license
or release procedure yet either.

## Requirements

- Java 21 for the CLI and for applications that use the generated code.
- Maven, to build sqlcj and to build a consuming project.
- PostgreSQL, reached through the application's own PostgreSQL JDBC driver.
  sqlcj does not ship a driver. Behavior is verified against PostgreSQL 16.

## Artifacts

`mvn -DskipTests install` produces both artifacts from this repository:

| Artifact | What it is |
| --- | --- |
| `sqlcj-cli/target/sqlcj-cli-0.1.0-SNAPSHOT.jar` | The executable command line tool. It bundles its own dependencies and starts `dev.sqlcj.Main`. |
| `dev.sqlcj:sqlcj-runtime:0.1.0-SNAPSHOT` | The dependency of generated code. It has no dependencies of its own, so compiler, YAML, and SQL-parser libraries never reach an application. |

The CLI and the runtime must always be used at the same version.

## Commands

```bash
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar version    # print the tool version
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar generate   # compile sqlcj.yaml into Java
java -jar sqlcj-cli-0.1.0-SNAPSHOT.jar --help     # list the commands
```

`generate` reads `sqlcj.yaml` from the directory it is run in. It exits with
status `0` on success, and with status `1` after printing one diagnostic for
invalid configuration, an unreadable source, or a query it cannot compile.

## Getting started

Follow the [Quickstart](docs/quickstart.md) to build a Maven application that
generates, compiles, and runs PostgreSQL create, read, list, update, and delete
operations, including an application-controlled commit and rollback.

## Documentation

- [Quickstart](docs/quickstart.md) — an end-to-end PostgreSQL project from an
  empty directory.
- [Queries](docs/queries.md) — query annotations, the supported SQL shapes,
  parameter and binding order, the generated API, and what is not supported.
- [Configuration](docs/configuration.md) — the `sqlcj.yaml` format, path
  resolution, and generated Java naming.
- [PostgreSQL Support](docs/postgresql.md) — the engine contract, accepted
  column types and `CREATE TABLE` constructs, null handling, and connection
  ownership.
