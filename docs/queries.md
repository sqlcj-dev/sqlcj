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

sqlcj accepts three annotations. Any other annotation is rejected.

| Annotation | Accepted statements | Generated return type | Result when no row matches |
| --- | --- | --- | --- |
| `:one` | `SELECT`, or a write with `RETURNING` | the generated result record | `null` |
| `:many` | `SELECT`, or a write with `RETURNING` | `List<`result record`>` | an empty list |
| `:exec` | `INSERT`, `UPDATE`, `DELETE` without `RETURNING` | `int` affected-row count | `0` |

The annotation and the statement must agree:

- a `SELECT` must be `:one` or `:many`,
- a write without `RETURNING` must be `:exec`,
- a write with `RETURNING` must be `:one` or `:many`.

`:one` reads the first row of the result set and does not read any further row,
so a query that matches several rows returns the first one rather than failing.
`:many` returns every row in the order the database produced it.

## Reads

A `SELECT` query is analyzed against the schema snapshot of its own
configuration entry.

### Sources

- The `FROM` item must be a table of that schema, optionally with an alias.
- A table may be joined with `JOIN` or `INNER JOIN`. A comma-separated source
  list and every other join modifier — `LEFT`, `RIGHT`, `FULL`, `OUTER`,
  `CROSS`, `NATURAL`, `SEMI`, `APPLY`, `STRAIGHT`, `GLOBAL`, a join hint, and
  `USING (...)` — are rejected.
- Each join requires exactly one `ON` equality between a qualified column of the
  joined source and a qualified column of a source introduced earlier.
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
- A result expression that is not a direct column or a wildcard, such as a
  function call, an arithmetic expression, or a literal, is rejected.
- An explicit alias names the result column, so `SELECT id AS author_id`
  generates the record component `authorId` while the component's type and
  nullability still come from the column. sqlcj itself resolves an alias only in
  the projection; every other clause reaches the database as written.

### Predicates

A `WHERE` clause may combine:

- the comparisons `=`, `<>`, `>`, `>=`, `<`, `<=` between a direct column and a
  `$N` placeholder, in either order,
- `AND`, `OR`, and parentheses,
- `IN` with a fixed list of `$N` placeholders, such as `id IN ($1, $2)`.

A comparison that binds no placeholder, such as `active = TRUE`, contributes no
generated parameter and reaches the database as written.

### Ordering

`ORDER BY` over direct columns is supported for a stable list order, as in
`ORDER BY id`. sqlcj rewrites only `$N` parameter tokens; the rest of the
statement, including the ordering clause, reaches JDBC exactly as written. A
placeholder in `ORDER BY` is rejected, because it is not an analyzed parameter
location.

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
RETURNING id, name, bio;

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

A supported `INSERT`, `UPDATE`, or `DELETE` declared `:one` or `:many` may end
with a `RETURNING` clause that lists either:

- unaliased direct columns of the target table, in the order they are declared,
  or
- a bare `*`, which expands in the target table's schema column order.

A returning write produces the same generated result record, positional row
mapper, and cardinality behavior as a read, so a database-generated `SERIAL` or
`BIGSERIAL` value is read back with its declared type.

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
- A placeholder in a location sqlcj does not analyze — for example `LIMIT $1` —
  is rejected rather than left unbound.

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
- A `:one` or `:many` query also generates a nested `public record` named
  `<QueryName>Result` in upper camel case, whose components follow the
  selected-column order and are named after each column's projection alias or
  column name, plus a private `RowMapper` field that reads each column by its
  one-based position.
- A `:exec` query generates no result record and returns `int`.
- Generated source imports only `dev.sqlcj.runtime.QueryExecutor`,
  `dev.sqlcj.runtime.RowMapper`, `java.util.List`, and the JDK types of the
  mapped columns, so the runtime artifact is the only sqlcj dependency a
  consumer needs.

The `Author` entry of the [Quickstart](quickstart.md), which declares
`CreateAuthor`, `GetAuthor`, `ListAuthors`, `UpdateAuthorBio`, and
`DeleteAuthor`, generates one `AuthorRepository`:

