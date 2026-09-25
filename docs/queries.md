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
- A result expression that is not a direct column, a wildcard, or the scalar
  count below — such as another function call, an arithmetic expression, or a
  literal — is rejected.
- An explicit alias names the result column, so `SELECT id AS author_id`
  generates the record component `authorId` while the component's type still
  comes from the column and its nullability from the column and its source.
  sqlcj itself resolves an alias only in the projection; every other clause
  reaches the database as written.

A read may count its matching rows with an aliased `COUNT(*)` as its only
projection:

```sql
-- name: CountAuthors :one
SELECT COUNT(*) AS total
FROM authors;
```

- The alias is required and may be written with or without `AS`, so both
  `COUNT(*) AS total` and `COUNT(*) total` generate the result record
  `CountAuthorsResult` with the single component `total`.
- The component is a non-null `Long`: the count is typed `BIGINT` because
  `count(*)` returns `bigint`, and it is never null because a count is `0` when
  no row matches.
- The count is accepted over any supported source, predicate, ordering, and
  pagination shape, and under any annotation.
- `COUNT(*)` without an alias is rejected with
  `COUNT(*) requires a result alias, such as COUNT(*) AS total.`, and a
  `COUNT(*)` beside another projection item is rejected with
  `COUNT(*) must be the only SELECT item.`
- Every other count is still an unsupported result expression, including
  `COUNT(column)`, `COUNT(DISTINCT column)`, `COUNT(t.*)`, a qualified
  `pg_catalog.count(*)`, and the `FILTER` and `OVER` forms.

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
  `code BETWEEN $1 AND $2`.

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
bound such as `code BETWEEN :lo AND :hi` is rejected like any other named
placeholder, while a placeholder as the tested value, as in
`$1 BETWEEN code AND code`, and a placeholder inside a computed bound, as in
`code BETWEEN $1 + 1 AND $2`, are rejected as unanalyzed placeholder locations.

A comparison that binds no placeholder, such as `active = TRUE`, contributes no
generated parameter and reaches the database as written.

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
- A named value such as `LIMIT :n` or `OFFSET :n` is rejected like any other
  named placeholder. A placeholder in a computed value, as in `LIMIT $1 + 1` or
  `OFFSET $1 + 1`, in the `LIMIT a, b` form, as in `LIMIT 5, $1`, and in a
  `FETCH FIRST $1 ROWS ONLY` clause are rejected as unanalyzed placeholder
  locations.

## Writes

A write targets exactly one table of its entry's schema.

| Statement | Accepted shape |
| --- | --- |
| `INSERT` | an explicit column list and a single `VALUES` row whose values are all `$N` placeholders |
| `UPDATE` | `SET` assignments that each assign one direct column a single `$N` placeholder, with an optional `WHERE` using the read predicate forms |
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
column, and `RETURNING` on `:exec` are rejected. `ON CONFLICT`, multi-row
`VALUES`, `INSERT ... SELECT`, `UPDATE ... FROM`, `DELETE ... USING`, and common
table expressions are rejected in a returning write.

## Parameters

sqlcj uses PostgreSQL `$N` placeholders. The compiler replaces each real
placeholder token with a JDBC `?` and leaves every other character of the
statement byte-for-byte unchanged, so `$1` inside a string literal, a quoted
identifier, or a comment is not a parameter.

- Placeholder indexes must be positive and contiguous from `$1`.
- The Java type of a placeholder is the type of the column it is compared with,
  assigned to, or inserted into.
- Anonymous `?` placeholders and named `:name` placeholders are rejected.
- A placeholder in a location sqlcj does not analyze — for example `ORDER BY $1`
  — is rejected rather than left unbound.

### Logical order versus textual order

The two orders are distinct and both are observable:

- **Logical order** is placeholder index order. It is the order of the generated
  method parameters: `$1` is the first method parameter, `$2` the second.
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

An index may repeat. A repeated index produces one method parameter, named and
typed from its first occurrence, and its value is bound at every textual
position where the index occurs. Occurrences of one index whose inferred Java
types differ are rejected.

## Generated Java

Each configuration entry generates one final repository class in the configured
`java.package`, written to the package directory under `java.out` and named
`<sql[].name>Repository`. Every named query of that entry becomes one method of
that repository; sqlcj never generates a class per query.

- The repository has one `dev.sqlcj.runtime.QueryExecutor` field and one
  constructor taking that executor.
