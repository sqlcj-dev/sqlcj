# PostgreSQL Support

sqlcj compiles PostgreSQL schema snapshots and queries into Java that runs on
JDBC. This document states the database contract of configuration version `"1"`
and lists the SQL types and `CREATE TABLE` constructs that a schema source may
use.

## Engine Contract

Configuration version `"1"` means PostgreSQL schema and query input and
blocking JDBC execution. There is no engine option and no dialect option in
`sqlcj.yaml`; the full file format is documented in
[Configuration](configuration.md), and the accepted query shapes in
[Queries](queries.md).

The application owns the database connection: sqlcj's runtime
`dev.sqlcj.runtime.JdbcQueryExecutor` executes each generated query as a JDBC
`PreparedStatement`. sqlcj does not ship a JDBC driver, so the application also
supplies the PostgreSQL driver.

## Connection Ownership and Transactions

`JdbcQueryExecutor` has two construction paths. Both share the same positional
parameter binding, row mapping, single-row and multi-row result handling,
affected-row counting, and exception translation, and both accept the same
generated query classes without regeneration:

| Construction | Connection ownership |
| --- | --- |
| `new JdbcQueryExecutor(javax.sql.DataSource)` | The executor obtains one connection per operation and closes it before the operation returns, so each operation runs on that connection's own transaction state, typically one auto-committed statement. |
| `new JdbcQueryExecutor(java.sql.Connection)` | The executor runs every operation on the supplied connection and never closes, commits, or rolls it back, never changes its auto-commit setting, and never otherwise configures it. |

The caller-owned connection path is how several generated operations take part
in one application-controlled transaction: the application disables auto-commit,
runs generated reads and writes through one executor, and then calls `commit` or
`rollback` itself. sqlcj provides no transaction callback or template API, no
savepoints, and no isolation configuration. The [Quickstart](quickstart.md)
runs that pattern end to end.

In both paths the `PreparedStatement` and any `ResultSet` opened for an
operation are closed before that operation returns, on success and on failure.

Every `SQLException` raised while acquiring a connection, preparing a statement,
binding parameters, executing, reading results, or closing a DataSource-acquired
connection is translated into `dev.sqlcj.runtime.QueryExecutionException` with
the message `Failed to execute query` and the `SQLException` as its cause. A
failed operation on a caller-owned connection leaves the connection open, so the
application decides whether to continue or roll back.

The runtime is blocking and synchronous. An executor built on a caller-owned
connection inherits that connection's confinement to a single thread at a time.

Behavior is verified against PostgreSQL 16. The pipeline is executed end to end
against a `postgres:16-alpine` container: the schema snapshot is run as
PostgreSQL DDL, the generated Java is compiled, and the generated classes are
executed through the JDBC runtime. Those tests are skipped when Docker is
unavailable.

H2 is used only inside sqlcj's own tests as a convenience database and is not a
supported target. The PostgreSQL driver, the container library, and H2 are all
test-scoped dependencies of the build.

## Schema Snapshot Input

A `sql[].schema` file is an explicit snapshot of the tables a query may use.

- Only `CREATE TABLE` statements are accepted. Any other statement in the file
  is rejected.
- A file may contain several `CREATE TABLE` statements, and table order is
  preserved.
- SQL identifier delimiters are removed for the parsed model, so the table
  `"user data"` is modeled as `user data` and the column `"user id"` is modeled
  as `user id`.

## Supported Column Types

Type spellings are matched case-insensitively, after parenthesized type
arguments are removed and remaining whitespace is collapsed to single spaces.
A declared length, precision, or scale is therefore accepted and ignored:
`VARCHAR(255)`, `DECIMAL(10, 2)`, and `timestamp(3) with time zone` are all
accepted and map exactly like their unparameterized spellings.

| Accepted SQL spellings | Java type | Notes |
| --- | --- | --- |
| `INTEGER`, `INT`, `SERIAL` | `Integer` | A `SERIAL` column is always modeled non-null. |
| `BIGINT`, `BIGSERIAL` | `Long` | A `BIGSERIAL` column is always modeled non-null. |
| `SMALLINT` | `Short` | |
| `BOOLEAN`, `BOOL` | `Boolean` | |
| `VARCHAR` | `String` | |
| `TEXT` | `String` | |
| `DATE` | `java.time.LocalDate` | |
| `TIMESTAMP` | `java.time.LocalDateTime` | |
| `TIMESTAMP WITH TIME ZONE` | `java.time.OffsetDateTime` | PostgreSQL normalizes the stored value to the session time zone, so a value read back equals the written value by instant rather than by offset. |
| `DECIMAL`, `NUMERIC` | `java.math.BigDecimal` | |
| `UUID` | `java.util.UUID` | |

