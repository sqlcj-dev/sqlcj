# Quickstart

This walkthrough builds a small Maven application that reads and writes a
PostgreSQL table through sqlcj-generated Java. Every file is listed in full, so
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
  - schema: sql/schema.sql
    queries: sql/queries.sql
java:
  package: com.example.app.db
  out: target/generated-sources/sqlcj
```

`sqlcj generate` reads `sqlcj.yaml` from the directory it is run in, and the
relative paths above are resolved against the directory that contains the file.
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
```

The accepted column types and `CREATE TABLE` constructs are listed in
[PostgreSQL Support](postgresql.md).

## 7. `sql/queries.sql`

```sql
-- name: CreateAuthor :one
INSERT INTO authors (name, bio)
VALUES ($1, $2)
RETURNING id, name, bio;

-- name: GetAuthor :one
SELECT id, name, bio
FROM authors
WHERE id = $1;

-- name: ListAuthors :many
SELECT id, name
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
```

Each query generates one class named after it: `CreateAuthor`, `GetAuthor`,
`ListAuthors`, `UpdateAuthorBio`, and `DeleteAuthor`. A `:one` or `:many` query
also generates a nested result record such as `GetAuthor.GetAuthorResult`. The
full query contract is documented in [Queries](queries.md).

## 8. `src/main/java/com/example/app/App.java`

```java
package com.example.app;

import com.example.app.db.CreateAuthor;
import com.example.app.db.DeleteAuthor;
import com.example.app.db.GetAuthor;
import com.example.app.db.ListAuthors;
import com.example.app.db.UpdateAuthorBio;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryExecutor;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class App {

    public static void main(String[] args) throws SQLException {
        DataSource dataSource = dataSource();

        QueryExecutor executor = new JdbcQueryExecutor(dataSource);

        CreateAuthor.CreateAuthorResult created = new CreateAuthor(executor)
                .createAuthor("Ada Lovelace", "First programmer");

        System.out.println("created: " + created.id() + " " + created.name());

        GetAuthor.GetAuthorResult read = new GetAuthor(executor).getAuthor(created.id());

        System.out.println("read: " + read.name() + " / " + read.bio());

        int updatedRows = new UpdateAuthorBio(executor)
                .updateAuthorBio(created.id(), "Mathematician");

        System.out.println("updated rows: " + updatedRows);

        for (ListAuthors.ListAuthorsResult author : new ListAuthors(executor).listAuthors()) {
            System.out.println("listed: " + author.id() + " " + author.name());
        }

        System.out.println("missing row: " + new GetAuthor(executor).getAuthor(-1L));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            QueryExecutor transactional = new JdbcQueryExecutor(connection);

            CreateAuthor.CreateAuthorResult committed = new CreateAuthor(transactional)
                    .createAuthor("Grace Hopper", null);

            new UpdateAuthorBio(transactional).updateAuthorBio(committed.id(), "Compiler pioneer");

            connection.commit();

            System.out.println(
                    "committed: " + new GetAuthor(executor).getAuthor(committed.id()).bio()
            );
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            QueryExecutor transactional = new JdbcQueryExecutor(connection);

            CreateAuthor.CreateAuthorResult discarded = new CreateAuthor(transactional)
                    .createAuthor("Temporary Author", null);

            new UpdateAuthorBio(transactional).updateAuthorBio(discarded.id(), "never stored");

            connection.rollback();

            System.out.println(
                    "rolled back: " + new GetAuthor(executor).getAuthor(discarded.id())
            );
        }

        System.out.println("deleted rows: " + new DeleteAuthor(executor).deleteAuthor(created.id()));
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

- `mvn clean` deletes `target`, including previously generated sources. sqlcj
  writes and overwrites its own files but never deletes a file it did not just
  generate, so a query that was renamed or removed would otherwise leave a stale
  class behind that still compiles.
- `sqlcj generate` is a separate command. It is not bound to the Maven
  lifecycle, so it must run after `clean` and before `compile`. It must run from
  the project root, because it reads `sqlcj.yaml` from the current directory.
- `mvn compile` then compiles `src/main/java` together with
  `target/generated-sources/sqlcj`.

Generation writes one file per query:

```text
target/generated-sources/sqlcj/com/example/app/db/CreateAuthor.java
target/generated-sources/sqlcj/com/example/app/db/DeleteAuthor.java
target/generated-sources/sqlcj/com/example/app/db/GetAuthor.java
target/generated-sources/sqlcj/com/example/app/db/ListAuthors.java
target/generated-sources/sqlcj/com/example/app/db/UpdateAuthorBio.java
```

`mvn exec:java` prints:

```text
created: 1 Ada Lovelace
read: Ada Lovelace / First programmer
updated rows: 1
listed: 1 Ada Lovelace
missing row: null
committed: Compiler pioneer
rolled back: null
deleted rows: 1
```

That output is the whole MVP contract in one run:

- `CreateAuthor` is a `:one` write whose `RETURNING` clause reads back the
  database-generated `BIGSERIAL` identifier as a typed `Long`.
- `GetAuthor` is a `:one` read, and returns `null` when no row matches.
- `ListAuthors` is a `:many` read, and returns an empty list when no row
  matches.
- `UpdateAuthorBio` and `DeleteAuthor` are `:exec` writes and return their
  affected-row counts. `UpdateAuthorBio` also shows that parameter order and
  binding order are different things: `$1` is the first method parameter even
  though `$2` occurs first in the SQL text.
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
sqlcj: Invalid query 'CountAuthors' in /home/dev/my-app/sql/queries.sql at line 26: Unsupported SELECT expression: Function
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
