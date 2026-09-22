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
  - schema: schema.sql
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
| `sql[].schema` | string | Path to a file containing `CREATE TABLE` statements. |
| `sql[].queries` | string | Path to a file containing named queries. |
| `java` | mapping | Java generation settings. |
| `java.package` | string | Package of the generated Java classes. |
| `java.out` | string | Directory that receives the generated package directories. |

### `sql` entries

Each entry pairs one schema source with one named-query source. Entries are
loaded in declared order, each entry's queries are analyzed against that entry's
own schema, and query order inside a query file is preserved.

Schemas are not shared or merged between entries. A query can only use tables
declared in the schema file of its own entry.

Query names must be unique across all configured query sources, because each
query name becomes a generated Java class name. See
[Generated Java Names](#generated-java-names) for how a query name becomes a
class name and when two query names collide.

### `java.package`

`java.package` must be a dot-separated sequence of valid, non-keyword Java
identifiers, for example `dev.example.generated`. It is used both for the
generated `package` declaration and for the generated file layout.

A query named `GetUser` with `java.package: dev.example.generated` and
`java.out: generated` is written to:

```text
generated/dev/example/generated/GetUser.java
```

### Value rules

- String values must not be blank.
- Unknown fields are rejected.
- Wrong-typed fields are rejected, including an unquoted numeric `version`.
- A missing or unsupported `version`, a missing section, an empty `sql` list, a
  null `sql` entry, a blank value, and an invalid `java.package` are all invalid
  configuration.

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
  - schema: sql/users/schema.sql
    queries: sql/users/queries.sql
  - schema: sql/orders/schema.sql
    queries: sql/orders/queries.sql
java:
  package: dev.example.generated
  out: target/generated-sources/sqlcj
```

With the configuration above and queries `GetUser` and `ListOrders`, sqlcj
generates:

```text
target/generated-sources/sqlcj/dev/example/generated/GetUser.java
target/generated-sources/sqlcj/dev/example/generated/ListOrders.java
```

## Generated Java Names

Query names and SQL column names become Java identifiers. SQL identifier
delimiters are removed before a name is analyzed, so the quoted column
`"user id"` has the JDBC label `user id`, while executable SQL keeps the query
exactly as written.

A name that is already a valid, non-reserved Java identifier keeps its spelling:

- a query named `GetUser` generates the class `GetUser` and the method `getUser`,
- a query named `getUser` generates the class `getUser` and the method `getUser`,
- a column named `created_at` generates the record component `created_at`.

Any other name is normalized deterministically:

- each maximal run of characters that cannot appear in a Java identifier becomes
  a single `_`, so `Get-User` and `Get*/User` both generate the class `Get_User`,
- a leading `_` is added when the first character cannot start an identifier, so
  `1stQuery` generates the class `_1stQuery`,
- a trailing `_` is added to a Java keyword, to `true`, `false`, `null`, and to
  `_`, so a column named `class` generates the component `class_`.

Generated names also avoid names that the generated source already uses:

- a class name never repeats an imported or generated type name such as `List`,
  `String`, `RowMapper`, or `QueryExecutor`, and never uses a restricted type
  identifier such as `record` or `var`; such a name gets a trailing `_`, so a
  query named `List` generates the class `List_`,
- the generated method name keeps the lower-initial rule and avoids Java
  keywords and inherited `Object` method names, so a query named `Class`
  generates the class `Class` with the method `class_`,
- record components avoid inherited `Object` method names, and method parameters
  avoid the generator-owned names `executor` and `ROW_MAPPER`.

Method parameters and record components are disambiguated inside their own
generated class, in logical parameter order and selected-column order, using the
suffixes `1`, `2`, and so on. Two parameters resolved from the column `id`
become `id1` and `id2`, and the columns `user id` and `user-id` become the
components `user_id1` and `user_id2`.

The generated row mapper reads each result column by its one-based position in
the selected-column list, so renaming a component never changes which column it
reads, and identically named columns selected from different query sources stay
distinct.

### Generated path collisions

Two queries whose generated class names resolve to the same file path, or to
paths that differ only by case and are therefore not portable, are rejected
before any file of the run is written, so existing output is not overwritten:

```text
sqlcj: Duplicate generated file for queries 'Get.User' and 'Get-User': generated/Get_User.java
sqlcj: Generated file paths for queries 'GetUser' and 'getuser' differ only by case: generated/GetUser.java and generated/getuser.java
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
so a renamed or deleted query leaves its previous class behind. Generate into a
build-owned directory and let the build's clean step remove stale output, as the
[Quickstart](quickstart.md) does.