```java
package com.example.app.db;

import dev.sqlcj.runtime.QueryExecutor;
import dev.sqlcj.runtime.RowMapper;
import java.util.List;

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

    public record CreateAuthorResult(
        Long id,
        String name,
        String bio
    ) {
    }

    private static final RowMapper<CreateAuthorResult> createAuthorRowMapper =
            resultSet -> new CreateAuthorResult(
            resultSet.getObject(1, Long.class),
            resultSet.getObject(2, String.class),
            resultSet.getObject(3, String.class)
    );

    /**
     * Query: CreateAuthor
     * Table: authors
     * Type: ONE
     */
    public CreateAuthorResult createAuthor(String name, String bio) {
        return executor.query(
                """
    INSERT INTO authors (name, bio)
    VALUES (?, ?)
    RETURNING id, name, bio;""",
                java.util.Arrays.asList(name, bio),
                createAuthorRowMapper
        );
    }

    // getAuthor, listAuthors, updateAuthorBio, and deleteAuthor follow here.
}
```

The application constructs the repository once per execution context:

```java
AuthorRepository authors = new AuthorRepository(new JdbcQueryExecutor(dataSource));

AuthorRepository.GetAuthorResult author = authors.getAuthor(1L);
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

- A result expression that is not a direct column or a wildcard, including a
  function call, an aggregate, and a literal.
- A `FROM` item that is not a table, a comma-separated source list, a join that
  is not a plain inner join, a join predicate that is not one qualified
  equality, and a set operation such as `UNION`.
- A placeholder in a location sqlcj does not analyze, including `LIKE $1`,
  `BETWEEN $1 AND $2`, and `LIMIT $1`, so parameterized pagination and dynamic
  `IN` expansion are unavailable.
- Anonymous `?` and named `:name` placeholders, and non-contiguous or
  non-positive placeholder indexes.
- An `INSERT` without an explicit column list, with more than one `VALUES` row,
  with a value that is not a placeholder, or built from a `SELECT`; and an
  `UPDATE` assignment that is not a single placeholder.
- An aliased, computed, qualified-wildcard, or unknown `RETURNING` item;
  `RETURNING` on `:exec`; and `ON CONFLICT`, `UPDATE ... FROM`,
  `DELETE ... USING`, or a common table expression in a returning write.
- Any annotation other than `:one`, `:many`, and `:exec`.

### Not analyzed

These are outside the subset and are not part of the contract. sqlcj neither
models nor rejects them, so a statement that uses one may still compile while
the generated Java describes only the part sqlcj did analyze. Do not rely on
them:

- `DISTINCT`, `GROUP BY`, and `HAVING`,
- predicate forms other than the listed comparisons, `AND`/`OR`, and fixed `IN`
  lists, such as `IS NULL`, `LIKE` with a literal, or `IN` with a subquery,
- common table expressions, and subqueries outside the `FROM` item,
- `LIMIT` and `OFFSET` with literal values,
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
  `DefaultQueryParserTest.rejectsInvalidHeader`,
  `DefaultQueryParserTest.rejectsInvalidQueryType`,
  `DefaultQueryParserTest.rejectsDuplicateQueryNames`, and
  `DefaultQueryParserTest.rejectsQueryWithoutSql` cover the file format.
- `QueryAnalyzerTest.shouldRejectSelectWithoutResultQueryType`,
  `QueryAnalyzerTest.shouldRejectWriteWithoutExecQueryType`,
  `QueryAnalyzerTest.shouldRejectReturningWriteDeclaredAsExec`, and
  `QueryAnalyzerTest.shouldAnalyzeReturningWriteForBothResultQueryTypes` cover
  annotation and statement agreement.
- `JdbcQueryExecutorTest.shouldReturnNullWhenQueryFindsNoRows`,
  `JdbcQueryExecutorTest.shouldReturnEmptyListWhenQueryManyFindsNoRows`,
  `JdbcQueryExecutorTest.shouldReturnAllRowsForQueryMany`, and
  `JdbcQueryExecutorTest.shouldReturnAffectedRowCountForExecute` cover the
  cardinality results.

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
- `PostgresIntegrationTest.shouldExecuteGeneratedWriteAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedInsertReturningAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedUpdateReturningAgainstPostgres`,
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
