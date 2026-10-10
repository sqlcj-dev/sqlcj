# Queries

This document defines the query contract of sqlcj: the named-query file format,
the accepted SQL shapes, how parameters are ordered and bound, and the Java that
each query generates.

Related documents:

- [Configuration](configuration.md) owns `sqlcj.yaml`, path resolution, and the
  rules that turn a SQL name into a Java name.
- [PostgreSQL Support](postgresql.md) owns the schema snapshot, the column
  types, the null contract, and the runtime connection contract.
- [Quickstart](quickstart.md) runs the whole path end to end.

## Query Sources

A `sql[].queries` file is a plain SQL file in which every statement is preceded
by a sqlcj header:

```sql
-- name: GetAuthor :one
SELECT id, name, bio
FROM authors
WHERE id = $1;
```

- A header line starts with `-- name:` and contains exactly the query name and
  the query annotation, separated by whitespace.
- A query owns every following line until the next header or the end of the
  file. The statement's trailing `;` is part of the query.
- Query order inside a file is preserved, and it is the order of the methods of
  the generated repository.
- A query name must be unique inside its own query source. Two configuration
  entries may use the same query name, because each entry generates its own
  repository.
- An unparsable header, an unknown annotation, a duplicate name, and a header
  without SQL are all rejected.

## Annotations and Cardinality

sqlcj accepts four annotations. Any other annotation is rejected.

| Annotation | Accepted statements | Generated return type | Result when no row matches | Result when several rows match |
| --- | --- | --- | --- | --- |
| `:one` | `SELECT`, or a write with `RETURNING` | the generated result record | `QueryCardinalityException` | `QueryCardinalityException` |
| `:optional` | `SELECT`, or a write with `RETURNING` | `Optional<`result record`>` | `Optional.empty()` | `QueryCardinalityException` |
| `:many` | `SELECT`, or a write with `RETURNING` | `List<`result record`>` | an empty list | every row |
| `:exec` | `INSERT`, `UPDATE`, `DELETE` without `RETURNING` | `int` affected-row count | `0` | the affected-row count |

The annotation and the statement must agree:

- a `SELECT` must be `:one`, `:optional`, or `:many`,
- a write without `RETURNING` must be `:exec`,
- a write with `RETURNING` must be `:one`, `:optional`, or `:many`.

The three result annotations differ only in how many rows they accept:

- `:one` returns exactly one row. It raises
  `dev.sqlcj.runtime.QueryCardinalityException` when the query returned no row
  and when it returned more than one.
- `:optional` returns at most one row, as `Optional.empty()` or
  `Optional.of(row)`. It raises the same exception when the query returned more
  than one row.
- `:many` returns every row in the order the database produced it, and an empty
  list when there was none.

