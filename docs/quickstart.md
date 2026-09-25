# Quickstart

This walkthrough builds a small Maven application that reads and writes two
PostgreSQL tables through sqlcj-generated Java. Every file is listed in full, so
the steps can be followed from an empty directory.

The finished project uses only the packaged sqlcj artifacts: the executable CLI
JAR and the `dev.sqlcj:sqlcj-runtime` dependency. The sqlcj source tree is never
on the application's classpath.

## 1. Prerequisites

- A full JDK 21 on the `PATH` (`java -version` reports 21).
- Maven (verified with 3.8.7).
- Docker, or another way to reach a PostgreSQL 16 server.

## 2. Get the sqlcj artifacts

sqlcj has no public release yet, so the artifacts are not downloadable from
Maven Central or any other repository. Build them once from a clone of the sqlcj
repository:

```bash
mvn -f /path/to/sqlcj/pom.xml -DskipTests install
```

That command:

- installs `dev.sqlcj:sqlcj-runtime:0.1.0-SNAPSHOT` into the local Maven
  repository, where the application below resolves it, and
- produces the executable CLI at
  `/path/to/sqlcj/sqlcj-cli/target/sqlcj-cli-0.1.0-SNAPSHOT.jar`.

The CLI JAR carries its own dependencies and starts `dev.sqlcj.Main`. Copy it
into the new project and confirm its version:

```bash
mkdir -p my-app/tools
cp /path/to/sqlcj/sqlcj-cli/target/sqlcj-cli-0.1.0-SNAPSHOT.jar my-app/tools/
cd my-app
java -jar tools/sqlcj-cli-0.1.0-SNAPSHOT.jar version
```

```text
0.1.0-SNAPSHOT
```

The CLI and the runtime dependency must always be the same version.

## 3. Create the project layout

```text
my-app/
├── pom.xml
├── sqlcj.yaml
├── sql/
│   ├── queries.sql
│   └── schema.sql
├── src/main/java/com/example/app/App.java
└── tools/sqlcj-cli-0.1.0-SNAPSHOT.jar
```

```bash
mkdir -p sql src/main/java/com/example/app
```

## 4. `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <sqlcj.version>0.1.0-SNAPSHOT</sqlcj.version>
        <sqlcj.generated.sources>${project.build.directory}/generated-sources/sqlcj</sqlcj.generated.sources>
    </properties>

    <dependencies>
        <dependency>
            <groupId>dev.sqlcj</groupId>
            <artifactId>sqlcj-runtime</artifactId>
            <version>${sqlcj.version}</version>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.13</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.15.0</version>
                <configuration>
                    <compileSourceRoots>
                        <compileSourceRoot>${project.basedir}/src/main/java</compileSourceRoot>
                        <compileSourceRoot>${sqlcj.generated.sources}</compileSourceRoot>
                    </compileSourceRoots>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.6.3</version>
                <configuration>
                    <mainClass>com.example.app.App</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

Two details matter:

- `sqlcj-runtime` is the only sqlcj dependency. It has no dependencies of its
  own; the compiler, YAML, and SQL-parser libraries stay inside the CLI JAR.
- Maven Compiler Plugin 3.15.0 compiles the directories listed in
  `compileSourceRoots`. Setting that list replaces the default, so both
  `src/main/java` and the sqlcj output directory must be named explicitly.
  Generated sources placed under `target/generated-sources` are not picked up
  automatically, because the plugin only adds that directory to the project
  after the compile execution has already chosen its inputs.

## 5. `sqlcj.yaml`

```yaml
version: "1"
sql:
  - name: Author
    schema: sql/schema.sql
    queries: sql/queries.sql
java:
  package: com.example.app.db
  out: target/generated-sources/sqlcj
```

`sql[].name` is the identity of the query group. It names the generated
repository, so the entry above generates one `AuthorRepository` holding every
query of `sql/queries.sql`.

`sqlcj generate` reads `sqlcj.yaml` from the directory it is run in, and the
relative paths above are resolved against the directory that contains the file.
`--config <path>` names another file when the command runs from elsewhere.
Generated output therefore belongs in the build directory, where `mvn clean`
removes it. See [Configuration](configuration.md) for the full file format.