- Methods appear in query-source order. A method name is the lower camel form
  of the query name, as in `get_author` and `GetAuthor` to `getAuthor`.
- A `:one`, `:optional`, or `:many` query that returns one complete table row — a
  single-source `SELECT *` or `SELECT <source>.*`, or a write whose `RETURNING`
  clause is exactly `*` — returns the repository's nested
  `<TableName>Row` record. That record and its private `RowMapper` field are
  generated once per table, after the constructor, in the order the queries
  first use them, and every query returning that row shares them.
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
  names the query the application called.
- Generated source imports only `dev.sqlcj.runtime.QueryExecutor`,
  `dev.sqlcj.runtime.RowMapper`, `java.util.List`, `java.util.Optional` when the
  entry declares an `:optional` query, and the JDK types of the mapped columns,
  so the runtime artifact is the only sqlcj dependency a consumer needs.

The `Author` entry of the [Quickstart](quickstart.md), which declares
`CreateAuthor`, `GetAuthor`, `FindAuthor`, `ListAuthors`, `UpdateAuthorBio`, and
`DeleteAuthor`, generates one `AuthorRepository`:

```java
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

    public record AuthorsRow(
        Long id,
        String name,
        String bio,
        LocalDateTime createdAt
    ) {
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
`Optional<AuthorsRow>`, and `listAuthors` returns `List<AuthorsRow>`.

The application constructs the repository once per execution context:

```java
AuthorRepository authors = new AuthorRepository(new JdbcQueryExecutor(dataSource));

AuthorRepository.AuthorsRow author = authors.getAuthor(1L);

