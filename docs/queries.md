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

- The `FROM` item must be a table of that schema, a `WITH` clause in scope, or a
  subquery, optionally with an alias. A `LATERAL` source is rejected as
  `LATERAL is not supported.`, before the source is resolved, and a doubly
  parenthesized source, such as `FROM ((SELECT ...)) s`, as
  `Only table sources are supported.`
- A `FROM` or `JOIN` source whose unqualified name matches a `WITH` clause in
  scope reads that clause and not the table of the same name, as PostgreSQL
  resolves it, matched case-insensitively and taking the innermost declaration
  of that name. A schema-qualified name always names a table.
- A `WITH` clause and a subquery source are **derived sources**: the source's
  columns are the result columns of its own `SELECT`, in order, with the names,
  types, enum types, arrays, and nullability that `SELECT` states. A derived
  source returns no shared `<TableName>Row` record, so `SELECT * FROM <name>`
  over one returns this query's own result record even when the source is named
  like a table, and every column of a left-joined derived source is nullable,
  as it is for a left-joined table.
- The body of a derived source is analyzed exactly like a top-level `SELECT`:
  it is classified inside its own parentheses, so a set operation or another
  statement form there is rejected with that diagnostic; its sources resolve and
  its joins follow the join rules; its projection follows the projection rules,
  including the rejection of a computed item without a cast and an alias; and
  its `ON`, `WHERE`, `LIMIT`, and `OFFSET` placeholders are typed by those
  clause rules, so `WHERE total = $1` inside a `WITH` body needs no cast.
- A subquery source requires an alias,
  `A subquery in FROM requires an alias, such as (SELECT ...) AS recent.`, and
  sees no source of the query around it, as PostgreSQL scopes a subquery that is
  not `LATERAL`.
- A column list on a `WITH` name or on a subquery alias, such as
  `WITH recent (n) AS (...)` or `(SELECT ...) AS s(n)`, is rejected as
  `A column list on <name> is not supported; alias the columns in its SELECT
  instead.`, and a derived source two of whose columns expose one name, compared
  ignoring case, as
  `Derived source <name> has more than one column named <column>; give each a
  distinct alias.`
- Every `WITH` clause of every statement is analyzed in the order the query
  writes it, whether or not a source reads it, so its placeholders are typed by
  its own clause rules. A clause sees the clauses written before it in its own
  list and those of every enclosing query, so a clause named like a table reads
  that table inside its own body. `MATERIALIZED` and `NOT MATERIALIZED` are
  accepted, while `WITH RECURSIVE` is rejected as
  `WITH RECURSIVE is not supported.` and a data-modifying body as
  `A data-modifying statement in WITH is not supported; run it as a query of its
  own.`