## 6. `sql/schema.sql`

The schema file is a snapshot of the tables the queries use. sqlcj reads it; it
never runs it.

```sql
CREATE TABLE authors
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    bio        TEXT,
    created_at TIMESTAMP
);

CREATE TABLE books
(
    id        BIGSERIAL PRIMARY KEY,
    author_id BIGINT       NOT NULL REFERENCES authors (id),
    title     VARCHAR(255) NOT NULL
);
```

The accepted column types and `CREATE TABLE` constructs are listed in
[PostgreSQL Support](postgresql.md).

## 7. `sql/queries.sql`

```sql
-- name: CreateAuthor :one
INSERT INTO authors (name, bio)
VALUES ($1, $2)
RETURNING *;

-- name: GetAuthor :one
SELECT *
FROM authors
WHERE id = $1;

-- name: FindAuthor :optional
SELECT *
FROM authors
WHERE id = $1;

-- name: ListAuthors :many
SELECT *
FROM authors
ORDER BY id;

-- name: UpdateAuthorBio :exec
UPDATE authors
SET bio = $2
WHERE id = $1;

-- name: DeleteAuthor :exec
DELETE
FROM authors
WHERE id = $1;

-- name: SearchAuthors :many
SELECT *
FROM authors
WHERE name ILIKE $1
ORDER BY id;

-- name: CountAuthors :one
SELECT COUNT(*) AS total
FROM authors;

-- name: ListAuthorPage :many
SELECT *
FROM authors
ORDER BY id
LIMIT $1 OFFSET $2;

-- name: CreateBook :exec
INSERT INTO books (author_id, title)
VALUES ($1, $2);

-- name: ListAuthorBooks :many
SELECT a.name, b.title
FROM authors a
LEFT JOIN books b ON b.author_id = a.id
ORDER BY a.id, b.id;
```

All eleven queries become methods of the one generated `AuthorRepository`:
`createAuthor`, `getAuthor`, `findAuthor`, `listAuthors`, `updateAuthorBio`,
`deleteAuthor`, `searchAuthors`, `countAuthors`, `listAuthorPage`, `createBook`,
and `listAuthorBooks`. `CreateAuthor`, `GetAuthor`, `FindAuthor`, `ListAuthors`,
`SearchAuthors`, and `ListAuthorPage` each return one complete `authors` row, so
all six share the nested record `AuthorRepository.AuthorsRow`, generated once
from the schema's column order. A query with its own result shape, such as a
partial projection or a `RETURNING` column list, generates a nested
`AuthorRepository.<QueryName>Result` record instead: `CountAuthors` generates
`CountAuthorsResult` with the single non-null `Long` component `total`, and
`ListAuthorBooks` generates `ListAuthorBooksResult` with the components `name`
and `title`, where `title` is `null` for an author that the left-joined `books`
table does not match.

`GetAuthor` and `FindAuthor` read the same row by the same key and differ only
in cardinality: `getAuthor` returns `AuthorsRow` and requires exactly one row,
while `findAuthor` returns `Optional<AuthorsRow>` and accepts none. The full
query contract is documented in [Queries](queries.md).

## 8. `src/main/java/com/example/app/App.java`

