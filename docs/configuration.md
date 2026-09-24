# Configuration

sqlcj is configured by a single YAML file named `sqlcj.yaml`.

For a project that uses this file end to end, see the
[Quickstart](quickstart.md). For the query annotations, supported SQL, and
generated API, see [Queries](queries.md).

The `generate` command reads `sqlcj.yaml` from the directory it is run in:

```bash
sqlcj generate
```

There is no configuration-path option and no configuration discovery in parent
directories.

## Supported Version

Only configuration version `"1"` is supported. Every field below is required;
none of them has a default value.

Version `"1"` commits sqlcj to PostgreSQL schema and query input and to
blocking JDBC execution; there is no engine or dialect option. See
[PostgreSQL Support](postgresql.md) for the accepted column types, the accepted
`CREATE TABLE` constructs, and the null contract.

```yaml
version: "1"
sql:
  - name: Author
    schema: schema.sql
    queries: queries.sql
java:
  package: dev.example.generated
  out: generated
```

## Fields

| Field | Type | Description |
| --- | --- | --- |
| `version` | string | Configuration contract version. Must be `"1"`. |
| `sql` | list | Non-empty, ordered list of source entries. |
| `sql[].name` | string | Identity of the query group. Names the generated repository. |
| `sql[].schema` | string | Path to a file containing `CREATE TABLE` statements. |
| `sql[].queries` | string | Path to a file containing named queries. |
| `java` | mapping | Java generation settings. |
| `java.package` | string | Package of the generated Java classes. |
| `java.out` | string | Directory that receives the generated package directories. |

### `sql` entries

Each entry pairs one group name with one schema source and one named-query
source. Entries are loaded in declared order, each entry's queries are analyzed
against that entry's own schema, and query order inside a query file is
preserved.

Schemas are not shared or merged between entries. A query can only use tables
declared in the schema file of its own entry.