A cardinality check runs after the statement has executed, so a returning write
that fails its check has already changed the database. The application controls
whether that change is kept, by running the write on a caller-owned connection
and rolling back. The exception messages are listed in
[PostgreSQL Support](postgresql.md#connection-ownership-and-transactions).

## Reads

A `SELECT` query is analyzed against the schema snapshot of its own
configuration entry.

### Sources

- The `FROM` item must be a table of that schema, optionally with an alias.
- sqlcj models one namespace, so a source resolves by its unqualified name:
  `users`, `public.users`, and `"public"."users"` are one table, matched without
  SQL identifier delimiters and case-insensitively. A source qualified with any
  other schema names a table sqlcj leaves unmodeled and is rejected as
  `Table not found in schema: <schema>.<t>`, in a `FROM`, a `JOIN`, and the
  target of an `INSERT`, an `UPDATE`, and a `DELETE` alike. `search_path` is
  not followed; see
  [Schema Snapshot Input](postgresql.md#schema-snapshot-input).
- A table may be joined with `JOIN`, `INNER JOIN`, `LEFT JOIN`, or
  `LEFT OUTER JOIN`, and inner and left joins may be chained in any order. A
  comma-separated source list and every other join modifier — `RIGHT`, `FULL`, a
  bare `OUTER`, `CROSS`, `NATURAL`, `SEMI`, `APPLY`, `STRAIGHT`, `GLOBAL`, a
  join hint, and `USING (...)` — are rejected.
- Each join requires exactly one `ON` equality between a qualified column of the
  joined source and a qualified column of a source introduced earlier. A left
  join uses the same rule as an inner join.
- Every column read from a left-joined source is nullable, even when the schema
  declares it `NOT NULL`, because an unmatched row reads it as `NULL`. The base
  source and every inner-joined source keep their schema nullability.
- An alias replaces the table name as the exposed source name, so a qualified
  reference to an aliased table must use the alias and not the table name.
- Two sources may not expose the same name.

### Projections

- A direct column of a source is selected by its own name or qualified with the
  exposed source name.
- `*` expands across the sources in their declared order, each source in its
  schema column order.
- `qualifier.*` expands one source in its schema column order.
- Selected-column order is the order of the generated result record and of the
  positional row mapper.
- An unqualified column must be found in exactly one source; an ambiguous or
  unknown column is rejected.
- A result expression that is not a direct column, a wildcard, the scalar count
  below, or the cast projection below — such as another function call, an
  arithmetic expression, or a literal — is rejected.
- An explicit alias names the result column, so `SELECT id AS author_id`
  generates the record component `authorId` while the component's type still
  comes from the column and its nullability from the column and its source.
  sqlcj itself resolves an alias only in the projection; every other clause
  reaches the database as written.

A read may count its matching rows with an aliased `COUNT(*)`:

```sql
-- name: CountAuthors :one
SELECT COUNT(*) AS total
FROM authors;

-- name: CountAuthorsByCountry :many
SELECT country, COUNT(*) AS total
FROM authors
GROUP BY country
ORDER BY country;
```

- The alias is required and may be written with or without `AS`, so both
  `COUNT(*) AS total` and `COUNT(*) total` generate the result record
  `CountAuthorsResult` with the single component `total`.
- The component is a non-null `Long`: the count is typed `BIGINT` because
  `count(*)` returns `bigint`, and it is never null because a count is `0` when
  no row matches.
- The count is one result column in its own position, so it may stand beside
  direct columns, a wildcard, a cast projection, or another count, in either
  order. `GROUP BY` itself is not analyzed and reaches the database as written,
  so PostgreSQL rather than sqlcj checks that the grouping is valid.
- The count is accepted over any supported source, predicate, ordering, and
  pagination shape, and under any annotation.
- `COUNT(*)` without an alias is rejected with
  `COUNT(*) requires a result alias, such as COUNT(*) AS total.`
- Every other count is still an unsupported result expression, including
  `COUNT(column)`, `COUNT(DISTINCT column)`, `COUNT(t.*)`, a qualified
  `pg_catalog.count(*)`, and the `FILTER` and `OVER` forms.

A read may also project a computed value by stating its type in a cast:

```sql
-- name: SumRoyaltiesByAuthor :one
SELECT SUM(amount)::numeric AS total
FROM royalties
WHERE author_id = $1;
```

- Both cast spellings are the same projection, so
  `SUM(amount)::numeric AS total` and `CAST(SUM(amount) AS numeric) AS total`
  are equivalent.
- The alias is required and may be written with or without `AS`, so
  `SUM(amount)::numeric total` is equivalent as well. A cast projection without
  an alias is rejected with
  `A cast projection requires a result alias, such as SUM(amount)::numeric AS total.`
- The result column takes the type the cast states, mapped exactly as a schema
  column's declared type is, so a cast may name a declared enum type or a
  one-dimensional array such as `::text[]`. A cast type sqlcj does not map is
  rejected with
  `Result column 'total' has unsupported cast type INTERVAL`, naming the result
  column and the declared type.
- The component is always nullable, because the cast operand is not analyzed
  and so whether it can read as `NULL` is unknown at compile time.
- The operand itself is not analyzed and reaches the database as written, so
  PostgreSQL rather than sqlcj checks its column references and its functions.
  A placeholder in a projection is still rejected, including a cast placeholder
  such as `$1::int AS x`.

### Predicates

A `WHERE` clause may combine:

- the comparisons `=`, `<>`, `>`, `>=`, `<`, `<=` between a direct column and a
  `$N` placeholder, in either order,
- `AND`, `OR`, and parentheses,
- `IN` with a fixed list of `$N` placeholders, such as `id IN ($1, $2)`,
- `LIKE` and `ILIKE` between a direct column and a `$N` pattern placeholder,
  such as `name LIKE $1`,
- `IS NULL` and `IS NOT NULL` on a direct column, such as `bio IS NULL`,
- `BETWEEN` and `NOT BETWEEN` on a direct column, such as
  `code BETWEEN $1 AND $2`,
- `= ANY` between a direct column and one `$N` list placeholder, such as
  `id = ANY($1)`.

A `LIKE` or `ILIKE` pattern placeholder requires a `VARCHAR` or `TEXT` column
and takes that column's type, so it is a `String` method parameter. sqlcj passes
the pattern through unchanged, so the caller supplies the `%` and `_` wildcards
in the argument, as in `"Al%"`. A negated `NOT LIKE` or `NOT ILIKE`, another
keyword such as `SIMILAR TO`, an `ESCAPE` clause, a `BINARY` modifier, a
non-text tested column, and a placeholder as the tested value are rejected. A
placeholder inside a computed pattern, such as `'%' || $1 || '%'`, is rejected
as an unanalyzed placeholder location.

`IS NULL` and `IS NOT NULL` bind no placeholder, but their column is resolved
against the query sources, so an unknown, ambiguous, or badly qualified column
is rejected.

A `BETWEEN` or `NOT BETWEEN` bound that is a `$N` placeholder takes the tested
column's type, so `code BETWEEN $1 AND $2` binds two `Integer` parameters named
after `code` and disambiguated as `code1` and `code2`. The bounds are bound in
textual order, the start bound before the end bound, whatever their placeholder
indexes are. A placeholder bound beside a literal bound, as in
`code BETWEEN $1 AND 10`, is typed the same way and binds one parameter. A named
bound such as `code BETWEEN :lo AND :hi` is typed the same way and named after
its placeholder, while a placeholder as the tested value, as in
`$1 BETWEEN code AND code`, and a placeholder inside a computed bound, as in
`code BETWEEN $1 + 1 AND $2`, are rejected as unanalyzed placeholder locations.

`<column> = ANY(<placeholder>)` is the id-list read. The placeholder is one
whole list of the compared column's type, named after that column, so
`WHERE id = ANY($1)` takes a `List<Long> id` and the runtime binds the list as
one PostgreSQL array at one `?` position. A null list and an empty list contain
no value, so each matches no row. A named list keeps its own name, and one name
used by two list predicates, as in
`id = ANY(:ids) OR parent_id = ANY(:ids)`, is one parameter bound at both
positions.

The compared column must be a non-array column of a declared enum type or of a
mapped type other than `BYTEA`, `JSON`, and `JSONB`, which are the element
types sqlcj binds no array of; any other column is rejected. Only this exact
shape is analyzed: the operator is `=`, the column is its left operand, and
`ANY` is written unquoted and unqualified with exactly one placeholder
argument. `id <> ANY($1)`, `id = SOME($1)`, `ANY($1) = id`, and
`id = ANY(ARRAY[$1, $2])` are therefore rejected as unanalyzed placeholder
locations, and sqlcj expands no `IN` list of its own.

A comparison that binds no placeholder, such as `active = TRUE`, contributes no
generated parameter and reaches the database as written.

A placeholder written as the direct operand of `::type` or `CAST(... AS type)`
is typed by that cast instead of by the clause it appears in, anywhere inside
the `WHERE` clause: beside a compared column, as the tested value of `IS NULL`,
inside a concatenation, as a function argument, as an `IN` element, as a range
bound, as a pattern, or as the operand of another operator such as the array
overlap `&&`. This makes the optional filter and the computed pattern idioms
compile:

```sql
-- name: ListUsersByName :many
SELECT id, name
FROM users
WHERE (:name::text IS NULL OR name = :name)
ORDER BY id;

-- name: SearchUsers :many
SELECT id, name
FROM users
WHERE name LIKE '%' || :term::text || '%';
```

`ListUsersByName` generates `listUsersByName(String name)`, which returns every
row for a null argument, and `SearchUsers` generates
`searchUsers(String term)`. The cast itself reaches the database as written. A
cast pattern states its own type, so the pattern restrictions above apply only
to an uncast pattern: `name NOT LIKE $1::text`, `name SIMILAR TO $1::text`, and
`name LIKE $1::text ESCAPE '!'` are accepted and typed `text`, whatever the
tested column's type is. Such a pattern is named after its placeholder rather
than after the tested column, so an indexed one is `param<N>`, as in `param1`
for `$1`. A cast that is the direct argument of an analyzed `= ANY` list, as in
`id = ANY($1::bigint[])`, `id = ANY(CAST($1 AS bigint[]))`, or
`id = ANY(:ids::bigint[])`, states its own type and keeps the compared column's
name, while a cast under any other operator, such as `id <> ANY($1::bigint[])`
or `tags && $1::varchar[]`, is named `param<N>`.
[Parameters](#parameters) states the accepted cast types and the name a cast
placeholder takes.

### Ordering

`ORDER BY` over direct columns is supported for a stable list order, as in
`ORDER BY id`. sqlcj rewrites only `$N` parameter tokens; the rest of the
statement, including the ordering clause, reaches JDBC exactly as written. A
placeholder in `ORDER BY` is rejected, because it is not an analyzed parameter
location.

### Pagination

A read may page its rows with `LIMIT` and `OFFSET`. Each clause takes either a
literal value or a `$N` placeholder:

```sql
-- name: ListAuthorPage :many
SELECT id, name
FROM authors
ORDER BY id
LIMIT $1 OFFSET $2;
```

- A `LIMIT` row count placeholder is an `INTEGER` parameter named `limit`, and an
  `OFFSET` value placeholder is an `INTEGER` parameter named `offset`, so
  `ListAuthorPage` generates `listAuthorPage(Integer limit, Integer offset)`.
- The pagination parameters are bound after every `WHERE` parameter, in the
  textual order of the two clauses, so both `LIMIT $1 OFFSET $2` and
  `OFFSET $2 LIMIT $1` generate the same `(limit, offset)` method parameters
  while the second binds the offset first.
- A value that binds no placeholder contributes no parameter and reaches the
  database as written, so `LIMIT 10 OFFSET 5` binds none, while
  `LIMIT 10 OFFSET $1` and `LIMIT ALL OFFSET $1` each bind one `offset`
  parameter.
- Both values must be non-negative. sqlcj generates no validation, so PostgreSQL
  rejects a negative value when the query executes.
- A named value is named after its placeholder rather than after its clause, so
  `LIMIT :pageSize OFFSET :skip` generates
  `listAuthorPage(Integer pageSize, Integer skip)`.
- A placeholder in a computed value, as in `LIMIT $1 + 1` or
  `OFFSET $1 + 1`, in the `LIMIT a, b` form, as in `LIMIT 5, $1`, and in a
  `FETCH FIRST $1 ROWS ONLY` clause are rejected as unanalyzed placeholder
  locations.

## Writes

A write targets exactly one table of its entry's schema.

| Statement | Accepted shape |
| --- | --- |
| `INSERT` | an explicit column list and a single `VALUES` row, each value a placeholder or an expression that binds none, with an optional `ON CONFLICT` clause |
| `UPDATE` | `SET` assignments that each assign one direct column a placeholder or an expression that binds none, with an optional `WHERE` using the read predicate forms |
| `DELETE` | one target table with an optional `WHERE` using the read predicate forms |

```sql
-- name: CreateAuthor :one
INSERT INTO authors (name, bio)
VALUES ($1, $2)
RETURNING *;

-- name: UpdateAuthorBio :exec
UPDATE authors
SET bio = $2
WHERE id = $1;

-- name: DeleteAuthor :exec
DELETE
FROM authors
WHERE id = $1;
```

### Values that bind no placeholder

An `INSERT` value or an `UPDATE` assignment that contains no placeholder — such
as `DEFAULT`, a literal, `NULL`, `now()`, or `version + 1` — contributes no
generated parameter and reaches the database exactly as written. Its target
column must exist in the written table, but nothing binds it, so its type need
not be one sqlcj maps. The placeholders beside it keep their own `$N` or `:name`
numbering and textual binding order:

```sql
-- name: TouchAuthor :exec
UPDATE authors
SET bio = $2,
    updated_at = now(),
    version = version + 1
WHERE id = $1;
```

`TouchAuthor` generates `touchAuthor(Long id, String bio)` and binds `(bio, id)`,
exactly as it would without the two non-binding assignments. A write whose every
value binds no placeholder, such as
`UPDATE authors SET version = version + 1`, generates a method without
parameters.

An `INSERT` value or an `UPDATE` assignment may also compute a value from a cast
placeholder, which is typed by its cast wherever it appears inside the value:

```sql
-- name: UpdateAuthorBioOrKeep :exec
UPDATE authors
SET bio = COALESCE(:bio::text, bio)
WHERE id = :id;
```

`UpdateAuthorBioOrKeep` generates `updateAuthorBioOrKeep(String bio, Long id)`
and keeps the assignment bound before the predicate. A cast placeholder that is
the whole value, as in `SET bio = :bio::text`, is typed by its cast as well and
named after its column. A value that contains an uncast placeholder, such as
`COALESCE($2, bio)`, stays rejected.

### Upsert with `ON CONFLICT`

An `INSERT` may end with one conflict clause whose target is a parenthesized
list of plain column names of the inserted table, followed by one action:

| Action | Accepted shape |
| --- | --- |
| `DO NOTHING` | no assignment, so the statement binds only its `VALUES` row |
| `DO UPDATE SET` | assignments analyzed exactly as `UPDATE` assignments are, where a value may also be `EXCLUDED.column` |

```sql
-- name: UpsertAuthor :one
INSERT INTO authors (id, name, bio)
VALUES (:id, :name, :bio)
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    bio = :bio,
    version = authors.version + 1,
    updated_at = now()
RETURNING *;
```

`UpsertAuthor` generates `upsertAuthor(Long id, String name, String bio)` and
returns the `AuthorsRow` it inserted or updated.

A `DO UPDATE` placeholder is written after the `VALUES` placeholders, so it
binds after them: on a two-value insert,
`ON CONFLICT (id) DO UPDATE SET active = $3` binds `$1`, `$2`, and `$3` in that
order, and the repeated `:bio` above is bound at both of its positions. A
`DO UPDATE` placeholder is typed by its assigned column, or by its own cast
wherever a cast appears inside the assigned value.

`EXCLUDED.column` reads the proposed row, and that column must exist in the
inserted table. An `EXCLUDED` reference inside a larger expression is not
resolved, as no other computed write value is, so PostgreSQL checks it. sqlcj
does not match the conflict target against a unique index; PostgreSQL does.

A `DO NOTHING` action writes no row when a conflict occurs, so
`DO NOTHING ... RETURNING` returns no row on conflict and suits `:optional` or
`:many` rather than `:one`. A `DO UPDATE ... RETURNING` always returns the row
it inserted or updated.

Every other conflict form is rejected on every insert, whether `:exec` or
returning: a missing target, `ON CONFLICT ON CONSTRAINT`, a target element that
is an expression or carries a collation or an operator class, a conflict-target
predicate, and `DO UPDATE ... WHERE`.

### `RETURNING`

A supported `INSERT`, `UPDATE`, or `DELETE` declared `:one`, `:optional`, or
`:many` may end with a `RETURNING` clause that lists either:

- unaliased direct columns of the target table, in the order they are declared,
  or
- a bare `*`, which expands in the target table's schema column order.

A returning write produces the same generated record, positional row mapper, and
cardinality behavior as a read, so a database-generated `SERIAL` or `BIGSERIAL`
value is read back with its declared type. A clause that is exactly `*` returns
the target table's shared `<TableName>Row` record, while a `RETURNING` column
list keeps the query's own `<QueryName>Result` record.

An aliased or computed `RETURNING` item, a qualified `table.*`, an unknown
column, and `RETURNING` on `:exec` are rejected. Multi-row `VALUES`,
`INSERT ... SELECT`, `UPDATE ... FROM`, `DELETE ... USING`, and common table
expressions are rejected in a returning write, while an accepted `ON CONFLICT`
clause is analyzed the same way with and without `RETURNING`.

## Parameters

A query writes its parameters either as PostgreSQL `$N` placeholders or as
`:name` placeholders, and one query uses one of the two forms. The compiler
replaces each real placeholder token with a JDBC `?` and leaves every other
character of the statement byte-for-byte unchanged, so `$1` or `:id` inside a
string literal, a quoted identifier, or a comment is not a parameter.

- Placeholder indexes must be positive and contiguous from `$1`.
- A named placeholder is a colon followed directly by an unquoted name of ASCII
  letters, digits, and underscores that does not start with a digit, such as
  `:userId`. Names are compared exactly as written, so `:term` and `:Term` are
  two parameters, and a name spelled like a SQL keyword, such as `:limit`,
  `:user`, or `:year`, is an ordinary name.
- Each distinct name is one parameter, numbered by its first textual
  occurrence, and every occurrence of that name is bound at its own `?`
  position.
- A named placeholder is accepted wherever a `$N` placeholder is, and names its
  generated method parameter after itself, so `LIMIT :pageSize` generates
  `pageSize` rather than `limit`.
- The Java type of a placeholder is the type of the column it is compared with,
  assigned to, or inserted into.
- A placeholder written as the direct operand of `::type` or
  `CAST(... AS type)` is typed by that cast instead, wherever it appears inside
  a `WHERE` clause, an `INSERT` value, or an `UPDATE` assignment. The cast type
  may be any type a column may declare and sqlcj maps, including a declared
  enum name, written unquoted and matched case-insensitively, and a
  one-dimensional array, which binds a `List`. A cast type sqlcj does not map,
  such as `INTERVAL` or a multi-dimensional `INT[][]`, is rejected naming the
  placeholder and the type.
- A named cast placeholder keeps its own name. An indexed cast placeholder
  keeps the name of the column whose value it is — a compared column, the
  tested column of `IN` or `BETWEEN`, the tested column of a plain `LIKE` or
  `ILIKE` pattern, which is the shape that accepts an uncast pattern, the
  compared column of an analyzed `= ANY` list, or an inserted or assigned
  column — so `name = $1::text` names `name` as
  `name = $1` does. Every other indexed cast placeholder is named `param<N>`
  after its own index, as in `param1` for `$1`, and colliding names take the
  usual numeric suffix.
- Occurrences of one placeholder may mix a cast and an uncast location, and the
  parameter keeps the name and type of its first occurrence, so
  `(:name::text IS NULL OR name = :name)` is one `String` parameter named
  `name`.
- Mixing `$N` and `:name` placeholders in one query is rejected, as are
  anonymous `?` placeholders, a qualified name such as `:a.b`, a quoted name
  such as `:"x"`, and an `&name` placeholder.
- A placeholder in a location sqlcj does not analyze — for example `ORDER BY $1`
  or `lower(name) = :name` — is rejected rather than left unbound.

### Logical order versus textual order

The two orders are distinct and both are observable:

- **Logical order** is placeholder index order. It is the order of the generated
  method parameters: `$1` is the first method parameter, `$2` the second. In a
  named query it is first-occurrence order: the name written first is the first
  method parameter.
- **Textual order** is the order in which placeholder tokens appear in the SQL.
  It is the JDBC binding order of the generated `?` positions.

`UpdateAuthorBio` above reads `$1` in its `WHERE` clause but writes `$2` first
in its `SET` clause, so the generated method takes `(id, bio)` and binds
`(bio, id)`:

```java
public int updateAuthorBio(Long id, String bio) {
    return executor.execute(
        "AuthorRepository",
        "UpdateAuthorBio",
        """
            UPDATE authors
            SET bio = ?
            WHERE id = ?;
        """,
        java.util.Arrays.asList(bio, id)
    );
}
```

An index and a name may repeat. A repeated index produces one method parameter,
named and typed from its first occurrence, and its value is bound at every
textual position where the index occurs; a repeated name behaves the same way
and keeps its own name. Occurrences of one index or one name whose inferred Java
types differ are rejected.

`UpdateAuthorBio` written with named placeholders states the same two orders:

```sql
-- name: UpdateAuthorBio :exec
UPDATE authors
SET bio = :bio
WHERE id = :id;
```

The generated method takes `(bio, id)`, because `:bio` occurs first, and binds
`(bio, id)`.

## Generated Java

Each configuration entry generates one final repository class in the configured
`java.package`, written to the package directory under `java.out` and named
`<sql[].name>Repository`. Every named query of that entry becomes one method of
that repository; sqlcj never generates a class per query. Beside the
repositories, the package holds one `<TableName>Row` record file for each table
whose complete row an entry returns, and one Java enum file for each enum type
a query of an entry reads or binds.

- Every generated file begins with the fixed notice
  `// Code generated by sqlcj. DO NOT EDIT.`, followed by one blank line and the
  `package` declaration. The notice holds no version, timestamp, or filesystem
  path.
- The repository has one `dev.sqlcj.runtime.QueryExecutor` field and one
  constructor taking that executor.
- Methods appear in query-source order. A method name is the lower camel form
  of the query name, as in `get_author` and `GetAuthor` to `getAuthor`.
- A `:one`, `:optional`, or `:many` query that returns one complete table row — a
  single-source `SELECT *` or `SELECT <source>.*`, or a write whose `RETURNING`
  clause is exactly `*` — returns the `<TableName>Row` record. That record is a
  top-level `public record` in `java.package`, generated once per table into its
  own file and shared by every repository of the package that returns that row.
  Each repository keeps its own private `RowMapper` field for the row, generated
  after the constructor in the order its queries first use it.
- Every other `:one`, `:optional`, or `:many` query generates a nested `public record` named
  `<QueryName>Result` in upper camel case, whose components follow the
  selected-column order and are named after each column's projection alias or
  column name, plus a private `RowMapper` field that reads each column by its
  one-based position. That includes an explicit column list, even one naming
  every column, a wildcard combined with another projection item, a wildcard in
  a join, and a `RETURNING` column list.
- A `:exec` query generates no result record and returns `int`.
- Every generated method passes the repository class name and the query name to
  the executor, as the first two arguments of its call, so a runtime failure
  names the query the application called. A `JSON` or `JSONB` argument is passed
  as `new dev.sqlcj.runtime.UntypedText(<parameter>)`, written out in full, so
  that the runtime binds its text without a declared SQL type; see
  [Supported Column Types](postgresql.md#supported-column-types).
- A column or parameter of a one-dimensional array type is a
  `java.util.List<T>` of the element's Java type. Its argument is passed as
  `new dev.sqlcj.runtime.SqlArray("<element type>", <parameter>)`, or as
  `dev.sqlcj.runtime.SqlArray.of("<enum type>", <parameter>, <EnumType>::label)`
  for an array of an enum, and the column is read as
  `dev.sqlcj.runtime.SqlArray.getList(resultSet, position, <Type>.class)`, so a
  `null` list, an empty list, and a `null` element are preserved in both
  directions; see [Array Types](postgresql.md#array-types).
- A column or parameter of an enum type the schema declares uses the Java enum
  the package generates for that type, by its simple name. Its argument is
  passed as
  `new dev.sqlcj.runtime.UntypedText(<parameter> == null ? null : <parameter>.label())`,
  and the column is read as `<EnumType>.fromLabel(resultSet.getString(position))`,
  so a `null` stays `null` in both directions; see
  [Enum Types](postgresql.md#enum-types).
- Generated repository source imports only `dev.sqlcj.runtime.QueryExecutor`,
  `dev.sqlcj.runtime.RowMapper`, `java.util.List`, `java.util.Optional` when the
  entry declares an `:optional` query, and the JDK types of the mapped columns,
  so the runtime artifact is the only sqlcj dependency a consumer needs. A row
  record imports the JDK types of its components alone, which include
  `java.util.List` for an array component, and depends on no sqlcj
  type, and a generated enum imports nothing at all.

The `Author` entry of the [Quickstart](quickstart.md), which declares
`CreateAuthor`, `GetAuthor`, `FindAuthor`, `ListAuthors`, `UpdateAuthorBio`, and
`DeleteAuthor`, generates the row record of the `authors` table:

```java
// Code generated by sqlcj. DO NOT EDIT.

package com.example.app.db;

import java.time.LocalDateTime;

/**
 * Generated by sqlcj.
 *
 * Table: authors
 */
public record AuthorsRow(
    Long id,
    String name,
    String bio,
    LocalDateTime createdAt
) {
}
```

and one `AuthorRepository` that uses it:

```java
// Code generated by sqlcj. DO NOT EDIT.

package com.example.app.db;

import dev.sqlcj.runtime.QueryExecutor;
import dev.sqlcj.runtime.RowMapper;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * Generated by sqlcj.
 *
 * Repository: Author
 */
public final class AuthorRepository {

    private final QueryExecutor executor;

    public AuthorRepository(QueryExecutor executor) {
        this.executor = executor;
    }

    private static final RowMapper<AuthorsRow> authorsRowMapper =
            resultSet -> new AuthorsRow(
            resultSet.getObject(1, Long.class),
            resultSet.getObject(2, String.class),
            resultSet.getObject(3, String.class),
            resultSet.getObject(4, LocalDateTime.class)
    );

    /**
     * Query: CreateAuthor
     * Table: authors
     * Type: ONE
     */
    public AuthorsRow createAuthor(String name, String bio) {
        return executor.queryOne(
                "AuthorRepository",
                "CreateAuthor",
                """
    INSERT INTO authors (name, bio)
    VALUES (?, ?)
    RETURNING *;""",
                java.util.Arrays.asList(name, bio),
                authorsRowMapper
        );
    }

    // getAuthor follows here, in query-source order.

    /**
     * Query: FindAuthor
     * Table: authors
     * Type: OPTIONAL
     */
    public Optional<AuthorsRow> findAuthor(Long id) {
        return executor.queryOptional(
                "AuthorRepository",
                "FindAuthor",
                """
    SELECT *
    FROM authors
    WHERE id = ?;""",
                java.util.Arrays.asList(id),
                authorsRowMapper
        );
    }

    // listAuthors, updateAuthorBio, and deleteAuthor follow here.
}
```

`CreateAuthor`, `GetAuthor`, `FindAuthor`, and `ListAuthors` each return one
complete `authors` row, so all four use the one `AuthorsRow` record and the one
`authorsRowMapper` field. `getAuthor` returns `AuthorsRow`, `findAuthor` returns
`Optional<AuthorsRow>`, and `listAuthors` returns `List<AuthorsRow>`. A second
entry of the same package that returns the full `authors` row returns that same
`AuthorsRow` class.

The application constructs the repository once per execution context:

```java
AuthorRepository authors = new AuthorRepository(new JdbcQueryExecutor(dataSource));

AuthorsRow author = authors.getAuthor(1L);

Optional<AuthorsRow> found = authors.findAuthor(2L);
```

A query name, projection alias, column name, and parameter name becomes a
conventional Java name by one deterministic camel-case rule set, and names that
would collide inside one generated repository are disambiguated in their SQL
order. Those rules, the rejection of two queries of one entry that generate the
same method, the rejection of two entries that define one row record
differently, and the rejection of two entries whose repository files would
collide, are documented in
[Generated Java Names](configuration.md#generated-java-names).

The generated repositories are executed through
`dev.sqlcj.runtime.JdbcQueryExecutor` on a `DataSource` or on a caller-owned
`Connection`. Connection ownership, transaction control, and exception
translation are documented in
[Connection Ownership and Transactions](postgresql.md#connection-ownership-and-transactions).

## Unsupported Queries

The supported subset is exactly the shapes described above. sqlcj does not
validate the whole SQL language: a construct outside the subset is either
rejected with a diagnostic or, when it introduces no parameter and no result
column that sqlcj must type, carried into the executable SQL unanalyzed. Only
the documented shapes are contract, tested, and safe to rely on.

### Rejected

Rejection stops the run with a diagnostic naming the query source, the query
name, and its header line.

- A result expression that is not a direct column, a wildcard, an aliased
  `COUNT(*)`, or an aliased cast, including another function call, another
  aggregate, and a literal. A `COUNT(*)` without an alias, a cast projection
  without an alias, and a cast projection whose type sqlcj does not map each
  fail with their own diagnostic, while `COUNT(column)`,
  `COUNT(DISTINCT column)`, `COUNT(t.*)`, `pg_catalog.count(*)`,
  `lower(name) AS n`, and the `FILTER` and `OVER` forms keep the
  unsupported-expression rejection.
- A `FROM` item that is not a table, a comma-separated source list, a join that
  is not a plain inner or left join, a join predicate that is not one qualified
  equality, and a set operation such as `UNION`.
- A placeholder in a location sqlcj does not analyze, including `ORDER BY $1`, a
  named placeholder under a function such as `lower(name) = :name`, a computed
  `LIKE` pattern such as
  `'%' || $1 || '%'`, a placeholder as the
  tested value of a range such as `$1 BETWEEN id AND id`, a computed range
  bound such as `id BETWEEN $1 + 1 AND $2`, a computed pagination value such as
  `LIMIT $1 + 1` or `OFFSET $1 + 1`, a `LIMIT a, b` row count such as
  `LIMIT 5, $1`, a placeholder inside a write value such as
  `COALESCE($2, bio)`, and a `FETCH FIRST $1 ROWS ONLY` clause, so dynamic `IN`
  expansion is unavailable. A cast does not widen these locations: a cast
  placeholder in a projection, `ORDER BY`, a join condition, or a pagination
  value, such as `ORDER BY $1::int`, stays rejected, as does a placeholder that
  is not the direct operand of its cast, such as `(:x)::int`.
- A cast type sqlcj does not map, such as `$1::interval` or a
  multi-dimensional `$1::int[][]`, which is rejected naming the placeholder and
  the declared type, or the result column and the declared type when the cast
  is a projection such as `id::interval AS i`.
- A `LIKE`-family pattern placeholder that is negated, uses another keyword such
  as `SIMILAR TO`, carries an `ESCAPE` clause or a `BINARY` modifier, tests a
  non-text column, or stands as the tested value. These restrict the uncast
  pattern placeholder; a cast pattern states its own type.
- A `= ANY` list placeholder whose compared column is an array column or a
  `BYTEA`, `JSON`, or `JSONB` column, because sqlcj binds no array of those
  element types. Every form outside the analyzed shape — another operator such
  as `id <> ANY($1)`, another quantifier such as `id = SOME($1)`, a reversed
  `ANY($1) = id`, a quoted or qualified `"ANY"($1)` or `pg_catalog.any($1)`,
  a modifier such as `ANY(DISTINCT $1)`, more than one argument, and an
  argument that is not one placeholder, such as `id = ANY(ARRAY[$1, $2])` —
  keeps the unanalyzed-placeholder rejection. A
  cast does not widen them: `id <> ANY($1::bigint[])` is accepted only as any
  other cast placeholder is, named after its own index.
- Anonymous `?` placeholders, `$N` and `:name` placeholders mixed in one query,
  a qualified name such as `:a.b`, a quoted name such as `:"x"`, an `&name`
  placeholder, each of them also as the operand of a cast, such as
  `:"x"::text`, `&x::text`, or `:a.b::text`, one name whose occurrences have
  conflicting types, and non-contiguous or non-positive placeholder indexes.
- An `INSERT` without an explicit column list, with more than one `VALUES` row,
  or built from a `SELECT`; an `UPDATE` assignment that sets a column list, such
  as `SET (name, active) = ('a', TRUE)`; and a written column that the table does
  not declare.
- A conflict clause outside the accepted upsert shape, on an `:exec` and on a
  returning insert alike: `ON CONFLICT` without a target, `ON CONFLICT ON
  CONSTRAINT`, a target element that is an expression or carries a collation or
  an operator class, a conflict-target predicate such as
  `ON CONFLICT (id) WHERE active`, and `DO UPDATE ... WHERE`. A `DO UPDATE`
  assignment that sets a column list, and an unknown target, assigned, or
  `EXCLUDED` column, are rejected as the write forms above are.
- An aliased, computed, qualified-wildcard, or unknown `RETURNING` item;
  `RETURNING` on `:exec`; and `UPDATE ... FROM`, `DELETE ... USING`, or a common
  table expression in a returning write.
- Any annotation other than `:one`, `:optional`, `:many`, and `:exec`.

### Not analyzed

These are outside the subset and are not part of the contract. sqlcj neither
models nor rejects them, so a statement that uses one may still compile while
the generated Java describes only the part sqlcj did analyze. Do not rely on
them:

- `DISTINCT`, `GROUP BY`, and `HAVING`,
- predicate forms other than the listed comparisons, `AND`/`OR`, fixed `IN`
  lists, pattern placeholders, null tests, ranges, and list predicates, such as
  `LIKE` with a literal pattern, a range whose bounds are both literal such as
  `id BETWEEN 1 AND 10`, `IN` with a subquery, or `= ANY` over something other
  than a placeholder, such as `id = ANY('{1,2}')`,
- common table expressions, and subqueries outside the `FROM` item,
- `UPDATE ... FROM` and `DELETE ... USING` on a non-returning `:exec` write,
- the unique index an accepted `ON CONFLICT` target matches, and the column
  references of an `EXCLUDED` value that is not exactly `EXCLUDED.column`.

sqlcj itself provides no macros, dynamic `IN` expansion, or query-building API.
The `= ANY` list predicate is the only analyzed array operator. An array
parameter is one whole list bound at one placeholder, not a placeholder list.

Unsupported schema input and unsupported column types are listed in
[PostgreSQL Support](postgresql.md#unsupported-types-and-ddl).

## Verified by

Query sources and annotations:

- `DefaultQueryParserTest.parsesSingleQuery`,
  `DefaultQueryParserTest.parsesMultipleQueries`,
  `DefaultQueryParserTest.preservesQueryOrder`,
  `DefaultQueryParserTest.parsesHeaderLineOfEachQuery`,
  `DefaultQueryParserTest.parsesOptionalQueryType`,
  `DefaultQueryParserTest.parsesExecQueryType`,
  `DefaultQueryParserTest.rejectsInvalidHeader`,
  `DefaultQueryParserTest.rejectsInvalidQueryType`,
  `DefaultQueryParserTest.rejectsDuplicateQueryNames`, and
  `DefaultQueryParserTest.rejectsQueryWithoutSql` cover the file format.
- `QueryAnalyzerTest.shouldRejectSelectWithoutResultQueryType`,
  `QueryAnalyzerTest.shouldAnalyzeSelectDeclaredAsOptional`,
  `QueryAnalyzerTest.shouldRejectWriteWithoutExecQueryType`,
  `QueryAnalyzerTest.shouldRejectReturningWriteDeclaredAsExec`, and
  `QueryAnalyzerTest.shouldAnalyzeReturningWriteForEveryResultQueryType` cover
  annotation and statement agreement.
- `JdbcQueryExecutorTest.shouldReturnTheRowWhenQueryOneFindsExactlyOneRow`,
  `JdbcQueryExecutorTest.shouldFailWhenQueryOneFindsNoRow`,
  `JdbcQueryExecutorTest.shouldFailWhenQueryOneFindsMoreThanOneRow`,
  `JdbcQueryExecutorTest.shouldReturnTheRowWhenQueryOptionalFindsOneRow`,
  `JdbcQueryExecutorTest.shouldReturnEmptyOptionalWhenQueryOptionalFindsNoRow`,
  `JdbcQueryExecutorTest.shouldFailWhenQueryOptionalFindsMoreThanOneRow`,
  `JdbcQueryExecutorTest.shouldReturnEmptyListWhenQueryManyFindsNoRows`,
  `JdbcQueryExecutorTest.shouldReturnAllRowsForQueryMany`, and
  `JdbcQueryExecutorTest.shouldReturnAffectedRowCountForExecute` cover the
  cardinality results and their exact messages.
- `PostgresIntegrationTest.shouldEnforceResultCardinalitiesAgainstPostgres`
  executes `:one`, `:optional`, and `:many` over zero, one, and two matching
  rows of a non-unique column against PostgreSQL 16, through both executor
  construction paths.
- `JavaCodeGeneratorTest.shouldGenerateOptionalExecutionForOptionalQuery`,
  `JavaCodeGeneratorTest.shouldShareOneRowRecordBetweenOptionalAndOtherFullRowQueries`,
  `JavaCodeGeneratorTest.shouldNotImportOptionalWithoutAnOptionalQuery`,
  `JavaCodeGeneratorTest.shouldPassRepositoryAndQueryIdentityToEveryExecutorCall`,
  and
  `JavaCodeGeneratorNamingTest.shouldPassExactRepositoryAndQueryNameToTheExecutor`
  cover the generated calls, return types, imports, and identity literals.

Reads:

- `QueryAnalyzerTest.shouldAnalyzeSelectWithExplicitColumns`,
  `QueryAnalyzerTest.shouldResolveAllColumns`,
  `QueryAnalyzerTest.shouldAnalyzeAliasedSingleTableSelect`,
  `QueryAnalyzerTest.shouldNameSelectedColumnAfterItsAlias`,
  `QueryAnalyzerTest.shouldNameJoinedSelectedColumnsAfterTheirAliases`,
  `QueryAnalyzerTest.shouldAnalyzeSingleInnerJoin`,
  `QueryAnalyzerTest.shouldExpandAllColumnsAcrossJoinedSourcesInOrder`,
  `QueryAnalyzerTest.shouldExpandQualifiedAllColumnsForOneSource`,
  `QueryAnalyzerTest.shouldRejectExcludedJoinReportedAsInnerJoin`,
  `QueryAnalyzerTest.shouldRejectAmbiguousUnqualifiedColumn`,
  `QueryAnalyzerTest.shouldResolveQueryParameterForComparisonOperator`,
  `QueryAnalyzerTest.shouldResolveQueryParametersInsideNestedAndOrExpressions`,
  and `QueryAnalyzerTest.shouldResolveQueryParametersInsideInExpression` cover
  the accepted read shapes.
- `QueryAnalyzerTest.shouldResolveAPublicQualifiedSource` covers the
  unqualified, `public`-qualified, quoted, and upper-case qualifier forms of one
  source, and `QueryAnalyzerTest.shouldRejectASourceOfAnotherSchema` covers the
  rejected message of a source of another schema in a `FROM`, an `INNER` and a
  `LEFT JOIN`, and an `INSERT`, an `UPDATE`, and a `DELETE` target.
- `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedAliasedQualifiedQuery`,
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedJoinQueryWithDuplicateColumnNames`,
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedJoinQueryWithProjectionAliases`,
  and `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedMultipleJoinQuery`
  compile and execute them.
- `PostgresIntegrationTest.shouldExecuteGeneratedOneQueryAgainstPostgres` and
  `PostgresIntegrationTest.shouldExecuteGeneratedManyQueryAgainstPostgres`
  execute an ordered list read against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolveLikePatternParameterInTextualBindingOrder`,
  `QueryAnalyzerTest.shouldResolveLikePatternParameterFromTextColumn`,
  `QueryAnalyzerTest.shouldNotCreateParameterForLiteralLikePattern`,
  `QueryAnalyzerTest.shouldResolveNullPredicateColumnsWithoutParameters`,
  `QueryAnalyzerTest.shouldRejectUnknownColumnInNullPredicate`,
  `QueryAnalyzerTest.shouldRejectUnsupportedLikePlaceholderForm`, and
  `QueryAnalyzerTest.shouldRejectPlaceholderThatIsNotAnAnalyzedPredicateOperand`
  cover the pattern and null predicates, and
  `PostgresIntegrationTest.shouldExecuteGeneratedTextAndNullPredicatesAgainstPostgres`
  executes `LIKE`, a case-insensitive `ILIKE`, `IS NULL`, and `IS NOT NULL`
  reads against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolveRangeBoundParametersFromTestedColumn`,
  `QueryAnalyzerTest.shouldResolveNegatedRangeBoundParameters`,
  `QueryAnalyzerTest.shouldResolveRangeBoundParametersInTextualBindingOrder`,
  `QueryAnalyzerTest.shouldResolveRangeBoundParameterBesideLiteralBound`,
  `QueryAnalyzerTest.shouldNotCreateParameterForLiteralRange`,
  `QueryAnalyzerTest.shouldRejectUnknownColumnInRangePredicate`, and
  `QueryAnalyzerTest.shouldResolveNamedRangeBoundParameters` cover the range
  predicates, and
  `PostgresIntegrationTest.shouldExecuteGeneratedRangePredicatesAgainstPostgres`
  executes a `BETWEEN` read and a `NOT BETWEEN` read whose bounds use
  out-of-order placeholder indexes against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolveListParameterOfAnyFromItsComparedColumn`,
  `QueryAnalyzerTest.shouldResolveNamedListParameterOfAnyAtEveryOccurrence`,
  `QueryAnalyzerTest.shouldCarryTheEnumTypeAndBlankPaddingOfAListParameterOfAny`,
  `QueryAnalyzerTest.shouldResolveListParameterOfAnyInWrites`,
  `QueryAnalyzerTest.shouldNameTheCastArgumentOfAnyAfterItsComparedColumn`,
  `QueryAnalyzerTest.shouldNameACastArgumentOutsideTheListPredicateAfterItsPlaceholder`,
  `QueryAnalyzerTest.shouldRejectAListParameterOfAColumnWithoutAnArrayBinding`,
  `QueryAnalyzerTest.shouldRejectAPlaceholderOutsideTheListPredicateShape`, and
  `QueryAnalyzerTest.shouldRejectANameUsedAsBothAListAndItsElement` cover the
  list predicate, its naming, its cast argument, and the forms that stay
  rejected,
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableRepositoryForAListPredicate`
  compiles its `List` method parameter and its array binding, and
  `PostgresIntegrationTest.shouldExecuteGeneratedListPredicateAgainstPostgres`
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedEnumListPredicateAgainstPostgres`
  execute an id list of no, one, and several ids and an enum-column list
  against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolvePaginationParametersAfterPredicateParameters`,
  `QueryAnalyzerTest.shouldResolvePaginationParametersInTextualBindingOrder`,
  `QueryAnalyzerTest.shouldResolveOffsetParameterBesideUnanalyzedRowCount`,
  `QueryAnalyzerTest.shouldNotCreateParametersForLiteralPagination`,
  `QueryAnalyzerTest.shouldNameNamedPaginationParameterAfterItsPlaceholder`, and
  `QueryAnalyzerTest.shouldRejectPlaceholderInUnsupportedPaginationValue` cover
  pagination, and
  `PostgresIntegrationTest.shouldExecuteGeneratedPaginationAgainstPostgres`
  executes a `LIMIT ... OFFSET ...` page and the same page written as
  `OFFSET ... LIMIT ...` against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolveScalarCountColumnFromItsAlias`,
  `QueryAnalyzerTest.shouldResolveScalarCountBesidePredicateParameter`,
  `QueryAnalyzerTest.shouldResolveScalarCountBesideDirectColumn`,
  `QueryAnalyzerTest.shouldResolveScalarCountBeforeDirectColumn`,
  `QueryAnalyzerTest.shouldResolveGroupedCountBesidePredicateParameter`, and
  `QueryAnalyzerTest.shouldRejectUnsupportedCountProjectionForm` cover the
  aliased `COUNT(*)` alone and beside a grouped column in either position, its
  focused diagnostic, and the count and function forms that stay rejected, and
  `PostgresIntegrationTest.shouldExecuteGeneratedScalarCountAgainstPostgres`
  compiles a `:one` count into a result record with one `Long` component and
  executes it against PostgreSQL 16 for a matching and a non-matching pattern,
  while
  `PostgresIntegrationTest.shouldExecuteGeneratedGroupedCountAgainstPostgres`
  executes a `:many` grouped count whose result record carries the grouped
  `Boolean` column and the `Long` count in projection order.
- `QueryAnalyzerTest.shouldResolveCastProjectionColumnFromItsAlias`,
  `QueryAnalyzerTest.shouldResolveEnumCastProjectionColumn`,
  `QueryAnalyzerTest.shouldResolveArrayCastProjectionColumn`,
  `QueryAnalyzerTest.shouldRejectUnsupportedCastProjectionForm`, and
  `QueryAnalyzerTest.shouldRejectCastPlaceholderProjection` cover both cast
  spellings and both alias spellings, the nullable column of the cast type
  including a declared enum and an array, the missing alias and the unmapped
  cast type, and the projected placeholder that stays rejected, and
  `PostgresIntegrationTest.shouldExecuteGeneratedCastProjectionAgainstPostgres`
  executes a `:one` cast `SUM` against PostgreSQL 16 whose `BigDecimal`
  component is the sum and `null` when no row matches.
- `QueryAnalyzerTest.shouldAnalyzeLeftJoinWithNullableJoinedColumns`,
  `QueryAnalyzerTest.shouldExpandAllColumnsOfLeftJoinedSourceAsNullable`,
  `QueryAnalyzerTest.shouldExpandQualifiedAllColumnsOfLeftJoinedSourceAsNullable`,
  `QueryAnalyzerTest.shouldAnalyzeLeftJoinAfterInnerJoin`,
  `QueryAnalyzerTest.shouldAnalyzeInnerJoinAfterLeftJoin`,
  `QueryAnalyzerTest.shouldResolveLeftJoinedParametersInTextualBindingOrder`,
  `QueryAnalyzerTest.shouldRejectUnsupportedJoinModifier`, and
  `QueryAnalyzerTest.shouldRejectLeftJoinWithoutSingleQualifiedEquality` cover
  both left-join spellings, the nullable columns of a left-joined source, the
  chained join orders, the unchanged parameters, and the join kinds and `ON`
  shapes that stay rejected, and
  `PostgresIntegrationTest.shouldExecuteGeneratedLeftJoinAgainstPostgres`
  executes a left join against PostgreSQL 16 whose unmatched row reads the
  joined columns as `null`.
- `QueryAnalyzerTest.shouldResolveRowTableForFullRowSelect`,
  `QueryAnalyzerTest.shouldNotResolveRowTableForQuerySpecificResult`,
  `QueryAnalyzerTest.shouldNotResolveRowTableForJoinedWildcard`, and
  `QueryAnalyzerTest.shouldNotResolveRowTableForExecWrite` cover which reads
  return one complete table row.

Writes and `RETURNING`:

- `QueryAnalyzerTest.shouldAnalyzeInsert`, `QueryAnalyzerTest.shouldAnalyzeUpdate`,
  `QueryAnalyzerTest.shouldAnalyzeDelete`,
  `QueryAnalyzerTest.shouldAnalyzeInsertReturningColumnsInDeclaredOrder`,
  `QueryAnalyzerTest.shouldExpandInsertReturningAllColumnsInSchemaOrder`,
  `QueryAnalyzerTest.shouldAnalyzeDeleteReturningAllColumns`,
  `QueryAnalyzerTest.shouldRejectUnsupportedReturningItem`,
  `QueryAnalyzerTest.shouldRejectUnknownReturningColumn`, and
  `QueryAnalyzerTest.shouldRejectExcludedReturningWriteForm` cover the write
  shapes.
- `QueryAnalyzerTest.shouldAnalyzeInsertWithNonBindingValues`,
  `QueryAnalyzerTest.shouldAnalyzeNamedInsertWithNonBindingValues`,
  `QueryAnalyzerTest.shouldAnalyzeReturningInsertWithNonBindingValues`,
  `QueryAnalyzerTest.shouldAnalyzeUpdateWithNonBindingAssignments`,
  `QueryAnalyzerTest.shouldAnalyzeWriteWithoutAnyPlaceholder`,
  `QueryAnalyzerTest.shouldAnalyzeNonBindingValueOnAnUnsupportedTypeColumn`,
  `QueryAnalyzerTest.shouldRejectAnUnknownNonBindingTargetColumn`,
  `QueryAnalyzerTest.shouldRejectAPlaceholderInsideAnInsertValue`,
  `QueryAnalyzerTest.shouldRejectAPlaceholderInsideAnUpdateAssignment`, and
  `QueryAnalyzerTest.shouldRejectExcludedWriteValueForm` cover the values that
  bind no placeholder, their numbering and binding order, and the nearby
  rejections.
- `QueryAnalyzerTest.shouldAnalyzeUpsertThatUpdatesOnConflict`,
  `QueryAnalyzerTest.shouldAnalyzeReturningUpsertThatUpdatesOnConflict`,
  `QueryAnalyzerTest.shouldAnalyzeNamedUpsertThatUpdatesOnConflict`,
  `QueryAnalyzerTest.shouldAnalyzeUpsertThatDoesNothingOnConflict`,
  `QueryAnalyzerTest.shouldResolveRowTableForUpsertThatDoesNothingOnConflict`,
  `QueryAnalyzerTest.shouldRejectAConflictTargetThatIsNotColumnNames`,
  `QueryAnalyzerTest.shouldRejectExcludedConflictForm`,
  `QueryAnalyzerTest.shouldRejectAnUncastPlaceholderInsideAConflictUpdateValue`,
  and `QueryAnalyzerTest.shouldRejectAnUnknownConflictColumn` cover the accepted
  conflict target, both actions, the `EXCLUDED`, placeholder, cast, and
  computed assignments, the binding order after the `VALUES` placeholders, the
  returning row type, and the conflict forms, unaccounted placeholder, and
  unknown target, assigned, and `EXCLUDED` columns that are rejected on an
  `:exec` and on a returning insert alike, and
  `PostgresIntegrationTest.shouldExecuteGeneratedUpsertsAgainstPostgres`
  executes a `DO NOTHING` and a `DO UPDATE` action, each with and without
  `RETURNING`, over a new row and then a conflicting one against
  PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolveRowTableForReturningAllColumns` covers the
  returning writes that produce a complete table row.
- `PostgresIntegrationTest.shouldExecuteGeneratedWriteAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedInsertReturningAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedUpdateReturningAgainstPostgres`,
  whose no-row returning update fails its `:one` cardinality check,
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedDeleteReturningAgainstPostgres`
  execute them against PostgreSQL 16.
- `PostgresIntegrationTest.shouldExecuteGeneratedNonBindingWriteValuesAgainstPostgres`
  executes `DEFAULT`, a literal, `NULL`, `now()`, and `code + 1` in an `:exec`
  insert, a returning named insert, and an `:exec` update against
  PostgreSQL 16.

Parameters:

- `QueryAnalyzerTest.shouldResolveBindingIndexesInTextualOrder`,
  `QueryAnalyzerTest.shouldRetainOneParameterForRepeatedIndex`,
  `QueryAnalyzerTest.shouldRejectRepeatedIndexWithConflictingType`,
  `QueryAnalyzerTest.shouldRejectGappedParameterIndexes`,
  `QueryAnalyzerTest.shouldRejectZeroParameterIndex`,
  `QueryAnalyzerTest.shouldRejectPlaceholderInUnsupportedLocation`,
  `QueryAnalyzerTest.shouldRejectAnonymousParameter`, and
  `QueryAnalyzerTest.shouldKeepPlaceholderTextThatIsNotAParameter` cover
  ordering, repetition, and rejection.
- `SqlParserTest.shouldCompileNamedParametersByFirstOccurrence`,
  `SqlParserTest.shouldCompileNamesThatDifferInCaseAsDistinctParameters`,
  `SqlParserTest.shouldReportBothPlaceholderFormsOfOneSource`,
  `SqlParserTest.shouldNotCompileUnsupportedNamedPlaceholderForms`,
  `SqlParserTest.shouldPreserveNamedPlaceholderTextThatIsNotAParameter`,
  `SqlParameterCompilerTest.shouldReplaceReportedNamedParameterSpans`,
  `SqlParameterCompilerTest.shouldIgnoreNameSeparatedFromItsColon`, and
  `SqlParameterCompilerTest.shouldRejectNamedSpanThatDoesNotHoldTheParameterImage`
  cover the compiled named form, its numbering, and its span guards.
- `QueryAnalyzerTest.shouldResolveNamedParameter`,
  `QueryAnalyzerTest.shouldNumberNamedParametersByFirstOccurrence`,
  `QueryAnalyzerTest.shouldResolveNamedParametersOfUpdate`,
  `QueryAnalyzerTest.shouldResolveNamedParametersOfInsert`,
  `QueryAnalyzerTest.shouldResolveNamedParametersInInList`,
  `QueryAnalyzerTest.shouldResolveNamedLikePatternParameter`,
  `QueryAnalyzerTest.shouldResolveNamedParametersSpelledLikeKeywords`,
  `QueryAnalyzerTest.shouldRejectMixedPlaceholderForms`,
  `QueryAnalyzerTest.shouldRejectUnsupportedNamedPlaceholderForm`,
  `QueryAnalyzerTest.shouldRejectNamedPlaceholderInUnanalyzedLocation`, and
  `QueryAnalyzerTest.shouldRejectNamedPlaceholderWithConflictingTypes` cover the
  analyzed named locations, the generated parameter names, and the named
  rejections.
- `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedQueryWithNamedPlaceholders`
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedNamedPlaceholdersAgainstPostgres`
  compile and execute a named query that repeats a name and orders its
  parameters by first occurrence.
- `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedQueryWithOutOfOrderPlaceholders`,
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedQueryWithRepeatedPlaceholder`,
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedUpdateWithOutOfOrderPlaceholders`,
  and
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedQueryWithProtectedPlaceholderText`
  execute the compiled binding order.
- `QueryAnalyzerTest.shouldResolveNamedCastParameterOfOptionalFilter`,
  `QueryAnalyzerTest.shouldResolveNamedCastParameterInsideComputedLikePattern`,
  `QueryAnalyzerTest.shouldResolveNamedCastParameterInsideUpdateAssignment`,
  `QueryAnalyzerTest.shouldResolveCastKeywordParameterNamedAfterItsComparedColumn`,
  `QueryAnalyzerTest.shouldNameCastParameterAfterItsColumn`,
  `QueryAnalyzerTest.shouldNameCastPatternAfterItsPlaceholderInAnUntypedPatternShape`,
  `QueryAnalyzerTest.shouldNameNamedCastPatternAfterItsPlaceholder`,
  `QueryAnalyzerTest.shouldResolveEnumCastParameter`,
  `QueryAnalyzerTest.shouldResolveArrayCastParameterNamedAfterItsPlaceholder`,
  `QueryAnalyzerTest.shouldResolveBlankPaddedCastParameter`,
  `QueryAnalyzerTest.shouldNameIndexedCastParameterAfterItsIndex`, and
  `QueryAnalyzerTest.shouldResolveCastParametersOfInsertValues` cover the cast
  types, the analyzed cast locations, and the name a cast placeholder takes,
  while `QueryAnalyzerTest.shouldRejectUnsupportedCastType`,
  `QueryAnalyzerTest.shouldRejectCastOccurrenceWithConflictingType`,
  `QueryAnalyzerTest.shouldRejectCastPlaceholderInUnanalyzedLocation`, and the
  cast rows of
  `QueryAnalyzerTest.shouldRejectUnsupportedNamedPlaceholderForm` cover the
  cast rejections.
- `SqlParameterCompilerTest.shouldReportNamedParametersThatAreCastOperands` and
  `SqlParameterCompilerTest.shouldNotReportCompiledNamedParameterThatIsACastOperand`
  cover the cast operand the parser reports without a parse-tree node of its
  own.
- `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForCastPlaceholders`
  compiles a repository whose cast and uncast placeholders of one column
  generate two disambiguated parameters, and
  `PostgresIntegrationTest.shouldExecuteGeneratedCastTypedOptionalFilterAgainstPostgres`
  executes a named cast-typed optional filter against PostgreSQL 16 with a null
  and a non-null argument.

Generated Java:

- `JavaNamesTest` covers the camel-case naming rules, the acronym, quoted-name,
  keyword, and digit-initial cases, the disambiguation suffixes, and the
  rejection of a SQL name without a letter or digit.
- `JavaCodeGeneratorTest` covers the generated repository, its single executor
  field and constructor, the nested result records and row mappers, method
  signatures, and compilation of the generated source.
- `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaFiles` and
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForReturningWrites`
  compile the generated output of the supported query shapes.
- `JavaCodeGeneratorTest.shouldShareOneRowRecordAcrossFullRowQueriesOfOneTable`,
  `JavaCodeGeneratorTest.shouldGenerateOneRowRecordPerRowTable`,
  `JavaCodeGeneratorTest.shouldDisambiguateRowMapperFromQueryRowMapper`, and
  `JavaCodeGeneratorTest.shouldRejectRowTypesThatAreEqualIgnoringCase` cover the
  shared row records, their mapper fields, and their collision.
- `SqlcjCompilerIntegrationTest.shouldGenerateOneSharedRowRecordForTwoEntries`,
  `SqlcjCompilerIntegrationTest.shouldDeleteTheStaleRowRecordOfARemovedFullRowQuery`,
  `SqlcjCompilerIntegrationTest.shouldReportTwoEntriesThatDefineOneRowDifferently`,
  and
  `SqlcjCompilerIntegrationTest.shouldReportRowRecordsOfTwoEntriesThatAreEqualIgnoringCase`
  cover the one top-level row record two entries share, its manifest entry and
  stale deletion, and the two cross-entry diagnostics.
- `PostgresIntegrationTest.shouldExecuteGeneratedFullRowQueriesIntoOneSharedRowTypeAgainstPostgres`
  executes a returning write, a full-row read, and a qualified full-row list of
  one table into one row type against PostgreSQL 16.
- `JavaCodeGeneratorTest.shouldBindAndReadArrayColumnsPerElementType`,
  `JavaCodeGeneratorTest.shouldBindEnumArrayLabelsAndReadEnumArrayColumnsByLabel`,
  and `JavaCodeGeneratorTest.shouldImportListForAnArrayRowComponent` cover the
  generated `List` type, the `SqlArray` argument and read, and the row record's
  `java.util.List` import, and
  `PostgresIntegrationTest.shouldRoundTripArrayValues` executes them against
  PostgreSQL 16.