Optional<AuthorRepository.AuthorsRow> found = authors.findAuthor(2L);
```

A query name, projection alias, column name, and parameter name becomes a
conventional Java name by one deterministic camel-case rule set, and names that
would collide inside one generated repository are disambiguated in their SQL
order. Those rules, the rejection of two queries of one entry that generate the
same method, and the rejection of two entries whose repository files would
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

- A result expression that is not a direct column, a wildcard, or an aliased
  sole `COUNT(*)`, including another function call, another aggregate, and a
  literal. A `COUNT(*)` without an alias and a `COUNT(*)` beside another
  projection item each fail with their own diagnostic, while `COUNT(column)`,
  `COUNT(DISTINCT column)`, `COUNT(t.*)`, `pg_catalog.count(*)`, and the
  `FILTER` and `OVER` forms keep the unsupported-expression rejection.
- A `FROM` item that is not a table, a comma-separated source list, a join that
  is not a plain inner or left join, a join predicate that is not one qualified
  equality, and a set operation such as `UNION`.
- A placeholder in a location sqlcj does not analyze, including `ORDER BY $1`, a
  computed `LIKE` pattern such as `'%' || $1 || '%'`, a placeholder as the
  tested value of a range such as `$1 BETWEEN id AND id`, a computed range
  bound such as `id BETWEEN $1 + 1 AND $2`, a computed pagination value such as
  `LIMIT $1 + 1` or `OFFSET $1 + 1`, a `LIMIT a, b` row count such as
  `LIMIT 5, $1`, and a `FETCH FIRST $1 ROWS ONLY` clause, so dynamic `IN`
  expansion is unavailable.
- A `LIKE`-family pattern placeholder that is negated, uses another keyword such
  as `SIMILAR TO`, carries an `ESCAPE` clause or a `BINARY` modifier, tests a
  non-text column, or stands as the tested value.
- A named range bound such as `id BETWEEN :lo AND :hi` and a named pagination
  value such as `LIMIT :n` or `OFFSET :n`, which fail with the named-placeholder
  diagnostic.
- Anonymous `?` and named `:name` placeholders, and non-contiguous or
  non-positive placeholder indexes.
- An `INSERT` without an explicit column list, with more than one `VALUES` row,
  with a value that is not a placeholder, or built from a `SELECT`; and an
  `UPDATE` assignment that is not a single placeholder.
- An aliased, computed, qualified-wildcard, or unknown `RETURNING` item;
  `RETURNING` on `:exec`; and `ON CONFLICT`, `UPDATE ... FROM`,
  `DELETE ... USING`, or a common table expression in a returning write.
- Any annotation other than `:one`, `:optional`, `:many`, and `:exec`.

### Not analyzed

These are outside the subset and are not part of the contract. sqlcj neither
models nor rejects them, so a statement that uses one may still compile while
the generated Java describes only the part sqlcj did analyze. Do not rely on
them:

- `DISTINCT`, `GROUP BY`, and `HAVING`,
- predicate forms other than the listed comparisons, `AND`/`OR`, fixed `IN`
  lists, pattern placeholders, null tests, and ranges, such as `LIKE` with a
  literal pattern, a range whose bounds are both literal such as
  `id BETWEEN 1 AND 10`, or `IN` with a subquery,
- common table expressions, and subqueries outside the `FROM` item,
- `ON CONFLICT`, `UPDATE ... FROM`, and `DELETE ... USING` on a non-returning
  `:exec` write.

sqlcj itself provides no named parameters, macros, array parameters, dynamic
`IN` expansion, or query-building API.

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
  `QueryAnalyzerTest.shouldRejectNamedRangeBound` cover the range predicates,
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedRangePredicatesAgainstPostgres`
  executes a `BETWEEN` read and a `NOT BETWEEN` read whose bounds use
  out-of-order placeholder indexes against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolvePaginationParametersAfterPredicateParameters`,
  `QueryAnalyzerTest.shouldResolvePaginationParametersInTextualBindingOrder`,
  `QueryAnalyzerTest.shouldResolveOffsetParameterBesideUnanalyzedRowCount`,
  `QueryAnalyzerTest.shouldNotCreateParametersForLiteralPagination`,
  `QueryAnalyzerTest.shouldRejectNamedPaginationValue`, and
  `QueryAnalyzerTest.shouldRejectPlaceholderInUnsupportedPaginationValue` cover
  pagination, and
  `PostgresIntegrationTest.shouldExecuteGeneratedPaginationAgainstPostgres`
  executes a `LIMIT ... OFFSET ...` page and the same page written as
  `OFFSET ... LIMIT ...` against PostgreSQL 16.
- `QueryAnalyzerTest.shouldResolveScalarCountColumnFromItsAlias`,
  `QueryAnalyzerTest.shouldResolveScalarCountBesidePredicateParameter`, and
  `QueryAnalyzerTest.shouldRejectUnsupportedCountProjectionForm` cover the
  aliased sole `COUNT(*)`, its focused diagnostics, and the count and function
  forms that stay rejected, and
  `PostgresIntegrationTest.shouldExecuteGeneratedScalarCountAgainstPostgres`
  compiles a `:one` count into a result record with one `Long` component and
  executes it against PostgreSQL 16 for a matching and a non-matching pattern.
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
- `QueryAnalyzerTest.shouldResolveRowTableForReturningAllColumns` covers the
  returning writes that produce a complete table row.
- `PostgresIntegrationTest.shouldExecuteGeneratedWriteAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedInsertReturningAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedUpdateReturningAgainstPostgres`,
  whose no-row returning update fails its `:one` cardinality check,
  and
  `PostgresIntegrationTest.shouldExecuteGeneratedDeleteReturningAgainstPostgres`
  execute them against PostgreSQL 16.

Parameters:

- `QueryAnalyzerTest.shouldResolveBindingIndexesInTextualOrder`,
  `QueryAnalyzerTest.shouldRetainOneParameterForRepeatedIndex`,
  `QueryAnalyzerTest.shouldRejectRepeatedIndexWithConflictingType`,
  `QueryAnalyzerTest.shouldRejectGappedParameterIndexes`,
  `QueryAnalyzerTest.shouldRejectZeroParameterIndex`,
  `QueryAnalyzerTest.shouldRejectPlaceholderInUnsupportedLocation`,
  `QueryAnalyzerTest.shouldRejectAnonymousParameter`,
  `QueryAnalyzerTest.shouldRejectNamedParameter`, and
  `QueryAnalyzerTest.shouldKeepPlaceholderTextThatIsNotAParameter` cover
  ordering, repetition, and rejection.
- `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedQueryWithOutOfOrderPlaceholders`,
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedQueryWithRepeatedPlaceholder`,
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedUpdateWithOutOfOrderPlaceholders`,
  and
  `SqlcjCompilerIntegrationTest.shouldExecuteGeneratedQueryWithProtectedPlaceholderText`
  execute the compiled binding order.

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
- `PostgresIntegrationTest.shouldExecuteGeneratedFullRowQueriesIntoOneSharedRowTypeAgainstPostgres`
  executes a returning write, a full-row read, and a qualified full-row list of
  one table into one row type against PostgreSQL 16.