```java
package com.example.app;

import com.example.app.db.AuthorRepository;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryCardinalityException;
import dev.sqlcj.runtime.QueryExecutor;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public final class App {

    public static void main(String[] args) throws SQLException {
        DataSource dataSource = dataSource();

        QueryExecutor executor = new JdbcQueryExecutor(dataSource);

        AuthorRepository authors = new AuthorRepository(executor);

        AuthorRepository.AuthorsRow created = authors.createAuthor("Ada Lovelace", "First programmer");

        System.out.println("created: " + created.id() + " " + created.name());

        AuthorRepository.AuthorsRow read = authors.getAuthor(created.id());

        System.out.println("read: " + read.name() + " / " + read.bio());

        int updatedRows = authors.updateAuthorBio(created.id(), "Mathematician");

        System.out.println("updated rows: " + updatedRows);

        for (AuthorRepository.AuthorsRow author : authors.listAuthors()) {
            System.out.println("listed: " + author.id() + " " + author.name());
        }

        Optional<AuthorRepository.AuthorsRow> missing = authors.findAuthor(-1L);

        System.out.println("missing row: " + missing.isPresent());

        try {
            authors.getAuthor(-1L);
        } catch (QueryCardinalityException e) {
            System.out.println("missing row rejected: " + e.getMessage());
        }

        Long committedId;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            AuthorRepository transactionalAuthors = new AuthorRepository(new JdbcQueryExecutor(connection));

            AuthorRepository.AuthorsRow committed = transactionalAuthors.createAuthor("Grace Hopper", null);

            transactionalAuthors.updateAuthorBio(committed.id(), "Compiler pioneer");

            connection.commit();

            committedId = committed.id();

            System.out.println("committed: " + authors.getAuthor(committedId).bio());
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            AuthorRepository transactionalAuthors = new AuthorRepository(new JdbcQueryExecutor(connection));

            AuthorRepository.AuthorsRow discarded = transactionalAuthors.createAuthor("Temporary Author", null);

            transactionalAuthors.updateAuthorBio(discarded.id(), "never stored");

            connection.rollback();

            System.out.println("rolled back: " + authors.findAuthor(discarded.id()).isPresent());
        }

        for (AuthorRepository.AuthorsRow author : authors.searchAuthors("%lovelace%")) {
            System.out.println("searched: " + author.id() + " " + author.name());
        }

        System.out.println("count: " + authors.countAuthors().total());

        for (AuthorRepository.AuthorsRow author : authors.listAuthorPage(1, 1)) {
            System.out.println("page: " + author.id() + " " + author.name());
        }

        int bookRows = authors.createBook(committedId, "The Education of a Computer");

        System.out.println("created book rows: " + bookRows);

        for (AuthorRepository.ListAuthorBooksResult book : authors.listAuthorBooks()) {
            System.out.println("book: " + book.name() + " / " + book.title());
        }

        System.out.println("deleted rows: " + authors.deleteAuthor(created.id()));
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();

        dataSource.setUrl("jdbc:postgresql://localhost:5432/quickstart");
        dataSource.setUser("quickstart");
        dataSource.setPassword("quickstart");

        return dataSource;
    }
}
```

One repository instance serves the whole `DataSource`-backed execution context,
and each transaction constructs another repository over its caller-owned
connection. No code constructs a type per query.

## 9. Start PostgreSQL and apply the schema

```bash
docker run --rm -d --name sqlcj-quickstart \
  -e POSTGRES_DB=quickstart \
  -e POSTGRES_USER=quickstart \
  -e POSTGRES_PASSWORD=quickstart \
  -p 5432:5432 \
  postgres:16-alpine

docker exec -i sqlcj-quickstart psql -U quickstart -d quickstart < sql/schema.sql
```

sqlcj does not create or migrate tables. Applying the same snapshot text that
the compiler reads initializes the walkthrough database consistently with the
compiler's input. sqlcj never inspects the live database, so keeping the
snapshot synchronized with later database changes is your responsibility.

## 10. Generate, compile, and run

Run the three steps in this order, from the project root:

```bash
mvn clean
java -jar tools/sqlcj-cli-0.1.0-SNAPSHOT.jar generate
mvn compile
mvn exec:java
```

- `mvn clean` deletes `target`, including previously generated sources and the
  classes compiled from them. sqlcj writes and overwrites its own files and
  deletes a repository its previous run recorded that the current run no longer
  generates, but it never deletes a compiled class, so a query that was renamed
  or removed would otherwise leave a stale class behind that still compiles.
- `sqlcj generate` is a separate command. It is not bound to the Maven
  lifecycle, so it must run after `clean` and before `compile`. The commands
  above run from the project root, because `generate` reads `sqlcj.yaml` from
  the current directory; from another directory, pass
  `--config <path to sqlcj.yaml>` instead, and the paths inside the file keep
  resolving against the project root.