Any spelling that is not listed above is rejected.

The generated class imports `java.time.LocalDate`, `java.time.LocalDateTime`,
`java.time.OffsetDateTime`, `java.math.BigDecimal`, and `java.util.UUID` as
needed; the remaining types need no import. Each result column is read with
`resultSet.getObject(position, JavaType.class)` at its one-based position in the
selected-column list.

## Supported `CREATE TABLE` Constructs

| Construct | Handling |
| --- | --- |
| `NOT NULL` | Accepted and modeled. It is the only source of column nullability. |
| Column-level `PRIMARY KEY` | Accepted and recorded as a primary-key constraint on that column. |
| Table-level `PRIMARY KEY (...)`, named or unnamed | Accepted and recorded as a primary-key constraint. |
| Column-level `UNIQUE` | Accepted and recorded as a unique constraint on that column. |
| Table-level `UNIQUE (...)`, named or unnamed | Accepted and recorded as a unique constraint. |
| `DEFAULT` with a literal or a function, such as `DEFAULT 1`, `DEFAULT 'new'`, `DEFAULT now()` | Accepted and ignored. |
| Column-level `REFERENCES t (c)`, including referential actions such as `ON DELETE CASCADE` | Accepted and ignored. |
| Column-level `CHECK (...)` | Accepted and ignored. |
| Table-level `FOREIGN KEY (...) REFERENCES ...`, named or unnamed | Accepted and ignored. |
| Table-level `CHECK (...)`, named or unnamed | Accepted and ignored. |
| Any statement other than `CREATE TABLE`, such as `ALTER TABLE` | Rejected. |
| Any other table-constraint kind | Rejected. |
| Unparsable SQL | Rejected. |

Constraints never affect generated Java. A recorded primary-key or unique
constraint is kept in the parsed schema model, but no code in the compilation
pipeline reads it, so it changes no generated type, method, parameter, or
result component. "Ignored" means the construct is accepted as valid schema
input and is not carried into the model at all.

## Nulls

Every generated method parameter and every generated result component uses a
reference type, so each of them can represent SQL `NULL`. `Optional` and custom
nullable wrappers are not used.

Null handling is uniform and independent of the column type:

- the generated argument list is built with `java.util.Arrays.asList`, which
  accepts null elements,
- `JdbcQueryExecutor` binds each argument positionally with
  `PreparedStatement.setObject`, so a null argument is bound as SQL `NULL`,
- each result column is read with `ResultSet.getObject(position, Class)`, so a
  SQL `NULL` is read back as `null`.

Nullability itself is parsed from `NOT NULL` only:

- a column without `NOT NULL` is modeled nullable, so a column declared bare
  `PRIMARY KEY` is modeled nullable,
- a `SERIAL` or `BIGSERIAL` column is always modeled non-null, because
  PostgreSQL defines those spellings as an integer type with a sequence default
  and `NOT NULL`,
- nullability never changes a generated Java type. It is carried into the
  analyzed model, but the generated type is resolved from the column type alone.

Null binding and null reading are executed against PostgreSQL for the nullable
columns of the integration schema snapshot, which cover `SMALLINT`, `VARCHAR`,
`TEXT`, `BOOLEAN`, `DATE`, `TIMESTAMP`, `DECIMAL`, `UUID`, and
`TIMESTAMP WITH TIME ZONE`.

## Unsupported Types and DDL

These spellings are rejected even though PostgreSQL accepts them:

- `TIMESTAMPTZ`
- `SERIAL4`
- `SERIAL8`
- `SMALLSERIAL`
- `TIMESTAMP WITHOUT TIME ZONE`