One entry generates exactly one repository containing every query of its query
source, in declared query order. Query names are therefore scoped to their
entry: two entries may use the same query name, while two queries of one entry
that generate the same method name are rejected. See
[Generated Java Names](#generated-java-names) for the naming rules and the
collisions that end a run.

### `sql[].name`

`sql[].name` is required and is used unchanged as the prefix of the generated
repository type name, so the entry `name: Author` generates `AuthorRepository`.

It must be a single valid, non-blank Java identifier. A Java keyword, the
literals `true`, `false`, and `null`, the identifier `_`, and a restricted
identifier such as `var` or `record` are rejected:

```text
sqlcj: Invalid configuration in /home/dev/project/sqlcj.yaml: 'sql[0].name' value 'record' is not a valid Java identifier for a generated repository name
```

sqlcj does not derive the name from a table or a file name. A query file may
join or write several tables, so the group boundary is declared, not guessed.

### `java.package`

`java.package` must be a dot-separated sequence of valid, non-keyword Java
identifiers, for example `dev.example.generated`. It is used both for the
generated `package` declaration and for the generated file layout.

An entry named `Author` with `java.package: dev.example.generated` and
`java.out: generated` is written to:

```text
generated/dev/example/generated/AuthorRepository.java
```

### Value rules

- String values must not be blank.
- Unknown fields are rejected.
- Wrong-typed fields are rejected, including an unquoted numeric `version`.
- A missing or unsupported `version`, a missing section, an empty `sql` list, a
  null `sql` entry, a blank value, an invalid `sql[].name`, and an invalid
  `java.package` are all invalid configuration.

## Path Resolution

A relative `schema`, `queries`, or `java.out` path is resolved against the
directory that contains `sqlcj.yaml`, not against the process working directory
at a later point in time. An absolute path is used as-is.

Resolved paths are normalized lexically, so `../sql/schema.sql` is supported.
Resolution does not require `java.out` to exist, does not expand `~`,
environment variables, or globs, and does not follow symbolic links to a
canonical location.

## Multiple Entries

```yaml
version: "1"
sql:
  - name: User
    schema: sql/users/schema.sql
    queries: sql/users/queries.sql
  - name: Order
    schema: sql/orders/schema.sql
    queries: sql/orders/queries.sql
java:
  package: dev.example.generated
  out: target/generated-sources/sqlcj
```

With the configuration above, sqlcj generates one repository per entry:

```text
target/generated-sources/sqlcj/dev/example/generated/UserRepository.java
target/generated-sources/sqlcj/dev/example/generated/OrderRepository.java
```

Both repositories may contain a query named `GetById`, because each name is
resolved inside its own repository.

## Generated Java Names

The configured `sql[].name` and the SQL names inside the entry become Java
identifiers. SQL identifier delimiters are removed before a name is analyzed, so
the quoted column `"user id"` has the JDBC label `user id`, while executable SQL
keeps the query exactly as written.

The repository name is the configured `sql[].name` followed by `Repository`,
without normalization, because configuration already requires a valid Java
identifier.

A query or column name that is already a valid, non-reserved Java identifier
keeps its spelling:

- a query named `GetUser` generates the result record `GetUserResult` and the
  method `getUser`,
- a column named `created_at` generates the record component `created_at`.

Any other name is normalized deterministically:

- each maximal run of characters that cannot appear in a Java identifier becomes
  a single `_`, so `Get-User` and `Get*/User` both generate the result record
  `Get_UserResult`,
- a leading `_` is added when the first character cannot start an identifier, so
  `1stQuery` generates `_1stQueryResult`,
- a trailing `_` is added to a Java keyword, to `true`, `false`, `null`, and to
  `_`, so a column named `class` generates the component `class_`.

Generated names also avoid names that the generated source already uses:

- the generated method name keeps the lower-initial rule and avoids Java
  keywords and inherited `Object` method names, so a query named `Class`
  generates the record `ClassResult` and the method `class_`,
- record components avoid inherited `Object` method names,
- method parameters avoid the generator-owned name `executor` and the row-mapper
  field names of the repository, which are the method name followed by
  `RowMapper`.

Method parameters and record components are disambiguated inside their own
generated method or record, in logical parameter order and selected-column
order, using the suffixes `1`, `2`, and so on. Two parameters resolved from the
column `id` become `id1` and `id2`, and the columns `user id` and `user-id`
become the components `user_id1` and `user_id2`.

The generated row mapper reads each result column by its one-based position in
the selected-column list, so renaming a component never changes which column it
reads, and identically named columns selected from different query sources stay
distinct.

### Repository method collisions

Two queries of one entry that generate the same method name are rejected instead
of being renamed, because a repository method is a name the application calls.
The diagnostic names both queries:

```text
sqlcj: Invalid query group 'User' in /home/dev/project/sql/queries.sql: Queries 'Get.User' and 'Get-User' generate the same repository method 'get_User'
```

Two queries of one entry whose nested result types differ only by case are
rejected for the same reason, because those class files are one path on a
case-insensitive filesystem:

```text
sqlcj: Invalid query group 'User' in /home/dev/project/sql/queries.sql: Queries 'GetUser' and 'getuser' generate result types that differ only by case: GetUserResult and getuserResult
```

### Generated path collisions

Two entries whose repository files resolve to the same path, or to paths that
differ only by case and are therefore not portable, are rejected before any file
of the run is written, so existing output is not overwritten:

```text
sqlcj: Duplicate generated file for repositories 'User' and 'User': generated/UserRepository.java
sqlcj: Generated file paths for repositories 'User' and 'user' differ only by case: generated/UserRepository.java and generated/userRepository.java
```

## Diagnostics

Invalid configuration and unreadable SQL sources make `sqlcj generate` print a
single message on standard error and exit with a non-zero status. The message
names the configuration file and, when available, the offending field, value, or
source path:

```text
sqlcj: Invalid configuration in /home/dev/project/sqlcj.yaml: 'java.package' value 'dev.class.generated' is not a valid Java package name
sqlcj: Cannot read queries source: /home/dev/project/sql/missing.sql
```

## Generated Output and Failures

Every configured entry is loaded, analyzed, and generated before the run writes
its first file. A configuration, source, schema, query, or generated-path
failure therefore ends the run without writing any file, and output from an
earlier successful run is left unchanged.

Writing itself is not transactional. The files of a successful compilation are
written one after another, each created or truncated in place, so a filesystem
failure part-way through can leave a mixture of newly written files and files
from a previous run. sqlcj does not remove or roll back files it has already
written.

sqlcj also never deletes a generated file that the current run did not produce,
so a renamed or removed configuration entry leaves its previous repository
behind. Generate into a
build-owned directory and let the build's clean step remove stale output, as the
[Quickstart](quickstart.md) does.