- `mvn compile` then compiles `src/main/java` together with
  `target/generated-sources/sqlcj`.

Generation writes one file per configured entry:

```text
target/generated-sources/sqlcj/com/example/app/db/AuthorRepository.java
```

`mvn exec:java` prints:

```text
created: 1 Ada Lovelace
read: Ada Lovelace / First programmer
updated rows: 1
listed: 1 Ada Lovelace
missing row: false
missing row rejected: Query 'GetAuthor' in AuthorRepository returned no row; expected exactly one
committed: Compiler pioneer
rolled back: false
searched: 1 Ada Lovelace
count: 2
page: 2 Grace Hopper
created book rows: 1
book: Ada Lovelace / null
book: Grace Hopper / The Education of a Computer
deleted rows: 1
```

That output is the whole MVP contract in one run:

- One `AuthorRepository` instance answers every call, and each generated method
  keeps the types and order of its named query.
- `CreateAuthor` is a `:one` write whose `RETURNING` clause reads back the
  database-generated `BIGSERIAL` identifier as a typed `Long`.
- `GetAuthor` is a `:one` read: it returns the row when exactly one matches, and
  fails with `dev.sqlcj.runtime.QueryCardinalityException` naming the query and
  the repository when none matches or several do.
- `FindAuthor` is an `:optional` read of the same row, and returns
  `Optional.empty()` when no row matches, which is how the sample checks for a
  missing, rolled back, or deleted author.
- `ListAuthors` is a `:many` read, and returns an empty list when no row
  matches.
- `SearchAuthors` filters with `name ILIKE $1`, so the pattern is a `String`
  parameter carrying its own `%` wildcards, and PostgreSQL matches it without
  regard to case.
- `CountAuthors` projects an aliased `COUNT(*)` as its only result item, so
  `countAuthors()` returns a `CountAuthorsResult` whose `total` is a non-null
  `Long`.
- `ListAuthorPage` pages with `LIMIT $1 OFFSET $2`, which generates
  `listAuthorPage(Integer limit, Integer offset)`, so asking for one row after
  the first returns the second author alone.
- `ListAuthorBooks` selects one column from each side of a `LEFT JOIN`, so it
  gets its own `ListAuthorBooksResult` record and reads `title` as `null` for
  the author with no book, even though `books.title` is declared `NOT NULL`.
- `UpdateAuthorBio`, `DeleteAuthor`, and `CreateBook` are `:exec` writes and
  return their affected-row counts. `UpdateAuthorBio` also shows that parameter
  order and binding order are different things: `$1` is the first method
  parameter even though `$2` occurs first in the SQL text. `CreateBook` writes
  the schema's other table, so the methods of one query group may span every
  table of its schema.
- The two `try` blocks run generated operations on a caller-owned `Connection`
  with auto-commit disabled. The runtime never closes, commits, rolls back, or
  reconfigures that connection, so the application's own `commit` makes both
  writes durable and its own `rollback` discards them.

## 11. Re-running after a SQL change

After editing `sql/schema.sql` or `sql/queries.sql`, repeat the same order:

```bash
mvn clean
java -jar tools/sqlcj-cli-0.1.0-SNAPSHOT.jar generate
mvn compile
```

If a query is invalid, `sqlcj generate` prints one diagnostic naming the source,
the query, and the line, and exits with status `1`:

```text
sqlcj: Invalid query 'CountAuthorBios' in /home/dev/my-app/sql/queries.sql at line 57: Unsupported SELECT expression: Function
```

Every configured source is compiled before any file is written, so a diagnostic
like this writes no new file and leaves the previous output in place.

## 12. Clean up

```bash
docker rm -f sqlcj-quickstart
```

## Next steps

- [Queries](queries.md) — annotations, supported SQL shapes, parameters, and
  the generated API.
- [Configuration](configuration.md) — `sqlcj.yaml`, multiple source entries, and
  generated naming.
- [PostgreSQL Support](postgresql.md) — column types, nulls, and the runtime
  connection contract.