The following type families are not supported at all, because only the
spellings listed in [Supported Column Types](#supported-column-types) are
accepted:

- floating point, such as `REAL` and `DOUBLE PRECISION`,
- binary, such as `BYTEA`,
- `JSON` and `JSONB`,
- arrays,
- enum types,
- domain types,
- range types,
- composite types,
- spatial types.

### Failure Behavior

An unsupported type or an unsupported statement stops compilation. `sqlcj
generate` prints a single message on standard error and exits with a non-zero
status. Because every configured source is analyzed and generated before the
run writes its first file, such a failure writes no generated file, and output
written by an earlier successful run is left unchanged. The writing step itself
is sequential rather than atomic; see
[Generated Output and Failures](configuration.md#generated-output-and-failures).

The message names the schema source and the offending type or statement:

```text
sqlcj: Invalid schema source /home/dev/project/schema.sql: Unsupported SQL column type: TIMESTAMPTZ
sqlcj: Invalid schema source /home/dev/project/schema.sql: Unsupported schema statement: Alter
sqlcj: Invalid schema source /home/dev/project/schema.sql: Failed to parse schema
```

## Verified by

Type table:

- `DefaultSchemaParserTest.shouldKeepParsingDeliveredColumnTypes` and
  `DefaultSchemaParserTest.shouldParseAddedColumnTypes` cover the accepted
  spellings, their case-insensitive forms, and the ignored type arguments.
- `PostgresIntegrationTest.shouldExecuteGeneratedOneQueryAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedWriteAgainstPostgres`, and
  `PostgresIntegrationTest.shouldRoundTripSerialUuidAndTimestampWithTimeZoneValues`
  execute the Java mappings against PostgreSQL 16.
- `DefaultSchemaParserTest.shouldRejectUnsupportedColumnType` covers the
  rejected spellings.

`CREATE TABLE` table:

- `DefaultSchemaParserTest.shouldParseTableConstraints`,
  `DefaultSchemaParserTest.shouldParseTableWithoutConstraints`,
  `DefaultSchemaParserTest.shouldCanonicalizeQuotedTableAndColumnNames`,
  `DefaultSchemaParserTest.shouldParseColumnSpecificationsThatDoNotAffectTypes`,
  `DefaultSchemaParserTest.shouldIgnoreForeignKeyAndCheckTableConstraints`, and
  `DefaultSchemaParserTest.shouldParseNamedTableConstraintsAlongsideIgnoredOnes`
  cover the modeled and ignored constructs.
- `DefaultSchemaParserTest.shouldRejectUnsupportedSchemaStatement` and
  `DefaultSchemaParserTest.shouldThrowSchemaParseExceptionForInvalidSql` cover
  the rejected input.
- `PostgresIntegrationTest.shouldExecuteGeneratedQueryForSnapshotWithIgnoredTableConstraints`
  proves that such a snapshot is valid PostgreSQL DDL and compiles and executes.

Nulls:

- `PostgresIntegrationTest.shouldBindAndReadNullValuesThroughGeneratedCode`
  binds null arguments and reads null results through generated code against
  PostgreSQL.
- `DefaultSchemaParserTest.shouldParseColumns`,
  `DefaultSchemaParserTest.shouldParseNullabilityOfAddedColumnTypes`, and
  `DefaultSchemaParserTest.shouldParseSerialColumnAsNotNullable` cover parsed
  nullability.

Connection ownership and transactions:

- `JdbcQueryExecutorTest` covers both construction paths for `query`,
  `queryMany`, and `execute`, including
  `shouldCloseAcquiredConnectionForEachDataSourceOperation`,
  `shouldCloseAcquiredConnectionWhenDataSourceOperationFails`,
  `shouldLeaveCallerOwnedConnectionOpenAndItsTransactionStateUnchanged`,
  `shouldCloseStatementsAndResultSetsOfCallerOwnedConnection`,
  `shouldCloseStatementWhenExecutionFailsOnCallerOwnedConnection`, and
  `shouldWrapSqlExceptionForCallerOwnedConnection`.
- `PostgresIntegrationTest.shouldCommitGeneratedOperationsOnCallerOwnedConnection`
  and
  `PostgresIntegrationTest.shouldRollBackGeneratedOperationsOnCallerOwnedConnection`
  run a generated affected-row write, a generated returning write, and a
  generated read on one caller-owned connection with auto-commit disabled, and
  prove the application's own `commit` and `rollback`.