- sqlcj models one namespace, so a source resolves by its unqualified name:
  `users`, `public.users`, and `"public"."users"` are one table, matched without
  SQL identifier delimiters and case-insensitively. A source qualified with any
  other schema names a table sqlcj leaves unmodeled and is rejected as
  `Table not found in schema: <schema>.<t>`, in a `FROM`, a `JOIN`, and the
  target of an `INSERT`, an `UPDATE`, and a `DELETE` alike. `search_path` is
  not followed; see
  [Schema Snapshot Input](postgresql.md#schema-snapshot-input).
- A table or a derived source may be joined with `JOIN`, `INNER JOIN`,
  `LEFT JOIN`, or `LEFT OUTER JOIN`, and inner and left joins may be chained in
  any order.
- Every other join is rejected, and a join that can be written as a supported
  one says what to write:
  - `FULL JOIN is not supported.`
  - `RIGHT JOIN is not supported; write a LEFT JOIN with the two sources swapped.`
  - `CROSS JOIN is not supported; write JOIN ... ON TRUE.`
  - `NATURAL JOIN is not supported; write the join condition with ON.`
  - `JOIN ... USING is not supported; write the join condition with ON.`
  - `A comma-separated FROM list is not supported; write JOIN ... ON with the join condition.`

  A `LATERAL` join source is reported as the same unsupported source as a
  `FROM` item of that kind, before the join kind, while every other joined
  source is resolved after it, and every remaining modifier — a bare `OUTER`,
  `SEMI`, `APPLY`, `STRAIGHT`, `GLOBAL`, and a join hint — is rejected as
  `Only unmodified INNER JOIN and LEFT JOIN clauses are supported.`
- Each join requires exactly one `ON` condition, which may be any boolean
  expression, including `ON TRUE`. Its column references, qualified or not,
  resolve against the sources introduced up to and including the joined source,
  so a reference to a source joined later is rejected, and its placeholders
  follow the same rules as a `WHERE` predicate. A left join uses the same rule
  as an inner join.
- Every column read from a left-joined source is nullable, even when the schema
  declares it `NOT NULL`, because an unmatched row reads it as `NULL`. The join
  kind alone decides this, so a left join whose `ON` condition filters its
  joined rows reads them as nullable too. The base source and every
  inner-joined source keep their schema nullability.
- An alias replaces the table name as the exposed source name, so a qualified
  reference to an aliased table must use the alias and not the table name.
- Two sources may not expose the same name.

### Projections

- A direct column of a source is selected by its own name or qualified with the
  exposed source name.
- A reference written with an array subscript or slice, such as `labels[1]`,
  `labels[1:2]`, or `s.labels[1]`, is not a direct column: it reads one element
  or one range of the array column rather than the column, so it is rejected
  like any other computed projection unless a cast states its type, as in
  `labels[1]::text AS first` or `labels[1:2]::varchar[] AS pair`.
- A wildcard of a table source that is not left-joined — `qualifier.*`, or each
  such source of a bare `*` — is one result component holding that table's
  complete row, typed by the table's shared `<TableName>Row` record and named
  after the name its source exposes, which is the source's alias or its table
  name as written. The row's columns are the table's own, in schema column
  order, with their declared nullability, and the component itself is never
  null.
- A projection that is one wildcard covering exactly one such source returns
  that table's shared row itself instead of a record holding it, so
  `SELECT u.* FROM users u JOIN profiles p ON p.user_id = u.id` returns
  `UsersRow`.
- A wildcard of a derived source — a common table expression or a subquery
  source — or of a left-joined source expands into that source's own columns in
  order, because a derived source has no row record and a left-joined source
  reads every column as nullable. A bare `*` therefore holds an embedded row per
  source that is not left-joined and the expanded columns of every other
  source, in source order.
- Selected-column order is the order of the generated result record and of the
  positional row mapper. An embedded row stands in the position its wildcard is
  written at and holds the columns that wildcard selected, so the read order
  stays the selected-column order.
- An unqualified column must be found in exactly one source; an ambiguous or
  unknown column is rejected.
- A result expression that is not a direct column, a wildcard, the scalar count
  below, or the cast projection below — such as another function call, an
  arithmetic expression, or a literal — is rejected as
  `Cannot infer the type of <expression> in SELECT; state it with a cast and an
  alias, such as SUM(amount)::numeric AS total`, naming the expression as the
  query writes it, on one line.
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
- A placeholder the projection's cast casts directly, as in
  `SELECT $1::text AS label`, is one parameter of the query, typed by that cast
  and named after its own placeholder, so an indexed one is `param<N>` and a
  named one keeps its name. It binds before a `WHERE` placeholder written after
  it, and a named one is also the first logical parameter. An uncast
  placeholder in a projection is still rejected.

### Predicates

A `WHERE` clause may combine:

- the comparisons `=`, `<>`, `>`, `>=`, `<`, `<=` between a direct column and a
  `$N` placeholder, in either order,
- `AND`, `OR`, `NOT`, and parentheses,
- `IN` with a fixed list of `$N` placeholders, such as `id IN ($1, $2)`,
- `LIKE` and `ILIKE` between a direct column and a `$N` pattern placeholder,
  such as `name LIKE $1`,
- `IS NULL` and `IS NOT NULL` on a direct column, such as `bio IS NULL`,
- `BETWEEN` and `NOT BETWEEN` on a direct column, such as
  `code BETWEEN $1 AND $2`,
- `= ANY` between a direct column and one `$N` list placeholder, such as
  `id = ANY($1)`,
- a subquery: `EXISTS (SELECT ...)` and `NOT EXISTS (SELECT ...)`,
  `IN (SELECT ...)` and `NOT IN (SELECT ...)`, whatever the tested value is,
  and a comparison one of whose operands is `(SELECT ...)`.

A direct column is a column reference written without an array subscript or
slice. A subscripted or sliced reference, such as `labels[1]`, `labels[1:2]`, or
`u.labels[1]`, is an expression of the array column, so no placeholder beside
one is typed from that column: `labels[1] = $1` is rejected as an unanalyzed
placeholder location, in a comparison of either order, an `IN` list, a `LIKE`
pattern, a `BETWEEN` bound, an `= ANY` list, a join `ON` condition, and a
write's `WHERE` alike. A cast states the type of such a placeholder, as in
`WHERE labels[1] = $1::text`, and the subscript itself reaches the database as
written.

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

A predicate subquery is analyzed by the rules of a read wherever the predicate
rules reach it — through `AND`, `OR`, `NOT`, and parentheses, in a read's
`WHERE`, a join `ON` condition, a write's `WHERE`, a conflict predicate, and the
`WHERE` of another analyzed nested query — so its placeholders are typed from
columns without a cast:

```sql
-- name: ListTeamsOfRole :many
SELECT id, name
FROM teams
WHERE id IN (SELECT team_id
             FROM memberships
             WHERE role = :role)
ORDER BY id;
```

`ListTeamsOfRole` generates `listTeamsOfRole(String role)`, whose one parameter
is typed and named from `memberships.role`.

The subquery sees its own sources first and then the sources of each query
around it, so a correlated predicate such as
`EXISTS (SELECT 1 FROM memberships m WHERE m.team_id = teams.id AND teams.name = :name)`
types `:name` from the enclosing `teams`. An unqualified column resolves in the
innermost level that has it, and a qualified one through the innermost source of
that exposed name, so a subquery source shadows an enclosing source of the same
name. A column two sources of one level expose is ambiguous in that level, while
the same name in an enclosing level never makes a reference ambiguous, and a
column no level exposes is rejected naming every visible source from the
innermost level outward, as in `Column not found in sources p, o, u: missing`.

A predicate subquery states no result column of the query, so its `SELECT` list
accepts `SELECT 1`, a wildcard, and a column whose type sqlcj does not map,
while a direct column there still names a column of the sources in scope. Its
own `WITH` list, its sources, its join conditions, and its `WHERE`, `LIMIT`, and
`OFFSET` clauses follow the rules every analyzed nested `SELECT` follows, which
[Sources](#sources) states, so a set operation inside it is rejected with the
set-operation diagnostic, and a `WITH` body or a subquery source declared inside
it sees no source of the query around it.

A subquery in any other location keeps its unanalyzed handling, in which only a
cast types a placeholder: `= ANY (SELECT ...)`, `IN ((SELECT ...))`, a bare
boolean subquery, and a subquery in a projection, `GROUP BY`, `HAVING`,
`ORDER BY`, a written value, `RETURNING`, or a function argument. A placeholder
compared with a subquery, as in `:x = (SELECT ...)`, is not typed from it
either.

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
statement, including the ordering clause, reaches JDBC exactly as written.

An `ORDER BY` placeholder that states its type with a cast is one parameter of
the query, which is how a sort key chosen by the caller compiles:

```sql
-- name: ListAuthorsSorted :many
SELECT id, name
FROM authors
ORDER BY
    CASE WHEN :sort::text = 'name' THEN name END,
    CASE WHEN :sort::text = 'id' THEN id END;
```

- `ListAuthorsSorted` generates `listAuthorsSorted(String sort)`, whose one
  argument is bound at both of its textual positions, so each accepted value
  returns the rows in its own order.
- The clause itself is still not analyzed: the ordering expression, including
  its column references, reaches the database as written, and sqlcj checks only
  the cast that types the placeholder.
- An uncast `ORDER BY` placeholder, such as `ORDER BY $1`, is still rejected,
  because nothing states its type. sqlcj never pastes a column name or a
  direction into the SQL, so an ordering column chosen at run time stays
  outside the subset.

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
- A value that is a cast placeholder keeps its clause's name and takes the type
  the cast states, so `LIMIT $1::int OFFSET $2::int` generates the same
  `(Integer limit, Integer offset)` method parameters as the uncast page, while
  `LIMIT $1::bigint` generates `Long limit` and `LIMIT :pageSize::int`
  generates `pageSize`.
- A placeholder in a computed value, as in `LIMIT $1 + 1` or `OFFSET $1 + 1`,
  in the `LIMIT a, b` form, as in `LIMIT 5, $1`, and in a
  `FETCH FIRST $1 ROWS ONLY` clause is rejected as an unanalyzed placeholder
  location unless a cast states its type. A cast placeholder there is one
  parameter named after its own placeholder rather than after a pagination
  clause, so `LIMIT LEAST($1::int, 100)` and `FETCH FIRST $1::int ROWS ONLY`
  each generate `param1`.

## Writes

A write targets exactly one table of its entry's schema, whose rows it changes.
An `UPDATE` and a `DELETE` may read further sources of that schema to select
those rows, as
[Added sources in `UPDATE` and `DELETE`](#added-sources-in-update-and-delete)
states.

| Statement | Accepted shape |
| --- | --- |
| `INSERT` | an explicit column list and a row source — `VALUES` rows or a `SELECT` — writing one value per written column by position, each a placeholder or an expression that binds none, with an optional `ON CONFLICT` clause |
| `UPDATE` | `SET` assignments that each assign one direct column a placeholder or an expression that binds none, with an optional `FROM` clause of added sources and an optional `WHERE` using the read predicate forms |
| `DELETE` | one target table, with an optional `USING` clause of added sources and an optional `WHERE` using the read predicate forms |

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

An `INSERT` writes its rows either from `VALUES` rows or from a `SELECT`, each
of which may be written inside parentheses, at any depth, as in
`(VALUES ($1))`. Every `VALUES` row pairs with the column list by position and
must write one value per written column, and a placeholder repeated across rows
is one parameter bound at each of its positions:

```sql
-- name: CreateAuthors :exec
INSERT INTO authors (name, bio)
VALUES (:firstName, :bio),
       (:secondName, :bio);

-- name: CreateTeamMemberships :exec
INSERT INTO memberships (team_id, user_id)
SELECT :teamId, unnest(:userIds::bigint[]);
```

`CreateAuthors` generates
`createAuthors(String firstName, String bio, String secondName)` and binds
`(firstName, bio, secondName, bio)`.

The `SELECT` an `INSERT` writes its rows from is analyzed by the rules
[Sources](#sources) states for an analyzed nested read: its sources resolve, its
join conditions, `WHERE`, and pagination placeholders are typed by those clause
rules, and it may declare `WITH` clauses of its own. It may also omit `FROM`,
which writes one row of values, as `CreateTeamMemberships` does, and it sees
no source of the insert, so it cannot read the target table through its name.
Its items pair with the column list by position and must match its count, a
wildcard standing for the columns of its source in schema order:

- a placeholder item is typed by the column it is written into, and an indexed
  one is named after that column, exactly as a `VALUES` value is, so
  `CreateTeamMemberships` generates
  `createTeamMemberships(Long teamId, List<Long> userIds)`;
- a cast placeholder item is typed by its cast and named after that column too;
- a direct column only resolves against the select's own sources, because it
  binds nothing, and so does each column a wildcard expands to;
- every other item reaches the database as written, and a cast placeholder
  inside one is typed by its own cast, as in
  `unnest(:userIds::bigint[])` above.

A written column may carry an array subscript or slice, as in
`SET labels[1] = $1::text` or `INSERT INTO stages (id, labels[1])`. Such a
target is not a direct column: it writes one element or one range, so its value
is not typed from the array column and a placeholder value needs a cast, while
the array column itself must exist in the written table and the subscript
reaches the database as written. An uncast placeholder written there is rejected
as an unanalyzed placeholder location, in an `UPDATE` or `DO UPDATE` assignment,
in a `VALUES` value, and in an item of an insert's `SELECT` alike.

### Added sources in `UPDATE` and `DELETE`

An `UPDATE` may select the rows it writes through an `UPDATE ... FROM` clause,
and a `DELETE` through a `DELETE ... USING` clause. The write still targets one
table, whose rows it changes, while the added sources only decide which of its
rows it writes and what the write may read:

```sql
-- name: SetRoyaltyAmountsByCountry :exec
UPDATE royalties r
SET amount = :amount
FROM authors a
WHERE a.id = r.author_id
  AND a.country = :country;

-- name: DeleteAuthorRoyalties :many
DELETE
FROM royalties r
    USING authors a
WHERE a.id = r.author_id
  AND a.name = :name
RETURNING r.id, a.name;
```

`SetRoyaltyAmountsByCountry` generates
`setRoyaltyAmountsByCountry(BigDecimal amount, String country)`, whose second
parameter is typed and named from `authors.country`, and
`DeleteAuthorRoyalties` returns one record of the deleted royalty's `id` and
its author's `name`.

- An `UPDATE ... FROM` clause takes one source followed by inner or left joins,
  by the join rules [Sources](#sources) states, or by further comma-separated
  sources. A comma-separated list is accepted here, unlike in a `SELECT`,
  because it is PostgreSQL's own form of the clause.
- A `DELETE ... USING` clause takes a comma-separated list of sources, and no
  join: JSqlParser does not read a join there, so `USING a JOIN b` fails to
  parse.
- Each added source is a table of the entry's schema, a `WITH` clause of the
  write in scope, or an aliased subquery, resolved exactly as a read's source
  is, so a `LATERAL` source, a doubly parenthesized source, and a subquery
  without an alias keep their diagnostics.
- A join condition inside `FROM` sees only the sources of the comma-separated
  item it joins onto, up to and including its own joined source, because
  PostgreSQL binds a `JOIN` more tightly than a comma. The target is never one
  of them, so a condition that names it is rejected as
  `Unknown source qualifier in query: <name>`, as PostgreSQL also rejects it.
  Its placeholders are typed by the `WHERE` predicate rules.
- The `WHERE` predicate reads the target and every added source as one level,
  with the write's `WITH` clauses in scope of its predicate subqueries, so a
  placeholder compared with a column of an added source is typed and named from
  that column. `SET` assignments keep reading the target's columns alone.
- The exposed names of the target and of every added source must differ, as the
  sources of a read must, so a repeated name is rejected as
  `Duplicate source name in query: <name>`.
- Every column read from a left-joined source is nullable, in `RETURNING` as in
  a read's projection.
- Placeholders bind in the textual order of the query, across the `SET`
  assignments, the added sources and their join conditions, and the `WHERE`
  predicate.

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

An `INSERT` may end with one conflict clause that names which conflict it
handles, followed by one action:

| Target | Accepted shape |
| --- | --- |
| none | accepted with `DO NOTHING`, which needs no target; `DO UPDATE` requires one |
| a column list | a parenthesized list of plain column names of the inserted table, with an optional predicate, as in `ON CONFLICT (code) WHERE active` |
| `ON CONSTRAINT name` | accepted with either action; the name is resolved by PostgreSQL |

| Action | Accepted shape |
| --- | --- |
| `DO NOTHING` | no assignment, so the statement binds only the rows its row source writes |
| `DO UPDATE SET` | assignments analyzed exactly as `UPDATE` assignments are, where a value may also be `EXCLUDED.column`, with an optional `WHERE` predicate |

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
matches neither the conflict target against a unique index nor a constraint
name against the schema; PostgreSQL resolves both.

Either predicate uses the read predicate forms, and a placeholder in one is
typed exactly as the same comparison types it in a `WHERE` clause:

```sql
-- name: PublishAuthorIfNewer :optional
INSERT INTO authors (id, name, version)
VALUES ($1, $2, $3)
ON CONFLICT (name) WHERE active DO UPDATE
SET version = EXCLUDED.version
WHERE authors.version < EXCLUDED.version
  AND authors.name <> $4
RETURNING id, name, version;
```

The two predicates read different sources:

- a conflict-target predicate reads the inserted table alone, under the name
  that table exposes, which is its alias when the `INSERT` writes one, as in
  `INSERT INTO authors AS a`;
- a `DO UPDATE ... WHERE` predicate reads both the stored row, under the same
  exposed name, and the proposed row as `EXCLUDED`. Both carry the same
  columns, so an unqualified column compared with a placeholder there is
  ambiguous, as PostgreSQL also reports it; write `authors.version` or
  `EXCLUDED.version`.

A placeholder in a conflict-target predicate is typed and bound like any other,
but PostgreSQL chooses the arbiter index when it plans the statement rather than
when it runs it, so a parameterized target predicate stops matching a partial
unique index once the statement is executed under a generic plan and the insert
then fails. Write a target predicate with literals that imply the index's own
predicate, as in `WHERE active` or `WHERE active = TRUE`, and keep the
placeholders in the `DO UPDATE ... WHERE` predicate.

Every conflict placeholder binds in the textual order the query writes it, so
`ON CONFLICT (name) WHERE active DO UPDATE SET bio = $4 WHERE authors.version < $3`
on a two-value insert binds `$1`, `$2`, `$4`, and `$3` in that order, while each
generated method parameter keeps its own index.

A `DO NOTHING` action writes no row when a conflict occurs, so
`DO NOTHING ... RETURNING` returns no row on conflict and suits `:optional` or
`:many` rather than `:one`. A `DO UPDATE ... WHERE` whose predicate does not
hold writes no row either, so it returns none as well. A `DO UPDATE` without a
predicate always returns the row it inserted or updated.

Every other conflict form is rejected on every insert, whether `:exec` or
returning: a `DO UPDATE` without a target, which is rejected naming the two
targets it accepts, and a target element that is an expression or carries a
collation or an operator class.

### `RETURNING`

A supported `INSERT`, `UPDATE`, or `DELETE` declared `:one`, `:optional`, or
`:many` may end with a `RETURNING` clause that lists either:

- direct columns of the write's sources — the target and, on a joined write,
  every source its `FROM` or `USING` clause adds — in the order they are
  declared, or
- a wildcard of the target, which returns the target table's complete row: a
  bare `*` on a single-table write, and `<target>.*` on any write, written with
  the name the target exposes, which is its alias when the write declares one.
  A clause that is exactly such a wildcard returns the target table's shared
  `<TableName>Row` record; written beside another returned item, the wildcard is
  one component holding that same row, named after the name the target exposes,
  so `RETURNING u.*, p.nickname` returns `UsersRow u` beside `String nickname`
  and `RETURNING *, id` returns `UsersRow users` beside `Long id`.

A returned column may carry an alias, written as `id AS user_id` or `id user_id`,
which names its result component exactly as a `SELECT` item's alias does, while
its type comes from the schema column and its nullability from that column and
its source, so a column of a left-joined source is nullable.

A returning write produces the same generated record, positional row mapper, and
cardinality behavior as a read, so a database-generated `SERIAL` or `BIGSERIAL`
value is read back with its declared type. A clause that is exactly a wildcard
of the target returns the target table's shared `<TableName>Row` record, on a
joined and on a single-table write alike, while a `RETURNING` column list,
including one whose items are a wildcard of the target and another column, keeps
the query's own `<QueryName>Result` record.

An unknown column and `RETURNING` on `:exec` are rejected, and a computed
`RETURNING` item, with or without an alias, is rejected as
`Unsupported RETURNING item <expression>; return columns of the target table or *`,
naming the expression as the query writes it, on one line. A cast does not make
such an item supported, because a `RETURNING` clause returns columns of the
write's sources. A reference written with an array subscript or slice, such as
`RETURNING labels[1]` or `RETURNING labels[1:2] AS pair`, is not a direct
column either, so it is rejected as a computed item, with or without an alias.

A wildcard returns the complete row of the target alone, so:

- an aliased wildcard, bare or qualified, is rejected as
  `RETURNING <wildcard> must not be aliased.`, and a modified one, such as
  `RETURNING * EXCEPT (id)`, as `RETURNING <wildcard> must not be modified.`;
- a wildcard whose qualifier names no source of the write, the `old` and `new`
  row aliases of a write included, is rejected as
  `Unknown source qualifier in query: <name>`;
- a wildcard of an added source is rejected as
  `RETURNING <name>.* is not supported; return the columns of <name> by name.`;
- a bare `*` on a joined write, which PostgreSQL returns the columns of every
  source for, is rejected as
  `RETURNING * is not supported with UPDATE ... FROM or DELETE ... USING; write
  RETURNING <target>.* for the target's row.`

A returning insert accepts every accepted row source — several `VALUES` rows, a
parenthesized row source, and a `SELECT` — and a returning `UPDATE` and
`DELETE` accept the added sources
[Added sources in `UPDATE` and `DELETE`](#added-sources-in-update-and-delete)
states. An accepted `ON CONFLICT` clause is analyzed the same way with and
without `RETURNING`.

A write may declare `WITH` clauses of its own, with or without `RETURNING`.
Each body is analyzed by the rules [Sources](#sources) states, so its
placeholders are typed from its own columns, while the returned columns stay
the write target's. Such a clause is read by the `SELECT` an `INSERT` writes
its rows from, which resolves a source named like one of them, and otherwise
only by a predicate subquery of the write's `WHERE` or of a conflict predicate,
which sees the write's `WITH` clauses and the target beside its own sources, as
[Predicates](#predicates) states. An `INSERT` may write its `WITH` clauses
before the statement or after the column list, where they belong to the row
source, and either placement is analyzed and read the same way, on a `SELECT`
and on a `VALUES` row source alike.

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
- The clause rules apply in an analyzed nested `SELECT` — a `WITH` body, a
  subquery source, a predicate subquery, and the `SELECT` an `INSERT` writes
  its rows from — exactly as they do at top level, so a placeholder of such a
  body's `ON`, `WHERE`, `LIMIT`, or `OFFSET` clause is typed and named from its
  own clause without a cast. A predicate subquery also types a placeholder from
  a column of the query around it, as [Predicates](#predicates) states, and an
  item of an insert's `SELECT` is typed and named by the column it is written
  into, as [Writes](#writes) states.
- A placeholder written as the direct operand of `::type` or
  `CAST(... AS type)` is typed by that cast instead, wherever it appears in an
  accepted statement: a projection, a join `ON` condition, `WHERE`, `GROUP BY`,
  `HAVING`, `ORDER BY`, `LIMIT`, `OFFSET`, `FETCH`, an `INSERT` value, an
  `UPDATE` or `DO UPDATE` assignment, a `WITH` body, and a subquery at any
  depth, such as `EXISTS`, `IN (SELECT ...)`, or a scalar subquery. The cast
  type may be any type a column may declare and sqlcj maps, including a declared
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
  `name = $1` does. An indexed cast placeholder that is the whole `LIMIT` row
  count or `OFFSET` value is named `limit` or `offset`, as an uncast one is.
  These names come from the clause the placeholder stands in, in an analyzed
  nested `SELECT` as at top level, so `orders.total = $1::numeric` names
  `total` in a `WITH` body and in a predicate subquery alike. Every other
  indexed cast placeholder, including one in a clause or a nested query sqlcj
  does not analyze, is named `param<N>` after its own index, as in `param1` for
  `$1`, and colliding names take the usual numeric suffix.
- A column written with an array subscript or slice names no parameter, because
  the parameter beside it is one element rather than the column's value, so an
  indexed cast placeholder there is `param<N>` and a named one keeps its own
  name: `labels[1] = $1::text` binds `param1`, where `labels = $1::text` would
  bind `labels`.
- Every placeholder occurrence binds in textual order across the clauses and
  nesting depths above, whatever order sqlcj analyzes those clauses in, so a
  projected placeholder binds before a `WHERE` one and
  `OFFSET $2::int LIMIT $1::int` binds the offset first.
- Occurrences of one placeholder may mix a cast and an uncast location, and the
  parameter keeps the name and type of its first occurrence, so
  `(:name::text IS NULL OR name = :name)` is one `String` parameter named
  `name`.
- Mixing `$N` and `:name` placeholders in one query is rejected, as are
  anonymous `?` placeholders, a qualified name such as `:a.b`, a quoted name
  such as `:"x"`, and an `&name` placeholder.
- A placeholder that neither a clause nor a cast types — for example
  `ORDER BY $1`, `lower(name) = :name`, or `(:x)::int`, whose placeholder is not
  the direct operand of its cast — is rejected rather than left unbound, as
  `Cannot infer the type of :name in lower(name) = :name; state it with a cast,
  such as :name::text`. The diagnostic writes the placeholder as the query
  spells it, `$1` or `:name`, and names where the query writes it: the smallest
  expression written around the placeholder, on one line, with whatever
  separates two tokens written as one space. A concatenated pattern is
  therefore named in its whole `LIKE` predicate and an `IN` list in its `IN`
  predicate, while a placeholder written inside no expression stands alone in
  its clause and is named by the keyword that opens that clause, in upper case:
  `SELECT`, `DISTINCT ON`, `ON`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`,
  `LIMIT`, `OFFSET`, `FETCH`, `VALUES`, `SET`, or `RETURNING`. One query reports
  its first untyped placeholder in textual order, after the rules of its clauses
  have run, so a clause's own rejection comes first.

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
whose complete row an entry returns or embeds, and one Java enum file for each
enum type a query of an entry reads or binds, an embedded row's own columns
included.

- Every generated file begins with the fixed notice
  `// Code generated by sqlcj. DO NOT EDIT.`, followed by one blank line and the
  `package` declaration. The notice holds no version, timestamp, or filesystem
  path.
- The repository has one `dev.sqlcj.runtime.QueryExecutor` field and one
  constructor taking that executor.
- Methods appear in query-source order. A method name is the lower camel form
  of the query name, as in `get_author` and `GetAuthor` to `getAuthor`.
- A `:one`, `:optional`, or `:many` query that returns one complete table row — a
  `SELECT *` over one table source, a `SELECT <source>.*` whose source is a
  table that is not left-joined, or a write whose `RETURNING` clause is exactly
  a wildcard of its target — returns the `<TableName>Row` record. That record is a
  top-level `public record` in `java.package`, generated once per table into its
  own file and shared by every repository of the package that returns or embeds
  that row.
  Each repository keeps its own private `RowMapper` field for a row one of its
  queries returns whole, generated after the constructor in the order its
  queries first use it.
- Every other `:one`, `:optional`, or `:many` query generates a nested `public record` named
  `<QueryName>Result` in upper camel case, whose components follow the
  selected-column order and are named after each column's projection alias or
  column name, plus a private `RowMapper` field that reads each column by its
  one-based position. That includes an explicit column list, even one naming
  every column, a wildcard combined with another projection item, a bare
  wildcard over joined sources, and a `RETURNING` column list.
- A component that holds one table's complete row is typed by that table's
  `<TableName>Row` record, by its simple name, and is built inside the result's
  own mapper from the consecutive positions its wildcard selected, as in
  `new ListUserProfilesResult(new UsersRow(resultSet.getObject(1, Long.class),
  ...), new ProfilesRow(resultSet.getObject(3, Long.class), ...))`, so a row the
  repository only embeds generates no mapper field of its own.
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
  an embedded row's own columns included, so the runtime artifact is the only
  sqlcj dependency a consumer needs. A row
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

Rejection stops the run with one diagnostic line naming the query source, the
query name, and its header line.

- A statement that is not a `SELECT`, an `INSERT`, an `UPDATE`, or a `DELETE`,
  including `VALUES`, `TABLE`, `MERGE`, `TRUNCATE`, `EXPLAIN`, `EXECUTE`, a DDL
  statement such as `CREATE TABLE`, and a `SET` statement, each rejected as
  `Only SELECT, INSERT, UPDATE, and DELETE statements are supported.` before the
  annotation is checked. A parenthesized `SELECT` is the query it encloses, at
  any depth, so it is analyzed as that query.
- A set operation, `UNION`, `UNION ALL`, `INTERSECT`, or `EXCEPT`, written
  directly or inside parentheses, rejected as
  `UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT.`
  A `WITH` body, a subquery source, a predicate subquery, and the row source of
  an `INSERT` are classified inside their own parentheses the same way, so a
  set operation there is rejected with that diagnostic and another statement
  form, such as a `VALUES` body of a `WITH` item, with the statement
  diagnostic.
- A result expression that is not a direct column, a wildcard, an aliased
  `COUNT(*)`, or an aliased cast, including another function call, another
  aggregate, and a literal. A `COUNT(*)` without an alias, a cast projection
  without an alias, and a cast projection whose type sqlcj does not map each
  fail with their own diagnostic, while `COUNT(column)`,
  `COUNT(DISTINCT column)`, `COUNT(t.*)`, `pg_catalog.count(*)`,
  `lower(name) AS n`, `name || 'x' AS n`, and the `FILTER` and `OVER` forms are
  rejected as `Cannot infer the type of COUNT(*) OVER () in SELECT; state it
  with a cast and an alias, such as SUM(amount)::numeric AS total`.
- A `FROM` or `JOIN` source outside the subset: a `LATERAL` source, a doubly
  parenthesized source, a subquery source without an alias, a column list on a
  `WITH` name or on a subquery alias, and a derived source two of whose columns
  expose one name, each with the diagnostic [Sources](#sources) states.
- A `WITH` clause outside the subset, on a read and on a write alike: a
  `WITH RECURSIVE` clause and a data-modifying body, each with the diagnostic
  [Sources](#sources) states. Both are rejected whether or not a source reads
  the clause, because every body is analyzed.
- A join kind outside a plain inner or left join, each with the diagnostic
  [Sources](#sources) states, which says what to write instead for `RIGHT`,
  `CROSS`, `NATURAL`, `USING`, and a comma-separated `FROM` list, and a join
  that does not carry exactly one `ON` condition.
- A column reference of a predicate subquery that no level in scope exposes, or
  that two sources of its own level expose, each with the diagnostic
  [Predicates](#predicates) states.
- An uncast placeholder in a location sqlcj does not analyze, including
  `ORDER BY $1`, a projection such as `SELECT $1 AS x`, a grouping list, a
  `HAVING` predicate, a subquery outside the analyzed predicate forms, such as
  the query of an `= ANY`, a
  named placeholder under a function such as `lower(name) = :name`, a computed
  `LIKE` pattern such as
  `'%' || $1 || '%'`, a placeholder as the
  tested value of a range such as `$1 BETWEEN id AND id`, a computed range
  bound such as `id BETWEEN $1 + 1 AND $2`, a computed pagination value such as
  `LIMIT $1 + 1` or `OFFSET $1 + 1`, a `LIMIT a, b` row count such as
  `LIMIT 5, $1`, a placeholder inside a write value such as
  `COALESCE($2, bio)`, and a `FETCH FIRST $1 ROWS ONLY` clause, so dynamic `IN`
  expansion is unavailable. Each is rejected as `Cannot infer the type of $1 in
  $1 + 1; state it with a cast, such as $1::text`, which names the placeholder
  as the query spells it and, after `in`, the smallest expression the query
  writes around it, on one line, or the upper-case keyword of the clause the
  placeholder stands alone in, such as `ORDER BY` for `ORDER BY $1` and
  `SELECT` for `SELECT $1 AS x`. A cast states the type of such a placeholder in
  every one of those locations, and it does not accept a placeholder that is not
  the direct operand of its cast, such as `(:x)::int`.
- A column reference written with an array subscript or slice wherever a direct
  column is required, because it reads one element or one range rather than the
  column: `SELECT labels[1] AS first` and `SELECT labels[1:2] AS pair` are
  rejected as computed projections, `RETURNING labels[1]` as a computed returned
  item, and an uncast placeholder beside one — `labels[1] = $1`,
  `$1 = labels[1]`, `labels[1] IN ($1, $2)`, `labels[1] LIKE $1`,
  `scores[1] BETWEEN $1 AND $2`, `labels[1] = ANY($1)`, a join
  `ON u.labels[1] = $1`, a write's `WHERE labels[1] = $1`, a
  `DO UPDATE ... WHERE stages.labels[1] = $1`, `SET labels[1] = $1`, and
  `INSERT INTO stages (id, labels[1]) VALUES ($1, $2)` — as an unanalyzed
  placeholder location. A cast states the type in each of those locations, and
  the array column of a subscripted write target must still exist.
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
- An `INSERT` without an explicit column list; an `INSERT` whose rows come from
  neither parenthesized `VALUES` rows nor a `SELECT`, such as `VALUES ROW(1)`,
  `VALUES (1), 2`, or a column list with `DEFAULT VALUES`, rejected as
  `INSERT requires parenthesized VALUES rows or a SELECT.`; a `VALUES` row or
  an insert `SELECT` that does not write one value per written column, rejected
  as `INSERT column and value counts must match.`; an `UPDATE` assignment that
  sets a column list, such as `SET (name, active) = ('a', TRUE)`; and a written
  column that the table does not declare.
- A conflict clause outside the accepted upsert shape, on an `:exec` and on a
  returning insert alike: a `DO UPDATE` without a conflict target, which is
  rejected naming the two targets it accepts, and a target element that is an
  expression or carries a collation or an operator class. A `DO UPDATE`
  assignment that sets a column list, and an unknown target, assigned, or
  `EXCLUDED` column, are rejected as the write forms above are, and so is an
  unqualified conflict-predicate column that both the inserted table and
  `EXCLUDED` carry, or a predicate column qualified with a name its own
  predicate does not read, such as `EXCLUDED` in a conflict-target predicate.
- An aliased or modified `RETURNING` wildcard, a wildcard of a source other
  than the write target, a bare `*` on a joined write, and a computed or
  unknown `RETURNING` item, and `RETURNING` on `:exec`; each is rejected as
  [`RETURNING`](#returning) states.
- The write shapes that change rows of more than the one analyzed table, on an
  `:exec` and on a returning write alike: an `UPDATE` that names several
  tables before `SET`, written with a comma or with a join, rejected as
  `UPDATE of several tables is not supported; write the other sources in
  UPDATE ... FROM.`; and a joined `DELETE`, such as
  `DELETE a FROM a JOIN b`, rejected as
  `A joined DELETE is not supported; write the other sources in
  DELETE ... USING.`
- Any annotation other than `:one`, `:optional`, `:many`, and `:exec`.

An unexpected failure of schema parsing, query analysis, or generation is not a
diagnostic of the compiled input: it is reported as one line that names the
step's own input and ends `; please report this sqlcj defect`, such as
`Internal error while analyzing query 'GetUser' in queries.sql at line 1;
please report this sqlcj defect`. The line carries neither the message nor the
class of the failure, because neither describes the compiled input.

### Not analyzed

These are outside the subset and are not part of the contract. sqlcj neither
models nor rejects them, so a statement that uses one may still compile while
the generated Java describes only the part sqlcj did analyze. Do not rely on
them:

- `DISTINCT`, `GROUP BY`, and `HAVING`,
- predicate forms other than the listed comparisons, `AND`/`OR`/`NOT`, fixed
  `IN` lists, pattern placeholders, null tests, ranges, list predicates, and
  the analyzed subquery forms, such as `LIKE` with a literal pattern or a range
  whose bounds are both literal such as `id BETWEEN 1 AND 10`,
- subqueries outside a `FROM` or `JOIN` item and the analyzed predicate forms,
  such as `= ANY (SELECT ...)`, `IN ((SELECT ...))`, a bare boolean subquery,
  and one in a projection, `GROUP BY`, `HAVING`, `ORDER BY`, a written value,
  `RETURNING`, or a function argument, whose tables and columns PostgreSQL
  rather than sqlcj checks,
- the unique index an accepted `ON CONFLICT` target matches, the constraint an
  `ON CONSTRAINT` target names, the column references of an `EXCLUDED` value
  that is not exactly `EXCLUDED.column`, and a conflict-predicate column that
  no placeholder is compared with, as in a `WHERE` clause.

A clause that is not analyzed still binds the placeholders a cast types inside
it, in textual order, and nothing else of it is checked. A cast placeholder in a
`GROUP BY`, `HAVING`, `ORDER BY`, or unanalyzed subquery therefore generates one
typed method parameter while PostgreSQL rather than sqlcj checks the surrounding
expression, including its tables and columns.

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
- `QueryAnalyzerTest.shouldEmbedTheRowOfEachQualifiedWildcardOfAJoinedRead`,
  `QueryAnalyzerTest.shouldEmbedOneTableRowUnderEachOfItsAliases`,
  `QueryAnalyzerTest.shouldEmbedTheRowOfAWildcardBesideAScalarCount`,
  `QueryAnalyzerTest.shouldEmbedTheRowOfASingleSourceWildcardBesideAnotherItem`,
  `QueryAnalyzerTest.shouldExpandTheWildcardOfADerivedSourceAsItsOwnColumns`,
  `QueryAnalyzerTest.shouldResolveRowTableForALoneJoinedWildcard`, and
  `QueryAnalyzerTest.shouldNotResolveRowTableForALoneLeftJoinedWildcard` cover
  the embedded rows of a wildcard: two qualified wildcards and a bare one over
  joined sources, one table under two aliases, a wildcard beside a column and
  beside a count, the shared row of a lone joined wildcard, and the derived and
  left-joined wildcards that stay expanded columns;
  `JavaCodeGeneratorTest.shouldGenerateResultRecordEmbeddingTheRowOfEachJoinedSource`,
  `JavaCodeGeneratorTest.shouldGenerateRowRecordAndEnumOnlyForAnEmbeddedRow`,
  and
  `JavaCodeGeneratorTest.shouldDisambiguateAnEmbeddedRowComponentFromAColumnOfTheSameName`
  cover the generated record, its inline positional reads and imports, the row
  record and enum an embedded row alone generates, the absent mapper field, and
  the component-name collision;
  `SqlcjCompilerIntegrationTest.shouldGenerateOneSharedRowRecordForAnEmbeddedAndAWholeRowEntry`
  compiles one package whose row record is shared by an entry that embeds the
  table and an entry that returns it whole, and
  `PostgresIntegrationTest.shouldExecuteGeneratedJoinedWildcardReadIntoSharedRowTypesAgainstPostgres`
  executes a joined wildcard read against PostgreSQL 16 whose components are
  instances of the shared row records.
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
  `QueryAnalyzerTest.shouldNameNamedPaginationParameterAfterItsPlaceholder`,
  `QueryAnalyzerTest.shouldResolveCastPaginationParameters`,
  `QueryAnalyzerTest.shouldResolveCastPaginationParametersInTextualBindingOrder`,
  `QueryAnalyzerTest.shouldTypeCastPaginationParameterByItsCast`, and
  `QueryAnalyzerTest.shouldRejectPlaceholderInUnsupportedPaginationValue` cover
  pagination, including the cast values and their clause names, and
  `PostgresIntegrationTest.shouldExecuteGeneratedPaginationAgainstPostgres` and
  `PostgresIntegrationTest.shouldExecuteGeneratedCastPaginationAgainstPostgres`
  execute a `LIMIT ... OFFSET ...` page and the same page written as
  `OFFSET ... LIMIT ...`, uncast and cast, against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolveCastPlaceholdersOfOrderBy`,
  `QueryAnalyzerTest.shouldNameIndexedCastPlaceholderOfOrderByAfterItsIndex`,
  `QueryAnalyzerTest.shouldResolveCastPlaceholderOfEveryReadClause`,
  `QueryAnalyzerTest.shouldNameIndexedCastPlaceholderOfGroupPredicateAfterItsIndex`,
  `QueryAnalyzerTest.shouldResolveCastPlaceholderOfSubquery`,
  `QueryAnalyzerTest.shouldResolveCastPlaceholderOfWithBody`,
  `QueryAnalyzerTest.shouldShareOneParameterBetweenAgreeingCastsOfDifferentClauses`,
  `QueryAnalyzerTest.shouldRejectConflictingCastsOfDifferentClauses`,
  `QueryAnalyzerTest.shouldRejectUncastPlaceholderOutsideTheAnalyzedClauses`, and
  `QueryAnalyzerTest.shouldRejectUnsupportedCastTypeOutsideTheAnalyzedClauses`
  cover the cast placeholders of the ordering, grouping, group-predicate,
  pagination, `FETCH`, `WITH`, and subquery clauses, their names, their shared
  and conflicting repetitions, and the uncast, indirect, and unmapped forms that
  stay rejected, while
  `SqlParserTest.shouldReportPlaceholderOccurrencesInTextualOrder` and
  `SqlParserTest.shouldReportTheCastThatCastsAPlaceholderDirectly` cover the
  syntax-level occurrence order and the cast each occurrence carries,
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableRepositoryForCastPlaceholdersOfEveryClause`
  compiles a repository generated from those clauses, and
  `PostgresIntegrationTest.shouldExecuteGeneratedDynamicSortAgainstPostgres` and
  `PostgresIntegrationTest.shouldExecuteGeneratedExistsSubqueryAgainstPostgres`
  execute a dynamic sort over two sort keys and an `EXISTS` membership check
  against PostgreSQL 16.
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
  `QueryAnalyzerTest.shouldRejectUnsupportedCastProjectionForm`,
  `QueryAnalyzerTest.shouldResolveCastPlaceholderProjection`, and
  `QueryAnalyzerTest.shouldNumberNamedProjectionPlaceholderBeforeThePredicateOne`
  cover both cast
  spellings and both alias spellings, the nullable column of the cast type
  including a declared enum and an array, the missing alias and the unmapped
  cast type, and the projected cast placeholder, its name, and its logical and
  binding positions, and
  `PostgresIntegrationTest.shouldExecuteGeneratedCastProjectionAgainstPostgres`
  executes a `:one` cast `SUM` against PostgreSQL 16 whose `BigDecimal`
  component is the sum and `null` when no row matches.
- `QueryAnalyzerTest.shouldAnalyzeLeftJoinWithNullableJoinedColumns`,
  `QueryAnalyzerTest.shouldExpandAllColumnsOfLeftJoinedSourceAsNullable`,
  `QueryAnalyzerTest.shouldExpandQualifiedAllColumnsOfLeftJoinedSourceAsNullable`,
  `QueryAnalyzerTest.shouldAnalyzeLeftJoinAfterInnerJoin`,
  `QueryAnalyzerTest.shouldAnalyzeInnerJoinAfterLeftJoin`,
  `QueryAnalyzerTest.shouldResolveLeftJoinedParametersInTextualBindingOrder`,
  and `QueryAnalyzerTest.shouldRejectUnsupportedJoinModifier` cover both
  left-join spellings, the nullable columns of a left-joined source, the
  chained join orders, the unchanged parameters, and the join kinds that stay
  rejected, and
  `PostgresIntegrationTest.shouldExecuteGeneratedLeftJoinAgainstPostgres`
  executes a left join against PostgreSQL 16 whose unmatched row reads the
  joined columns as `null`.
- `QueryAnalyzerTest.shouldAnalyzeJoinConditionOfAnyBooleanExpression`,
  `QueryAnalyzerTest.shouldReadFilteredLeftJoinedColumnsAsNullable`,
  `QueryAnalyzerTest.shouldResolveJoinConditionParameterInTextualBindingOrder`,
  `QueryAnalyzerTest.shouldResolveNamedJoinConditionParameter`,
  `QueryAnalyzerTest.shouldTypeCastJoinConditionPlaceholderFromItsCast`,
  `QueryAnalyzerTest.shouldRejectJoinConditionReferenceToLaterSource`,
  `QueryAnalyzerTest.shouldRejectUnknownJoinConditionColumn`, and
  `QueryAnalyzerTest.shouldRejectJoinWithoutOneOnCondition` cover a composite
  key, a comparison other than equality, a condition over one source alone, an
  unqualified reference, `ON TRUE`, the nullability of a filtered left join,
  the uncast, named, and cast placeholder forms and their binding order, the
  join scope, and the joins that carry no single `ON` condition;
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForJoinConditions`
  compiles a repository over them, and
  `PostgresIntegrationTest.shouldExecuteGeneratedFilteredLeftJoinAgainstPostgres`
  executes a filtered left join against PostgreSQL 16 whose filtered-out and
  unmatched rows read the joined components as `null`.
- `QueryAnalyzerTest.shouldResolveCommonTableExpressionSource`,
  `QueryAnalyzerTest.shouldResolveChainedCommonTableExpressions`,
  `QueryAnalyzerTest.shouldReadTheSchemaTableInsideACommonTableExpressionNamedLikeIt`,
  `QueryAnalyzerTest.shouldResolveCommonTableExpressionDeclaredInsideASubquerySource`,
  `QueryAnalyzerTest.shouldResolveSubquerySourceOfAJoin`,
  `QueryAnalyzerTest.shouldReadColumnsOfALeftJoinedDerivedSourceAsNullable`,
  `QueryAnalyzerTest.shouldResolveCountAndCastColumnsThroughADerivedSource`,
  `QueryAnalyzerTest.shouldResolveQuerySpecificResultForAllColumnsOfADerivedSource`,
  `QueryAnalyzerTest.shouldBindDerivedSourceParametersInTextualOrder`,
  `QueryAnalyzerTest.shouldAnalyzeACommonTableExpressionReadOnlyInsideAPredicateSubquery`,
  `QueryAnalyzerTest.shouldAcceptMaterializedCommonTableExpression`,
  `QueryAnalyzerTest.shouldRejectAnOuterSourceReferenceInsideASubquerySource`, and
  `QueryAnalyzerTest.shouldRejectExcludedDerivedSourceForm` cover the derived
  sources: a `WITH` source whose placeholder is typed and named from its own
  column, chained clauses, a clause named like a table whose body reads that
  table, a clause declared inside a subquery source, a joined subquery, the
  nullable columns of a left-joined derived source selected by name and by
  wildcard, an aliased `COUNT(*)` and an aliased cast read through a derived
  source, the query-specific result of `SELECT *` over one, the textual binding
  order across a `WITH` body, a subquery source, and the outer query, a clause
  read only inside a predicate subquery, the accepted `MATERIALIZED` spellings,
  the outer source a subquery body does not see, and every rejected
  derived-source and `WITH` form, including a data-modifying body reported
  without an internal error;
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForDerivedSources`
  compiles a repository over a left-joined `WITH` report and a paged subquery
  source, and
  `PostgresIntegrationTest.shouldExecuteGeneratedDerivedSourcesAgainstPostgres`
  executes both against PostgreSQL 16, reading the unmatched report row's
  derived column as `null`.
- `QueryAnalyzerTest.shouldTypePredicateSubqueryPlaceholderFromItsOwnColumn`,
  `QueryAnalyzerTest.shouldTypePredicateSubqueryPlaceholderOfAJoinCondition`,
  `QueryAnalyzerTest.shouldTypePredicateSubqueryPlaceholderOfAnUpdate`,
  `QueryAnalyzerTest.shouldTypePredicateSubqueryPlaceholderOfADelete`,
  `QueryAnalyzerTest.shouldTypePredicateSubqueryPlaceholderOfAConflictPredicate`,
  `QueryAnalyzerTest.shouldTypeCorrelatedPlaceholderFromAnEnclosingSource`,
  `QueryAnalyzerTest.shouldTypeNestedPredicateSubqueryPlaceholdersFromEveryLevel`,
  `QueryAnalyzerTest.shouldResolveAnUnqualifiedColumnInTheInnermostLevelThatHasIt`,
  `QueryAnalyzerTest.shouldResolveAReusedAliasInTheInnermostLevel`,
  `QueryAnalyzerTest.shouldAcceptOneColumnNameExposedByTwoLevels`,
  `QueryAnalyzerTest.shouldRejectAnUnknownColumnNamingEveryVisibleSource`,
  `QueryAnalyzerTest.shouldRejectAnAmbiguousColumnWithinOnePredicateSubqueryLevel`,
  `QueryAnalyzerTest.shouldShareOneParameterBetweenTwoLevelsOfOneName`,
  `QueryAnalyzerTest.shouldBindPredicateSubqueryParametersInTextualOrder`,
  `QueryAnalyzerTest.shouldTypePlaceholderOfANegatedPredicate`,
  `QueryAnalyzerTest.shouldAcceptPredicateSubquerySelectItems`,
  `QueryAnalyzerTest.shouldAcceptAnUnmappedColumnOfAPredicateSubquerySelectList`,
  `QueryAnalyzerTest.shouldRejectAnUnknownColumnOfAPredicateSubquerySelectList`,
  `QueryAnalyzerTest.shouldRejectASetOperationInsideAPredicateSubquery`, and
  `QueryAnalyzerTest.shouldResolveAWriteCommonTableExpressionInsideItsPredicateSubquery`
  cover the predicate subqueries: each analyzed form in a read's `WHERE`, a
  join `ON` condition, an `UPDATE`, a `DELETE`, and a `DO UPDATE` predicate,
  with the executable SQL unchanged; a correlated placeholder typed from a
  qualified and an unqualified outer column and from every level of a nested
  subquery; an unqualified column and a reused alias resolved in the innermost
  level; one column name exposed by two levels accepted; an unknown column
  naming every visible source innermost outward and an ambiguous column of one
  level rejected; one name shared by two levels as one parameter; the textual
  binding order across levels; the placeholder of a negated predicate; the
  select-list items a subquery accepts, an unmapped column included, and the
  unknown one it rejects; the set operation rejected inside one; and a write's
  `WITH` clause read by its own predicate subquery;
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForPredicateSubqueries`
  compiles a repository over uncast `EXISTS`, `NOT EXISTS`, and `IN` membership
  checks, and
  `PostgresIntegrationTest.shouldExecuteGeneratedUncastPredicateSubqueriesAgainstPostgres`
  executes an uncast membership check and a correlated `NOT EXISTS` filter
  against PostgreSQL 16.
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
  shapes, and
  `QueryAnalyzerTest.shouldAnalyzeReturningWriteWithACommonTableExpression` and
  `QueryAnalyzerTest.shouldAnalyzeCommonTableExpressionOfAWriteWithoutReturning`
  cover the `WITH` clause of a returning and of an `:exec` write, whose body
  types its own placeholders while the returned columns stay the target's.
- `QueryAnalyzerTest.shouldTypeUpdateWhereParameterFromAnAddedSourceColumn`,
  `QueryAnalyzerTest.shouldTypeDeleteWhereParameterFromAUsingSourceColumn`,
  `QueryAnalyzerTest.shouldTypeJoinConditionParameterOfAnUpdateFromClause`,
  `QueryAnalyzerTest.shouldResolveCommaSeparatedUpdateFromSources`,
  `QueryAnalyzerTest.shouldResolveSubquerySourceOfAnUpdateFromClause`,
  `QueryAnalyzerTest.shouldResolveCommonTableExpressionSourceOfADeleteUsingClause`,
  `QueryAnalyzerTest.shouldRejectAnUpdateFromJoinConditionOutsideItsJoinGroup`,
  `QueryAnalyzerTest.shouldResolveNullabilityOfJoinedWriteReturningColumns`,
  `QueryAnalyzerTest.shouldResolveRowTableForATargetWildcardOfAWrite`,
  `QueryAnalyzerTest.shouldNotResolveRowTableForATargetWildcardBesideAnotherColumn`,
  `QueryAnalyzerTest.shouldEmbedTheTargetRowOfABareReturningWildcardBesideAnotherColumn`,
  `QueryAnalyzerTest.shouldRejectAnUnsupportedJoinedWriteReturningWildcard`,
  `QueryAnalyzerTest.shouldRejectAnUnknownReturningWildcardQualifier`,
  `QueryAnalyzerTest.shouldRejectARepeatedJoinedWriteSourceName`,
  `QueryAnalyzerTest.shouldBindJoinedUpdateParametersInTextualOrder`, and
  `QueryAnalyzerTest.shouldShareOneNamedParameterAcrossAJoinedUpdate` cover
  `UPDATE ... FROM` and `DELETE ... USING`: a `WHERE` placeholder typed from a
  column of an added source, a join condition's own placeholders, a
  comma-separated list, a subquery and a common table expression as sources
  with their bodies' placeholders typed, a join condition that names the
  target or an earlier comma-separated source rejected as an unknown
  qualifier, the nullability of a returned column of a left-joined source, the
  target's shared row returned by `RETURNING <target>.*` on a joined and on a
  single-table write, the query-specific result in which a qualified and a bare
  target wildcard beside another column embeds the target's row, the bare `*`,
  other-source wildcard, aliased wildcard, unknown
  qualifier, and repeated source-name rejections, and the textual binding
  order across `SET`, the added sources, and `WHERE`, with indexed and named
  placeholders, while the executable SQL stays the query's own.
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForJoinedWrites`
  compiles the generated sources of a joined update, a left-joined update, and
  a `USING` delete, and
  `PostgresIntegrationTest.shouldExecuteGeneratedJoinedWritesAgainstPostgres`
  executes a joined update returning the target's shared row and a `USING`
  delete returning a column of each source against PostgreSQL 16.
- `QueryAnalyzerTest.shouldAnalyzeInsertWithSeveralValuesRows`,
  `QueryAnalyzerTest.shouldAnalyzeIndexedInsertWithSeveralValuesRows`,
  `QueryAnalyzerTest.shouldAnalyzeInsertSelectWithoutFrom`,
  `QueryAnalyzerTest.shouldNameAnInsertSelectCastItemAfterItsTargetColumn`,
  `QueryAnalyzerTest.shouldNotTypeAnInsertSelectItemOfASubscriptedTarget`,
  `QueryAnalyzerTest.shouldAnalyzeInsertSelectFromATable`,
  `QueryAnalyzerTest.shouldCountOneInsertSelectItemPerWildcardColumn`,
  `QueryAnalyzerTest.shouldAnalyzeInsertSelectReadingACommonTableExpressionOfTheInsert`,
  `QueryAnalyzerTest.shouldAnalyzeACommonTableExpressionOfAnInsertSelect`,
  `QueryAnalyzerTest.shouldAnalyzeACommonTableExpressionOfAValuesSource`,
  `QueryAnalyzerTest.shouldAnalyzeParenthesizedValuesRowsAtAnyDepth`,
  `QueryAnalyzerTest.shouldAnalyzeSeveralValuesRowsWithConflictAndReturningAllColumns`,
  `QueryAnalyzerTest.shouldAnalyzeInsertSelectWithConflictUpdateAndReturningColumns`,
  `QueryAnalyzerTest.shouldBindInsertSelectParametersInTextualOrder`,
  `QueryAnalyzerTest.shouldRejectAnInsertRowSourceOutsideTheSubset`,
  `QueryAnalyzerTest.shouldRejectInsertColumnAndValueCountMismatch`,
  `QueryAnalyzerTest.shouldRejectASetOperationAsAnInsertRowSource`, and
  `QueryAnalyzerTest.shouldRejectAnInsertSelectItemOutsideItsSources` cover the
  multi-row and `SELECT`-sourced inserts: several rows with a repeated named
  placeholder and with repeated indexed placeholders beside a `DEFAULT`; the
  `FROM`-less `SELECT` of an `unnest` bulk insert; a cast item typed by its
  cast and named after its target column; a subscripted target that types
  nothing; a `SELECT` over joined tables whose `ON`, `WHERE`, and `LIMIT`
  placeholders are typed by the clause rules; the wildcard items counted per
  source column; both `WITH` placements and a `WITH` on a `VALUES` source;
  parenthesized rows at any depth, with and without `RETURNING`;
  `ON CONFLICT DO NOTHING` with `RETURNING *` on several rows and
  `DO UPDATE ... WHERE` with a returned column list on a `SELECT` source; the
  textual binding order across the insert's `WITH` body, its items, its
  `WHERE`, and its conflict clause; and the row-source, count-mismatch,
  set-operation, unknown-column, and target-qualifier rejections. The
  generated sources of these inserts are compiled by
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForMultiRowAndSelectSourcedInserts`,
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedMultiRowAndSelectSourcedInsertsAgainstPostgres`
  executes a multi-row insert, an `unnest` bulk insert with
  `ON CONFLICT DO NOTHING RETURNING *`, and a table-sourced insert with a
  `WHERE` placeholder against PostgreSQL 16.
- `QueryAnalyzerTest.shouldNameInsertReturningColumnsFromTheirAliases`,
  `QueryAnalyzerTest.shouldNameUpdateReturningColumnFromABareAlias`,
  `QueryAnalyzerTest.shouldNameDeleteReturningColumnQualifiedThroughTheTargetAlias`,
  and `QueryAnalyzerTest.shouldNameReturningColumnFromAQuotedAlias` cover an
  aliased returned column on each write statement, both alias spellings, a
  quoted alias, a column qualified through the target alias, and the schema
  type, nullability, parameters, and binding order an alias leaves unchanged,
  while `QueryAnalyzerTest.shouldRejectUnsupportedReturningItem` covers the
  aliased `*` and aliased computed items that stay rejected.
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableJavaForAliasedReturningWrites`
  compiles result records named from the aliases, and
  `PostgresIntegrationTest.shouldExecuteGeneratedAliasedReturningWritesAgainstPostgres`
  executes the three aliased returning writes against PostgreSQL 16 and reads
  the aliased components.
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
  returning row type, and the conflict forms, untyped placeholder, and
  unknown target, assigned, and `EXCLUDED` columns that are rejected on an
  `:exec` and on a returning insert alike, and
  `PostgresIntegrationTest.shouldExecuteGeneratedUpsertsAgainstPostgres`
  executes a `DO NOTHING` and a `DO UPDATE` action, each with and without
  `RETURNING`, over a new row and then a conflicting one against
  PostgreSQL 16.
- `QueryAnalyzerTest.shouldAnalyzeUpsertWithoutAConflictTarget`,
  `QueryAnalyzerTest.shouldAnalyzeUpsertOnANamedConstraint`,
  `QueryAnalyzerTest.shouldAnalyzeAPlaceholderInAConflictTargetPredicate`,
  `QueryAnalyzerTest.shouldAnalyzeANamedPlaceholderInAConflictTargetPredicate`,
  `QueryAnalyzerTest.shouldAnalyzePlaceholdersInAConflictUpdatePredicate`,
  `QueryAnalyzerTest.shouldAnalyzeAConflictUpdatePredicateThroughTheInsertedTableAlias`,
  `QueryAnalyzerTest.shouldBindConflictPredicatesInTextualOrder`, and
  `QueryAnalyzerTest.shouldRejectAConflictPredicateColumnOutsideItsScope` cover
  the conflict forms that name no column target: a targetless `DO NOTHING`, a
  named constraint with either action, both predicates and the scope each one
  reads, their placeholder types and names, their textual binding order, and
  the ambiguous and out-of-scope predicate columns, and
  `PostgresIntegrationTest.shouldExecuteGeneratedConflictClausesAgainstPostgres`
  executes each of those forms against PostgreSQL 16, including a target
  predicate that selects a partial unique index and a `DO UPDATE ... WHERE`
  that updates once and then skips the row.
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

Unsupported queries and internal errors:

- `SqlcjCompilerIntegrationTest.shouldRejectUnsupportedQueryBeforeWriting`
  covers the whole located diagnostic line, and the absence of generated
  output, of each rejected input: the set operations written directly and in
  parentheses, the `VALUES`, `TABLE`, `MERGE`, `TRUNCATE`, `EXPLAIN`,
  `EXECUTE`, `CREATE TABLE`, and `SET` statements, a window and a
  concatenation projection, a computed `RETURNING` item, the `RIGHT`, `FULL`,
  `USING`, `NATURAL`, `CROSS`, comma, and `LATERAL` joins, a column list on a
  subquery alias, a subquery source without an alias, a `WITH RECURSIVE`
  clause, a data-modifying `WITH` body, a set operation as an insert's row
  source, an insert whose `SELECT` writes fewer values than its column list,
  a comma-joined and a join-joined multi-table `UPDATE`, a joined `DELETE`,
  and a bare `RETURNING *` on a joined update, while
  `QueryAnalyzerTest.shouldRejectUnsupportedCountProjectionForm`,
  `QueryAnalyzerTest.shouldRejectUnsupportedJoinModifier`,
  `QueryAnalyzerTest.shouldRejectUnsupportedReturningItem`,
  `QueryAnalyzerTest.shouldRejectExcludedReturningWriteForm`,
  `QueryAnalyzerTest.shouldRejectExcludedDerivedSourceForm`, and
  `QueryAnalyzerTest.shouldRejectAnInsertRowSourceOutsideTheSubset` cover the
  projection, join, derived-source, and write rejections per form, including
  the modifiers that keep the general join rejection, and
  `QueryAnalyzerTest.shouldAnalyzeParenthesizedSelectAsTheQueryItEncloses`,
  `QueryAnalyzerTest.shouldAnalyzeInsertWhoseValuesRowIsParenthesized`, and
  `QueryAnalyzerTest.shouldAnalyzeParenthesizedValuesRowsAtAnyDepth` cover
  the doubly parenthesized read that analyzes like the query it encloses and
  the parenthesized `VALUES` rows that analyze like the rows they write.
- `SqlcjCompilerIntegrationTest.shouldReportAnUnexpectedSchemaFailureAsAnInternalError`,
  `SqlcjCompilerIntegrationTest.shouldReportAnUnexpectedAnalysisFailureAsAnInternalError`,
  `SqlcjCompilerIntegrationTest.shouldReportAnUnexpectedGenerationFailureAsAnInternalError`,
  and
  `SqlcjCompilerIntegrationTest.shouldReportAnUnexpectedSharedGenerationFailureAsAnInternalError`
  inject an unexpected failure into the schema, analysis, repository
  generation, and shared row-record steps and cover the exact internal-error
  line of each, that it carries neither the failure's message nor its class,
  including a blank-message unsupported-operation failure and a
  `NumberFormatException`, and that no file is written.

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
- `QueryAnalyzerTest.shouldRejectAnUntypedPlaceholder`,
  `QueryAnalyzerTest.shouldRejectAnUntypedPlaceholderOfAConcatenatedPattern`,
  `QueryAnalyzerTest.shouldRejectTheFirstUntypedPlaceholderOfAQuery`,
  `SqlParserTest.shouldReportTheEnclosingExpressionOfAPlaceholder`,
  `SqlParserTest.shouldReportTheClauseOfAPlaceholderThatStandsAlone`, and
  `SqlcjCompilerIntegrationTest.shouldRejectUntypedPlaceholderBeforeWriting`
  cover the untyped-placeholder diagnostic, in both placeholder forms, with the
  enclosing expression and the clause keyword it names, the first occurrence it
  reports, and the whole located line of a compiled source.
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
  `QueryAnalyzerTest.shouldRejectCastOccurrenceWithConflictingType`, and the
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
- `QueryAnalyzerTest.shouldRejectASubscriptedProjection`,
  `QueryAnalyzerTest.shouldAnalyzeACastSubscriptedProjection`,
  `QueryAnalyzerTest.shouldRejectASubscriptedReturningItem`,
  `QueryAnalyzerTest.shouldRejectAnUncastPlaceholderBesideASubscriptedColumn`,
  `QueryAnalyzerTest.shouldNameACastPlaceholderBesideASubscriptedColumnAfterItself`,
  `QueryAnalyzerTest.shouldNameACastPlaceholderOfASubscriptedWriteTargetAfterItself`,
  `QueryAnalyzerTest.shouldRejectAnUnknownSubscriptedWriteTarget`, and
  `QueryAnalyzerTest.shouldAnalyzeALiteralAssignedToASubscriptedColumn` cover a
  subscripted and a sliced reference in qualified and unqualified spellings:
  the projection and `RETURNING` rejections, the cast projection that states its
  type, the untyped-placeholder diagnostic in each typing location, the name a
  cast placeholder keeps beside one, the write target whose array column must
  exist, and the non-binding value assigned to one, and
  `PostgresIntegrationTest.shouldReadAndFilterASubscriptedArrayElement` executes
  a cast subscript projection and a cast subscript filter against PostgreSQL 16
  as one `String` component and one `String` method parameter.

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

Reference service:

- `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedMilestoneQueryShapes`
  executes the reference service's dynamic sort in both of its modes and a cast
  page with an offset, its `EXISTS` and `IN (SELECT ...)` membership checks and
  its scalar subquery, its composite-key join and the filtered left join that
  names only a live assignee, its untargeted `ON CONFLICT DO NOTHING`, its
  `ON CONFLICT ON CONSTRAINT ... DO UPDATE ... WHERE` that skips an unchanged
  role, its conflict-target predicate over a partial unique index, and the
  aliased `RETURNING` of an optimistic update, against PostgreSQL 16 through the
  generated repository.
- `ReferenceServiceIntegrationTest.shouldExecuteTheGeneratedNestedQueriesAndMultiRowWrites`
  executes the reference service's `NOT EXISTS` check, its CTE report joined to
  `projects`, its aliased subquery source with a `LIMIT` placeholder, its
  two-row insert sharing one placeholder, its
  `INSERT ... SELECT ... unnest(:userIds::bigint[]) ON CONFLICT DO NOTHING`,
  its `INSERT ... SELECT` from a table, its `UPDATE ... FROM` returning the
  shared task row, and its `DELETE ... USING`, against PostgreSQL 16 through
  the generated repository.
- `ReferenceServiceIntegrationTest.shouldRejectEachUnsupportedCatalogueQuery`
  covers the whole located diagnostic line, and the absence of generated
  output, of every entry of the reference service's unsupported query
  catalogue: the two set operations, the `RIGHT`, `FULL`, `USING`, `NATURAL`,
  `CROSS`, comma, and `LATERAL` joins, a recursive and a data-modifying CTE, an
  uncast `lower(:email)`, and a placeholder beside a subscripted column, and
  `ReferenceServiceIntegrationTest.shouldCatalogueExactlyTheUnsupportedQueryFiles`
  covers that the catalogue directory holds exactly those queries.
