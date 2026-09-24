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

`sql[].name` is required and names the generated repository type: the entry is
converted to upper camel case and followed by `Repository`, so `name: Author`
generates `AuthorRepository` and `name: author_admin` generates
`AuthorAdminRepository`.

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

The configured `sql[].name` and the SQL names inside the entry — query names,
table names, column names, and projection aliases — become conventional Java
identifiers.
SQL identifier delimiters are removed before a name is converted, so the quoted
column `"user id"` has the JDBC label `user id` and generates the component
`userId`, while executable SQL keeps the query exactly as written.

Every name is derived from its SQL spelling by one deterministic rule set that
uses no locale-dependent case mapping and no configuration:

1. The SQL name is split into words at every character that is not a letter or
   a digit, so `get_author`, `Get-User`, and `user id` have two words each.
   There is no split inside a word, so `GetAuthor` and `HTTPStatus` are one
   word.
2. A word written without a lower-case letter is lower-cased, so the acronyms
   `ID` and `URL` become the words `id` and `url`. Every other word keeps its
   spelling.
3. A type name is upper camel case: the first character of each word is
   upper-cased and the words are joined. `authors` becomes `Authors`,
   `get_author` and `GetAuthor` both become `GetAuthor`, and `user_ID` becomes
   `UserId`.
4. A method, record component, or parameter name is lower camel case: the upper
   camel form's leading run of upper-case letters is lower-cased. A run of two
   or more letters that is followed by a lower-case letter keeps its last letter
   upper-case, because that letter starts the next word. So `created_at` becomes
   `createdAt`, `GetAuthor` becomes `getAuthor`, `HTTPStatus` becomes
   `httpStatus`, `HTTP2Status` becomes `http2Status`, and `GetHTTPStatus`
   becomes `getHTTPStatus`.
5. A name that would start with a digit is prefixed with `_`, so the query
   `1st_query` generates the result record `_1stQueryResult`.
6. A SQL name with no letter and no digit at all, such as `_`, `***`, or `$`,
   has no Java name and ends the run:

   ```text
   sqlcj: Invalid query group 'User' in /home/dev/project/sql/queries.sql: SQL name '***' of query 'ListUsers' has no letter or digit to generate a Java name from
   ```

The rules are applied as follows:

- the repository type is the upper camel form of `sql[].name` followed by
  `Repository`, so `author_admin` generates `AuthorAdminRepository`,
- a result record is the upper camel form of the query name followed by
  `Result`, so `get_author` generates `GetAuthorResult`,
- a row record is the upper camel form of the table name followed by `Row`, so
  the table `authors` generates `AuthorsRow`. Row names are normalized, never
  singularized,
- a method is the lower camel form of the query name, so `get_author` generates
  `getAuthor`,
- a row-mapper field is the method name followed by `RowMapper`, and the mapper
  of a row record is the lower camel form of the row type followed by `Mapper`,
  so `AuthorsRow` generates `authorsRowMapper`,
- record components and method parameters are the lower camel form of the column
  name or projection alias, so `created_at` generates `createdAt`.

Generated names also avoid names that Java or the generated source already uses:

- a method, record component, or parameter that would be a Java keyword or one
  of the literals `true`, `false`, and `null` is suffixed with `_`, so a query
  named `Class` generates the record `ClassResult` and the method `class_`, and
  a column named `class` generates the component `class_`,
- a method name also avoids the inherited `Object` method names, so a query
  named `ToString` generates the method `toString_`,
- record components avoid inherited `Object` method names,
- method parameters avoid the generator-owned name `executor` and every
  row-mapper field name of the repository, including the mappers of its row
  records,
- a row record's mapper field yields to the mapper of a query that generates its
  own, using the same numeric suffixes, so a query named `Authors` keeps
  `authorsRowMapper` while the row record of the table `authors` uses
  `authorsRowMapper1`.

Method parameters and record components are disambiguated inside their own
generated method or record, in logical parameter order and selected-column
order, using the suffixes `1`, `2`, and so on. Two parameters resolved from the
column `id` become `id1` and `id2`; the columns `user id` and `user-id` both
convert to `userId` and become the components `userId1` and `userId2`; and a
component that would be an inherited `Object` method name, such as `hashCode`,
becomes `hashCode1`.

A projection alias is the name of its result component, so
`SELECT u.id AS user_id, p.id AS profile_id` generates the components `userId`
and `profileId` instead of two disambiguated `id` components.

The generated row mapper reads each result column by its one-based position in
the selected-column list, so renaming a component never changes which column it
reads, and identically named columns selected from different query sources stay
distinct.

### Repository method collisions

Two queries of one entry that generate the same method name are rejected instead
of being renamed, because a repository method is a name the application calls.
The diagnostic names both queries:

```text
sqlcj: Invalid query group 'User' in /home/dev/project/sql/queries.sql: Queries 'get_author' and 'GetAuthor' generate the same repository method 'getAuthor'
```

Two queries of one entry whose nested result types differ only by case are
rejected for the same reason, because those class files are one path on a
case-insensitive filesystem:

```text
sqlcj: Invalid query group 'User' in /home/dev/project/sql/queries.sql: Queries 'GetUser' and 'getuser' generate result types that differ only by case: GetUserResult and GetuserResult
```

Two tables of one entry whose row records are equal ignoring case are rejected
on the same grounds, naming both tables and both row types:

```text
sqlcj: Invalid query group 'User' in /home/dev/project/sql/queries.sql: Tables 'user_data' and 'userdata' generate row types that are equal ignoring case: UserDataRow and UserdataRow
```

### Generated path collisions

Two entries whose repository files resolve to the same path, or to paths that
differ only by case and are therefore not portable, are rejected before any file
of the run is written, so existing output is not overwritten. Two entry names
that differ only by the case of their first character, such as `User` and
`user`, generate one repository name and collide as the same path:

```text
sqlcj: Duplicate generated file for repositories 'User' and 'user': generated/UserRepository.java
sqlcj: Generated file paths for repositories 'UserData' and 'Userdata' differ only by case: generated/UserDataRepository.java and generated/UserdataRepository.java
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
