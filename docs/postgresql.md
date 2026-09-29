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
parameter binding, row mapping, cardinality enforcement, affected-row counting,
and exception translation, and both accept the same generated repositories
without regeneration:

| Construction | Connection ownership |
| --- | --- |
| `new JdbcQueryExecutor(javax.sql.DataSource)` | The executor obtains one connection per operation and closes it before the operation returns, so each operation runs on that connection's own transaction state, typically one auto-committed statement. |
| `new JdbcQueryExecutor(java.sql.Connection)` | The executor runs every operation on the supplied connection and never closes, commits, or rolls it back, never changes its auto-commit setting, and never otherwise configures it. |

The caller-owned connection path is how several generated operations take part
in one application-controlled transaction: the application disables auto-commit,
constructs another repository instance over an executor bound to that
connection, runs generated reads and writes through it, and then calls `commit`
or `rollback` itself. sqlcj provides no transaction callback or template API, no
savepoints, and no isolation configuration. The [Quickstart](quickstart.md)
runs that pattern end to end.

In both paths the `PreparedStatement` and any `ResultSet` opened for an
operation are closed before that operation returns, on success, on a cardinality
failure, and on any other failure.

Every generated method passes the generated repository's class name and its
query name to the executor, so every runtime failure names the query the
application called.

Every `SQLException` raised while acquiring a connection, preparing a statement,
binding parameters, executing, reading results, or closing a DataSource-acquired
connection is translated into `dev.sqlcj.runtime.QueryExecutionException` with
the message `Failed to execute query '<query>' in <repository>` and the
`SQLException` as its cause:

```text
Failed to execute query 'GetAuthor' in AuthorRepository
```

A row count an annotation does not allow raises
`dev.sqlcj.runtime.QueryCardinalityException`, a subclass of
`QueryExecutionException` that carries only a message:

```text
Query 'GetAuthor' in AuthorRepository returned no row; expected exactly one
Query 'GetAuthor' in AuthorRepository returned more than one row; expected exactly one
Query 'FindAuthor' in AuthorRepository returned more than one row; expected at most one
```

A cardinality check runs after the statement has executed, so a returning write
that fails it has already changed the database.

A failed operation on a caller-owned connection leaves the connection open, so
the application decides whether to continue or roll back.

The runtime is blocking and synchronous. An executor built on a caller-owned
connection inherits that connection's confinement to a single thread at a time.

Behavior is verified against PostgreSQL 16. The pipeline is executed end to end
against a `postgres:16-alpine` container: the schema snapshot is run as
PostgreSQL DDL, the generated Java is compiled, and the generated repository is
executed through the JDBC runtime. Those tests are skipped when Docker is
unavailable.

H2 is used only inside sqlcj's own tests as a convenience database and is not a
supported target. The PostgreSQL driver, the container library, and H2 are all
test-scoped dependencies of the build.

## Schema Snapshot Input

`sql[].schema` is an explicit snapshot of the tables a query may use. It is one
file, a list of files, or a directory of `.sql` migration files; see
[`sql[].schema`](configuration.md#sqlschema) for the accepted forms and the
order the files are read in.

- Only `CREATE TABLE`, `DROP TABLE`, and the `ALTER TABLE` forms listed in
  [Ordered Table DDL](#ordered-table-ddl) update the schema model. The
  statements listed in [Ignored Statements](#ignored-statements) are accepted
  and leave it unchanged. Any other statement in a schema file is rejected.
- The statements of the schema files of one entry are applied in order to one
  schema model, so each of them sees the tables and columns the statements and
  files before it left.
- A file may contain several statements, and a table keeps the position of the
  statement that created it.
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
| `INTEGER`, `INT`, `INT4`, `SERIAL`, `SERIAL4` | `Integer` | A serial column is always modeled non-null. |
| `BIGINT`, `INT8`, `BIGSERIAL`, `SERIAL8` | `Long` | A serial column is always modeled non-null. |
| `SMALLINT`, `INT2`, `SMALLSERIAL`, `SERIAL2` | `Short` | A serial column is always modeled non-null. |
| `BOOLEAN`, `BOOL` | `Boolean` | |
| `VARCHAR`, `CHARACTER VARYING` | `String` | |
| `CHAR`, `CHARACTER` | `String` | PostgreSQL blank-pads a stored value to the declared length, so `ab` written into a `CHAR(3)` column reads back as `ab `. |
| `TEXT` | `String` | |
| `DATE` | `java.time.LocalDate` | |
| `TIME`, `TIME WITHOUT TIME ZONE` | `java.time.LocalTime` | |
| `TIMESTAMP`, `TIMESTAMP WITHOUT TIME ZONE` | `java.time.LocalDateTime` | |
| `TIMESTAMP WITH TIME ZONE`, `TIMESTAMPTZ` | `java.time.OffsetDateTime` | PostgreSQL normalizes the stored value to the session time zone, so a value read back equals the written value by instant rather than by offset. |
| `DECIMAL`, `NUMERIC` | `java.math.BigDecimal` | |
| `REAL`, `FLOAT4` | `Float` | |
| `DOUBLE PRECISION`, `FLOAT8` | `Double` | |
| `UUID` | `java.util.UUID` | |
| `BYTEA` | `byte[]` | A record compares an array component by reference, so two row records holding equal bytes are not `equals`. |
| `JSON` | `String` | The JSON text itself. PostgreSQL stores it as written, so it reads back exactly as written. sqlcj never parses, validates, or normalizes it. |
| `JSONB` | `String` | The JSON text itself. PostgreSQL stores a decomposed value, so the text reads back as PostgreSQL renders it rather than as written, and `=` compares by value. sqlcj never parses, validates, or normalizes it. |
| The name of an enum type the schema declares | The generated Java enum of that type | Matched without SQL identifier delimiters and case-insensitively, as PostgreSQL resolves an unquoted type name. See [Enum Types](#enum-types). |
| A one-dimensional array of any spelling above except `BYTEA`, `JSON`, and `JSONB`, written `type[]` or `type[n]` | `java.util.List<T>` of the element's Java type | The declared size is ignored, as PostgreSQL ignores it. See [Array Types](#array-types). |

Any spelling that is not listed above has no Java mapping. Such a column is
recorded with its declared type instead of
failing the schema, and fails only a query that uses it; see
[Unsupported Types and DDL](#unsupported-types-and-ddl).

The generated repository imports `java.time.LocalDate`, `java.time.LocalTime`,
`java.time.LocalDateTime`, `java.time.OffsetDateTime`, `java.math.BigDecimal`,
and `java.util.UUID` as needed; the remaining types need no import, and
`java.util.List` is already imported by every repository. A row record imports
`java.util.List` when one of its components is an array. A generated
enum belongs to the generated package, so it is used by its simple name and
needs no import either. Each result column is read at its one-based position in
the selected-column list, with `resultSet.getObject(position, JavaType.class)`,
or with `resultSet.getString(position)` for a `JSON` or `JSONB` column, which
the driver reports as a type of its own rather than as a character type. An
enum column reads its label the same way and resolves it with
`<EnumType>.fromLabel(...)`, and an array column is read with
`dev.sqlcj.runtime.SqlArray.getList(...)`.

A `JSON` or `JSONB` argument is passed to the executor as
`new dev.sqlcj.runtime.UntypedText(value)`, written out in full so that the
generated imports are unchanged, and `JdbcQueryExecutor` binds that text with
`java.sql.Types.OTHER`. PostgreSQL then types the text from the context of its
placeholder, which is what a `json` or `jsonb` column or comparison needs: text
bound as `varchar` is rejected there. An enum argument is passed the same way,
around the label of its constant, because a label bound as `varchar` is
rejected where an enum is expected. An array argument is wrapped in
`dev.sqlcj.runtime.SqlArray` the same way; see [Array Types](#array-types).

## Enum Types

`CREATE TYPE <name> AS ENUM (...)` adds an enum type to the schema, and a
column whose declared type names it is modeled as a column of that type. Every
query that reads or binds such a column uses the one Java enum the
package generates for the type:

```sql
CREATE TYPE stage_setting AS ENUM ('indoor', 'outdoor');

ALTER TYPE stage_setting ADD VALUE 'covered' BEFORE 'outdoor';
```

```java
// Code generated by sqlcj. DO NOT EDIT.

package com.example.app.db;

/**
 * Generated by sqlcj.
 *
 * Enum: stage_setting
 */

public enum StageSetting {

    INDOOR("indoor"),
    COVERED("covered"),
    OUTDOOR("outdoor");

    private final String label;

    StageSetting(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static StageSetting fromLabel(String label) {
        if (label == null) {
            return null;
        }

        for (StageSetting value : values()) {
            if (value.label.equals(label)) {
                return value;
            }
        }

        throw new IllegalArgumentException("Unknown label for enum type stage_setting: " + label);
    }
}
```

- The constants keep their exact PostgreSQL labels and follow them in
  PostgreSQL's own sort order, which is the declared order with each added
  label in the position its `ALTER TYPE ... ADD VALUE` places it.
- A constant is named by the naming rules in
  [Generated Java Names](configuration.md#generated-java-names).
- `fromLabel` reads back a label: `null` for a SQL `NULL`, and a failure for a
  label the generated enum does not hold, which means the database declares one
  the schema source does not.
- Only an enum type a query actually uses generates a file, including an enum
  type only an array element names.
- A one-dimensional array of an enum type, such as `stage_setting[]`, is a
  `List` of that generated enum; see [Array Types](#array-types).

These statements update the enum types the statements and files before them
left:

| Statement | Handling |
| --- | --- |
| `CREATE TYPE ... AS ENUM (...)` | Adds the type with its declared labels. |
| `ALTER TYPE ... ADD VALUE '<label>'` | Appends the label after the last one. |
| `ALTER TYPE ... ADD VALUE '<label>' BEFORE '<neighbour>'` | Inserts the label directly before the neighbour. |
| `ALTER TYPE ... ADD VALUE '<label>' AFTER '<neighbour>'` | Inserts the label directly after the neighbour. |
| `ALTER TYPE ... ADD VALUE IF NOT EXISTS '<label>'` | Does nothing when the type already has the label. As PostgreSQL does, the existing label decides before the neighbour, so a neighbour the type does not have is not resolved at all. |
| Any other `ALTER TYPE` action, such as `RENAME TO`, `RENAME VALUE`, `OWNER TO`, `SET SCHEMA`, or an attribute change | Rejected. |
| `CREATE TYPE` of a composite, range, or shell type | Rejected. |
| `DROP TYPE` | Rejected. sqlcj's parser does not read the statement, so it is reported as a syntax error. |

Type names are matched case-insensitively, after their SQL identifier
delimiters are removed, and labels are matched exactly. A statement that repeats
a type or a label, or that refers to a type or a label that is not modeled, is
rejected as PostgreSQL rejects it:

```text
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__stages.sql: Type already exists in schema: stage_setting
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__stages.sql: Type not found in schema: stage_setting
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__stages.sql: Label already exists in type stage_setting: indoor
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__stages.sql: Label not found in type stage_setting: covered
```

A placeholder index repeated across two enum types, or across an enum and any
other type, has no single Java type and is rejected by query analysis, which
names each enum by its PostgreSQL type:

```text
sqlcj: Invalid query 'UpdateStage' in /home/dev/project/sql/queries.sql at line 5: Placeholder $1 has conflicting types: stage_setting from 'setting' and String from 'handle'
```

Two entries of one package that use one enum type must define its labels alike,
and a generated enum must not collide with another generated type of the
package; both are documented in
[Generated Java Names](configuration.md#generated-java-names).

## Array Types

A column declared as a one-dimensional array, written `type[]` or `type[n]`, is
modeled as an array of its element type and generates `java.util.List<T>` of
that element's Java type, for method parameters and result components alike:

```sql
CREATE TABLE stages (
    id       BIGINT PRIMARY KEY,
    tags     varchar(20)[],
    past     stage_setting[]
);
```

generates `List<String> tags` and `List<StageSetting> past`.

- The element type is any spelling in
  [Supported Column Types](#supported-column-types) except `BYTEA`, `JSON`, and
  `JSONB`, or the name of an enum type the schema declares.
- The declared size of a dimension is ignored, as PostgreSQL ignores it, so
  `numeric(10, 2)[3]` is the same type as `numeric(10, 2)[]`.
- An array column is typed the same way through `CREATE TABLE`,
  `ALTER TABLE ... ADD COLUMN`, and `ALTER TABLE ... ALTER COLUMN ... TYPE`, and
  a rename or a nullability change keeps it.
- An array is a type of its own: a placeholder index used once as an array and
  once as a value of its element type is rejected, like any other conflicting
  index.
- A `LIKE`/`ILIKE` pattern placeholder still requires a scalar `VARCHAR` or
  `TEXT` column, so an array column is rejected there.
- No array operator or function, such as `= ANY` or `&&`, is analyzed.

An array argument is passed to the executor as
`new dev.sqlcj.runtime.SqlArray("<element type>", <parameter>)`, written out in
full so that the generated imports are unchanged, where `<element type>` is
PostgreSQL's own name of the element type: `int4`, `int8`, `int2`, `bool`,
`varchar`, `bpchar`, `text`, `date`, `time`, `timestamp`, `timestamptz`,
`numeric`, `float4`, `float8`, `uuid`, or the declared name of an enum type. A
`CHAR` or `CHARACTER` element is named `bpchar`, PostgreSQL's own name of the
blank-padded character type, because PostgreSQL compares a `bpchar` array only
with another one; `VARCHAR` and `CHARACTER VARYING` elements are named
`varchar`. Both read their elements back as `String`, blank padded to the
declared length for `CHAR`, as a scalar of that type is read. An array of an
enum carries the labels of its constants instead, as
`dev.sqlcj.runtime.SqlArray.of("<enum type>", <parameter>, <EnumType>::label)`.

`JdbcQueryExecutor` builds the value with `Connection.createArrayOf` on the
connection the operation runs on and binds it with
`PreparedStatement.setArray`. An array column is read with
`dev.sqlcj.runtime.SqlArray.getList(resultSet, position, <Type>.class)`, which
reads each element as a scalar of that type is read; an array of an enum is read
as `dev.sqlcj.runtime.SqlArray.getList(resultSet, position, String.class,
<EnumType>::fromLabel)`. Both go through the JDBC `java.sql.Array` API alone, so
neither the runtime nor the generated code depends on a driver.

Nulls are carried in both directions:

- a `null` list argument is bound with
  `PreparedStatement.setNull(position, java.sql.Types.ARRAY)`, so the column
  receives a SQL `NULL` rather than an empty array,
- a `null` element is bound as a `NULL` element of the array,
- a SQL `NULL` column reads back as a `null` list, an empty array as an empty
  list, and a `NULL` element as a `null` element.

A declared multidimensional array, such as `integer[][]`, and an array of
`BYTEA`, `JSON`, `JSONB`, or an unmapped element type have no Java mapping and
are recorded with their declared type; see
[Unsupported Types and DDL](#unsupported-types-and-ddl).

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
| Any statement outside [Ordered Table DDL](#ordered-table-ddl) and [Ignored Statements](#ignored-statements), such as `CREATE VIEW` | Rejected. |
| Any other table-constraint kind | Rejected. |
| Unparsable SQL | Rejected. |

Constraints never affect generated Java. A recorded primary-key or unique
constraint is kept in the parsed schema model, but no code in the compilation
pipeline reads it, so it changes no generated type, method, parameter, or
result component. "Ignored" means the construct is accepted as valid schema
input and is not carried into the model at all.

## Ordered Table DDL

A schema of `CREATE TABLE` statements alone is modeled exactly as it was before
ordered DDL existed. Beyond it, these statements update the schema the
statements and files before them left; the enum-type statements are listed in
[Enum Types](#enum-types):

| Statement | Handling |
| --- | --- |
| `CREATE TABLE` | Adds the table after the tables already modeled. |
| `CREATE TABLE IF NOT EXISTS` | Does nothing when the table already exists. |
| `DROP TABLE`, with one or several names | Removes each named table. |
| `DROP TABLE IF EXISTS` | Does nothing for a name that is not modeled. |
| `ALTER TABLE ... ADD COLUMN` | Appends the column, typed exactly as a `CREATE TABLE` column of the same declaration, and records its column-level `PRIMARY KEY` or `UNIQUE`. |
| `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` | Does nothing when the column already exists. |
| `ALTER TABLE ... DROP COLUMN` | Removes the column and every recorded constraint that lists it. |
| `ALTER TABLE ... DROP COLUMN IF EXISTS` | Does nothing when the column is not modeled. |
| `ALTER TABLE ... RENAME COLUMN` | Renames the column in its position and in the constraints that list it. |
| `ALTER TABLE ... RENAME TO` | Renames the table in its position, keeping its columns and constraints. |
| `ALTER TABLE ... ALTER COLUMN ... TYPE` | Maps or records the new type as a `CREATE TABLE` column of that type is mapped or recorded, keeping the column's position and its nullability, which a type change does not state. |
| `ALTER TABLE ... ALTER COLUMN ... SET NOT NULL` | Models the column non-null. |
| `ALTER TABLE ... ALTER COLUMN ... DROP NOT NULL` | Models the column nullable. |
| `ALTER TABLE IF EXISTS ...` | Does nothing when the table is not modeled. It covers only the table, so a missing column of a modeled table still fails. |
| Any other `ALTER TABLE` action outside [Ignored Statements](#ignored-statements), such as `SET DEFAULT` | Rejected. |
| `DROP` of an object that is neither a table nor listed in [Ignored Statements](#ignored-statements), such as `DROP VIEW` | Rejected. |

One `ALTER TABLE` may state several actions; they are applied in the written
order. Table and column names are matched case-insensitively, as query analysis
looks them up, after their SQL identifier delimiters are removed.

Outside the `IF [NOT] EXISTS` forms above, a statement that refers to a table or
a column that is not modeled is rejected, and so is a statement that would give
two tables, or two columns of one table, the same name, as PostgreSQL rejects it:

```text
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Table not found in schema: payments
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Column not found in table orders: total
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Table already exists in schema: orders
sqlcj: Invalid schema source /home/dev/project/sql/migrations/V2__orders.sql: Column already exists in table orders: total
```

## Ignored Statements

A schema file records the whole history of a database, not only its tables, so
these statements are accepted and leave the schema model exactly as the
statements and files before them left it:

| Statement | Notes |
| --- | --- |
| `CREATE INDEX`, `CREATE UNIQUE INDEX` | |
| `ALTER INDEX` | |
| `DROP INDEX`, `DROP INDEX IF EXISTS` | |
| `COMMENT ON TABLE`, `COMMENT ON COLUMN`, `COMMENT ON VIEW` | These are the only `COMMENT ON` targets sqlcj's parser reads; see below. |
| `CREATE EXTENSION`, `CREATE EXTENSION IF NOT EXISTS` | |
| `CREATE SEQUENCE`, `ALTER SEQUENCE`, `DROP SEQUENCE` | |
| `GRANT`, `REVOKE` | |
| `CREATE FUNCTION`, `CREATE OR REPLACE FUNCTION` | Only with a body delimited by the untagged `$$ ... $$`, such as `AS $$ ... $$`; see below. |
| `DROP FUNCTION`, `DROP FUNCTION IF EXISTS` | |
| `CREATE TRIGGER`, `DROP TRIGGER` | |
| `INSERT`, `UPDATE`, `DELETE` | A migration's data statements do not change the modeled tables. |
| `ALTER TABLE ... ADD [CONSTRAINT name] PRIMARY KEY (...)` | Named or unnamed. |
| `ALTER TABLE ... ADD [CONSTRAINT name] UNIQUE (...)` | Named or unnamed. |
| `ALTER TABLE ... ADD [CONSTRAINT name] FOREIGN KEY (...) REFERENCES ...` | Named or unnamed. |
| `ALTER TABLE ... ADD CONSTRAINT name CHECK (...)` | The unnamed `ADD CHECK (...)` spelling is a syntax error for sqlcj's parser; see below. |
| `ALTER TABLE ... DROP CONSTRAINT [IF EXISTS] name` | |
| `ALTER TABLE ... RENAME CONSTRAINT` | |

A constraint an `ALTER TABLE` adds is not recorded, so it is not carried into
the schema model the way a `CREATE TABLE` constraint is.

An ignored statement is not resolved against the schema at all, so it may name
a table or a column the snapshot does not model. An ignored `ALTER TABLE`
action is the one exception: the statement still resolves its table, so
`ALTER TABLE payments ADD CONSTRAINT payments_pkey PRIMARY KEY (id)` fails when
`payments` is not modeled, exactly as any other `ALTER TABLE` of a missing
table does. An ignored action may stand alone or beside modeled actions of one
`ALTER TABLE`, so
`ALTER TABLE users ADD COLUMN age INTEGER, ADD CONSTRAINT users_age_check CHECK (age > 0)`
appends `age` and records nothing for the constraint.

Three limitations follow from what sqlcj's SQL parser reads, and sqlcj does not
split or pre-process the SQL to work around them:

- A `CREATE FUNCTION` is ignored only when its body is delimited by the untagged
  `$$ ... $$`, which is the only dollar-quote delimiter sqlcj's parser reads. A
  tagged delimiter such as `$body$ ... $body$` is not read at all: it is a
  syntax error, reported for the closing delimiter, as in
  `Encountered unexpected token: "$body$" at line 1, column 74`. A body written
  any other way, such as `AS 'SELECT 1' LANGUAGE sql`, is captured through the
  end of the file, so such a function is rejected at the line it begins on and
  nothing after it is applied.
- `COMMENT ON` is read only for the `TABLE`, `COLUMN`, and `VIEW` targets. Any
  other target, such as `COMMENT ON TYPE`, is a syntax error rather than an
  ignored statement.
- `ALTER TABLE ... ADD CHECK (...)` without a constraint name is a syntax
  error. The named `ADD CONSTRAINT name CHECK (...)` spelling is ignored as
  listed above.

## Nulls

Every generated method parameter and every generated result component uses a
reference type, so each of them can represent SQL `NULL`. A result component may
be `null` exactly when its column is modeled nullable by the schema snapshot or
is read from a left-joined source, whose columns are all nullable because an
unmatched row supplies no value for them.

Row absence is a different thing from a null component, and the two are never
mixed:

- absence is expressed only by the query's annotation: `:optional` returns
  `Optional.empty()`, and `:one` fails with
  `dev.sqlcj.runtime.QueryCardinalityException`,
- `Optional` is used only as the return type of an `:optional` query. No record
  component and no method parameter is wrapped in `Optional`, and no custom
  nullable wrapper is used.

Null handling does not depend on the column type:

- the generated argument list is built with `java.util.Arrays.asList`, which
  accepts null elements,
- `JdbcQueryExecutor` binds each argument positionally with
  `PreparedStatement.setObject`, so a null argument is bound as SQL `NULL`. A
  `JSON` or `JSONB` argument is bound the same way through its
  `dev.sqlcj.runtime.UntypedText` wrapper, so a null value becomes a SQL `NULL`
  without a declared type. A null enum argument is wrapped the same way, so it
  is bound as SQL `NULL` instead of as the label of a constant. A null array
  argument is bound as a SQL `NULL` array through its
  `dev.sqlcj.runtime.SqlArray` wrapper, and a null element stays null inside the
  bound array,
- each result column is read with `ResultSet.getObject(position, Class)`, or
  with `ResultSet.getString(position)` for `JSON`, `JSONB`, and an enum, so a
  SQL `NULL` is read back as `null`, which `fromLabel` keeps `null` for an
  enum. An array column is read with `dev.sqlcj.runtime.SqlArray.getList`, which
  reads a SQL `NULL` as a null list and a `NULL` element as a null element.

Nullability itself is parsed from `NOT NULL` only:

- a column without `NOT NULL` is modeled nullable, so a column declared bare
  `PRIMARY KEY` is modeled nullable,
- a serial column, declared `SMALLSERIAL`, `SERIAL`, `BIGSERIAL`, `SERIAL2`,
  `SERIAL4`, or `SERIAL8`, is always modeled non-null, because PostgreSQL
  defines those spellings as an integer type with a sequence default and
  `NOT NULL`,
- nullability never changes a generated Java type. It is carried into the
  analyzed model, but the generated type is resolved from the column type alone.

Null binding and null reading are executed against PostgreSQL for the nullable
columns of the integration schema snapshots, which cover `SMALLINT`, `VARCHAR`,
`TEXT`, `BOOLEAN`, `DATE`, `TIMESTAMP`, `DECIMAL`, `UUID`,
`TIMESTAMP WITH TIME ZONE`, `REAL`, `DOUBLE PRECISION`, `BYTEA`, `TIME`, `JSON`,
`JSONB`, an enum type, and an array of every mapped element type.

## Unsupported Types and DDL

The following type families have no Java mapping, because only the spellings
listed in [Supported Column Types](#supported-column-types) are mapped:

- multidimensional arrays, such as `integer[][]`, and arrays of `BYTEA`,
  `JSON`, `JSONB`, or an unmapped element type,
- domain types,
- range types,
- composite types,
- spatial types.

These spellings of otherwise mapped families are unmapped as well:

- `FLOAT` and `FLOAT(p)`, whose precision selects `REAL` or `DOUBLE PRECISION`,
- `TIME WITH TIME ZONE` and `TIMETZ`.

A column of such a type does not fail the schema. It is recorded with its
declared type, written as the canonical spelling of that type — upper case, with
parenthesized type arguments removed — followed by `[]` for each declared array
dimension, so `xml` is recorded as `XML`, `jsonb[]` as `JSONB[]`,
and `integer[][]` as `INTEGER[][]`.

A recorded column fails only the analysis of a query that

- reads it, as a `SELECT` item or a `RETURNING` item,
- binds it, meaning a placeholder takes its type in a comparison, an `IN` list,
  a range bound, a `LIKE`/`ILIKE` pattern, an `INSERT` column, or an `UPDATE`
  assignment, or
- expands it, through `SELECT *`, `SELECT qualifier.*`, or `RETURNING *`.

Every other query over the same table compiles, including one that references
the column without using its type, such as `WHERE tags IS NULL`.

### Failure Behavior

An unsupported statement, a schema sqlcj cannot parse, or a query that uses a
column of an unmapped type stops compilation. `sqlcj generate` prints a single
message on standard error
and exits with status `1`. Because every configured source is analyzed and
generated before the run writes its first file, such a failure writes no
generated file, and output
written by an earlier successful run is left unchanged. The writing step itself
is sequential rather than atomic; see
[Generated Output and Failures](configuration.md#generated-output-and-failures).

A schema message names the schema source and the offending statement with the
line it begins on, the table or column the statement refers to, or the syntax
error with the line and column it was found at. A query message names the
query, its source, its header line, and the offending column and recorded type:

```text
sqlcj: Invalid schema source /home/dev/project/schema.sql: Unsupported schema statement: CreateView at line 12
sqlcj: Invalid schema source /home/dev/project/schema.sql: Encountered unexpected token: ";" at line 4, column 1
sqlcj: Invalid query 'ListTags' in /home/dev/project/queries.sql at line 5: Column 'tags' has unsupported type JSONB[]
```

## Verified by

Type table:

- `DefaultSchemaParserTest.shouldKeepParsingDeliveredColumnTypes`,
  `DefaultSchemaParserTest.shouldParseAddedColumnTypes`, and
  `DefaultSchemaParserTest.shouldParsePostgresTypeSpellings` cover the accepted
  spellings, their case-insensitive forms, and the ignored type arguments.
- `PostgresIntegrationTest.shouldExecuteGeneratedOneQueryAgainstPostgres`,
  `PostgresIntegrationTest.shouldExecuteGeneratedWriteAgainstPostgres`,
  `PostgresIntegrationTest.shouldRoundTripSerialUuidAndTimestampWithTimeZoneValues`,
  `PostgresIntegrationTest.shouldRoundTripPostgresTypeSpellingValues`,
  `PostgresIntegrationTest.shouldRoundTripFloatingPointBinaryAndTimeValues`, and
  `PostgresIntegrationTest.shouldRoundTripJsonValues`
  execute the Java mappings, including the blank-padded `CHAR` values, the JSON
  text as written and as PostgreSQL renders it, and a `JSONB` equality
  predicate, against PostgreSQL 16.
- `JavaCodeGeneratorTest.shouldWrapJsonArgumentsAndReadJsonColumnsAsText` and
  `JavaCodeGeneratorTest.shouldGenerateCompilableJavaSourceForJsonTypes` cover
  the generated `UntypedText` argument at every binding position of a JSON
  placeholder, the `getString` read, the unchanged binding and reading of the
  other `String` types, and compilation of the generated source.
- `JdbcQueryExecutorTest.shouldBindUntypedTextWithoutADeclaredSqlType` and
  `JdbcQueryExecutorTest.shouldBindNullUntypedTextWithoutADeclaredSqlType`
  cover the runtime binding of a null and a non-null `UntypedText` beside an
  ordinary argument.
- `DefaultSchemaParserTest.shouldModelColumnsOfADeclaredEnumType` covers the
  enum column of a `CREATE TABLE`, an added column, and a changed column type,
  and `JavaCodeGeneratorTest.shouldBindEnumLabelsAndReadEnumColumnsByLabel`
  covers the generated enum parameter, its `UntypedText` argument at every
  binding position, and the `fromLabel` read.
- `DefaultSchemaParserTest.shouldRecordUnsupportedColumnType` and
  `DefaultSchemaParserTest.shouldParseNullabilityOfUnsupportedColumnTypes` cover
  the recorded type of an unmapped spelling and of an array, beside the mapped
  columns of the same table.
- `QueryAnalyzerTest.shouldRejectQueryThatUsesAnUnsupportedTypeColumn` covers
  each reading, binding, and expanding query, and
  `QueryAnalyzerTest.shouldAnalyzeQueryBesideAnUnsupportedTypeColumn` and
  `QueryAnalyzerTest.shouldAnalyzeIsNullOnAnUnsupportedTypeColumn` cover the
  queries over the same table that still compile.
- `SqlcjCompilerIntegrationTest.shouldReportTheQueryThatReadsAnUnsupportedTypeColumn`
  covers the query diagnostic and that no file is written, and
  `SqlcjCompilerIntegrationTest.shouldGenerateCompilableRepositoryBesideUnsupportedTypeColumns`
  compiles a repository generated beside a `JSONB[]` and an `XML` column.

Array types:

- `DefaultSchemaParserTest.shouldModelOneDimensionalArrayColumns` covers every
  element spelling through `CREATE TABLE`, `ADD COLUMN`, and
  `ALTER COLUMN ... TYPE`, including the ignored declared size, and
  `DefaultSchemaParserTest.shouldCarryTheArrayShapeOfARenamedAndRetypedColumn`
  covers the rename, the nullability change, and the two retypes;
  `DefaultSchemaParserTest.shouldModelTheBlankPaddedSpellingOfCharacterArrayColumns`
  covers the `CHAR` and `CHARACTER` spellings that carry `bpchar` and the
  varying spellings that do not, through the same statements.
- `DefaultSchemaParserTest.shouldRecordUnsupportedColumnType` covers the
  multidimensional arrays and the `BYTEA`, `JSON`, `JSONB`, and `XML` arrays
  that stay recorded.
- `QueryAnalyzerTest.shouldCarryTheArrayShapeOfSelectedColumnsAndTheirParameters`,
  `QueryAnalyzerTest.shouldExpandTheArrayColumnsOfAWildcard`, and
  `QueryAnalyzerTest.shouldCarryTheArrayShapeOfAReturningWrite` cover the
  analyzed positions, and
  `QueryAnalyzerTest.shouldRejectARepeatedIndexAcrossAnArrayAndItsElement` and
  `QueryAnalyzerTest.shouldRejectLikeOnAnArrayColumn` cover the rejections.
  `QueryAnalyzerTest.shouldCarryTheBlankPaddedSpellingOfAnArrayParameter` covers
  the spelling a character array parameter carries.
- `JavaCodeGeneratorTest.shouldBindAndReadArrayColumnsPerElementType` covers the
  generated `List` type, the `SqlArray` argument, and the `getList` read of
  every element type;
  `JavaCodeGeneratorTest.shouldBindEnumArrayLabelsAndReadEnumArrayColumnsByLabel`
  covers the enum array at every binding position of its index and the enum file
  an array element alone generates; and
  `JavaCodeGeneratorTest.shouldImportListForAnArrayRowComponent` covers the row
  record's imports. The enum array test and the row-component test compile the
  generated source. `JavaCodeGeneratorTest.shouldBindBlankPaddedCharacterArraysAsBpchar`
  covers the `bpchar` argument of a `CHAR` array and the `varchar` argument of a
  `VARCHAR` array.
- `JdbcQueryExecutorTest.shouldBindSqlArrayAsAServerArray` and
  `JdbcQueryExecutorTest.shouldBindNullSqlArrayElementsAsANullArray` cover the
  recorded `createArrayOf` name and elements, the `setArray` position, and the
  `setNull` of a null list, and `SqlArrayTest` covers the converted elements and
  the null, empty, and element-null lists a read returns.
- `PostgresIntegrationTest.shouldRoundTripArrayValues` writes and reads a
  non-empty list holding a `null` element, an empty list, and a `null` list for
  every mapped element type and an enum type against PostgreSQL 16, comparing
  the `TIMESTAMPTZ` elements by instant and the `CHAR(3)` elements with the
  blank-padded values PostgreSQL stores, and finds the row again through a
  placeholder equality on the `CHAR(3)` array column.

Enum types:

- `DefaultSchemaParserTest.shouldAddEnumTypeWithItsDeclaredLabels`,
  `DefaultSchemaParserTest.shouldAddEnumLabelInItsStatedPosition`,
  `DefaultSchemaParserTest.shouldIgnoreAddValueIfNotExistsForAnExistingLabel`,
  and `DefaultSchemaParserTest.shouldApplyAnAddedLabelToAnEarlierEnumColumn`
  cover the applied forms and the label order they leave, and
  `DefaultSchemaParserTest.shouldReportTheEnumStatementItCannotApply` covers
  each diagnostic.
- `DefaultSchemaParserTest.shouldKeepTheEnumTypeOfARenamedAndRetypedColumn`
  covers the enum type a rename and a nullability change carry along, and
  `DefaultSchemaParserTest.shouldModelEnumArraysAndRecordUndeclaredTypesAsUnsupported`
  covers the modeled enum array beside the multidimensional enum array and the
  undeclared type that stay recorded.
- `DefaultSchemaParserTest.shouldRejectStatementOutsideTheIgnoredList` covers
  the rejected `ALTER TYPE` actions and the composite `CREATE TYPE`, and
  `DefaultSchemaParserTest.shouldRejectDropType` covers `DROP TYPE`.
- `QueryAnalyzerTest.shouldCarryTheEnumTypeOfASelectedColumnAndItsParameter`,
  `QueryAnalyzerTest.shouldCarryTheEnumTypeOfAReturningColumnAndItsParameter`,
  `QueryAnalyzerTest.shouldAcceptARepeatedIndexOfOneEnumType`, and
  `QueryAnalyzerTest.shouldRejectARepeatedIndexAcrossConflictingEnumTypes`
  cover the analyzed enum type and the repeated-index rejection.
- `JavaCodeGeneratorTest.shouldGenerateCompilableEnumWithLabelLookups` compiles
  the generated enum and executes `label`, `fromLabel`, its null, and its
  unknown label;
  `JavaCodeGeneratorTest.shouldGenerateOnlyTheEnumTypesTheQueriesUse`,
  `JavaCodeGeneratorTest.shouldNameTheGeneratedEnumAndItsConstants`, and the
  four generator rejection tests cover the naming rules and the collisions.
- `SqlcjCompilerIntegrationTest.shouldGenerateOneSharedEnumForTwoEntries`,
  `SqlcjCompilerIntegrationTest.shouldDeleteTheStaleEnumOfARemovedEnumQuery`,
  and
  `SqlcjCompilerIntegrationTest.shouldReportTwoEntriesThatDefineOneEnumTypeDifferently`
  cover the shared file, the manifest, the stale deletion, and the cross-entry
  diagnostic that writes nothing.
- `PostgresIntegrationTest.shouldRoundTripEnumValues` runs the ordered DDL
  against PostgreSQL 16, compares the generated constants with
  `pg_enum` in `enumsortorder`, and round trips a null and a non-null value as
  an `INSERT` value, an equality predicate, and a result component.

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

Ordered table DDL:

- `DefaultSchemaParserTest.shouldComposeTheSchemaOfSeveralParsedSources`,
  `DefaultSchemaParserTest.shouldAppendAddedColumnsTypedLikeCreateTableColumns`,
  `DefaultSchemaParserTest.shouldRecordTheColumnConstraintsOfAnAddedColumn`,
  `DefaultSchemaParserTest.shouldDropColumnAndTheConstraintsThatListIt`,
  `DefaultSchemaParserTest.shouldRenameColumnInItsPositionAndInItsConstraints`,
  `DefaultSchemaParserTest.shouldRenameTableInItsPosition`,
  `DefaultSchemaParserTest.shouldChangeColumnTypeInItsPositionKeepingItsNullability`,
  `DefaultSchemaParserTest.shouldChangeARecordedColumnTypeBackToAMappedType`,
  `DefaultSchemaParserTest.shouldSetAndDropColumnNullability`,
  `DefaultSchemaParserTest.shouldApplyTheActionsOfOneAlterTableInOrder`,
  `DefaultSchemaParserTest.shouldDropEveryTableOfOneDropStatement`, and
  `DefaultSchemaParserTest.shouldMatchTableAndColumnNamesCaseInsensitively`
  cover the applied forms.
- `DefaultSchemaParserTest.shouldIgnoreCreateTableIfNotExistsForAnExistingTable`,
  `DefaultSchemaParserTest.shouldIgnoreAddColumnIfNotExistsForAnExistingColumn`,
  `DefaultSchemaParserTest.shouldIgnoreDropTableIfExistsForAMissingTable`,
  `DefaultSchemaParserTest.shouldIgnoreAlterTableIfExistsForAMissingTable`,
  `DefaultSchemaParserTest.shouldIgnoreDropColumnIfExistsForAMissingColumn`, and
  `DefaultSchemaParserTest.shouldReportTheMissingColumnOfAnAlterTableIfExists`
  cover the `IF [NOT] EXISTS` variants.
- `DefaultSchemaParserTest.shouldReportTheMissingTableOfAStatement`,
  `DefaultSchemaParserTest.shouldReportTheMissingColumnOfAnAlterTableAction`,
  `DefaultSchemaParserTest.shouldReportAStatementThatRepeatsATableName`,
  `DefaultSchemaParserTest.shouldReportAStatementThatRepeatsAColumnName`,
  `DefaultSchemaParserTest.shouldRejectUnsupportedAlterTableAction`,
  `DefaultSchemaParserTest.shouldRejectAlterColumnSetStatistics`, and
  `DefaultSchemaParserTest.shouldRejectAlterColumnAddIdentity` cover the
  rejected statements and their messages.
- `SqlcjCompilerIntegrationTest.shouldGenerateTheSameRepositoryFromAlteringMigrationsAndASnapshot`
  generates one compilable repository from migrations that rename, add, drop, and
  alter columns, byte-identical to the one generated from the equivalent
  snapshot, and
  `SqlcjCompilerIntegrationTest.shouldReportTheMigrationFileThatAltersAMissingTable`
  covers the file-naming diagnostic of a missing reference and that no file is
  written.

Ignored statements:

- `DefaultSchemaParserTest.shouldIgnoreDocumentedStatements` covers one spelling
  per listed statement, including the lower-case `ALTER INDEX` form and
  `CREATE OR REPLACE FUNCTION ... $$ ... $$ LANGUAGE plpgsql`, and
  `DefaultSchemaParserTest.shouldIgnoreConstraintAlterTableActions` covers the
  named and unnamed constraint actions.
- `DefaultSchemaParserTest.shouldNotResolveAnIgnoredStatementAgainstTheSchema`
  covers that an ignored statement may name an unmodeled table or column, and
  `DefaultSchemaParserTest.shouldReportTheMissingTableOfAnIgnoredConstraintAction`
  covers that an ignored `ALTER TABLE` action still resolves its table.
- `DefaultSchemaParserTest.shouldApplyAModeledActionBesideAnIgnoredConstraintAction`
  covers an ignored action beside a modeled one.
- `DefaultSchemaParserTest.shouldRejectStatementOutsideTheIgnoredList` covers the
  kind and the line of each rejected statement, including the single-quoted
  function body, and
  `DefaultSchemaParserTest.shouldRejectUnsupportedSchemaStatement` and
  `DefaultSchemaParserTest.shouldReportTheLineOfARejectedStatementAfterCommentsAndAFunctionBody`
  cover the reported line after comments that contain a statement separator and
  after a multi-line dollar-quoted body.
- `DefaultSchemaParserTest.shouldRejectASingleQuotedFunctionBodyThatCapturesALaterDollarQuotedBody`
  covers that a single-quoted body is rejected at its own line even when it
  captures a later dollar-quoted body.
- `SqlcjCompilerIntegrationTest.shouldReportTheMigrationFileAndLineOfAnUnsupportedStatement`
  covers the migration file and line of a rejected statement that follows an
  ignored one, and that no file is written.

Nulls:

- `PostgresIntegrationTest.shouldBindAndReadNullValuesThroughGeneratedCode`
  and `PostgresIntegrationTest.shouldRoundTripFloatingPointBinaryAndTimeValues`
  bind null arguments and read null results through generated code against
  PostgreSQL.
- `PostgresIntegrationTest.shouldEnforceResultCardinalitiesAgainstPostgres`
  proves that row absence is reported by `:optional` and `:one` rather than by a
  null result.
- `DefaultSchemaParserTest.shouldParseColumns`,
  `DefaultSchemaParserTest.shouldParseNullabilityOfAddedColumnTypes`,
  `DefaultSchemaParserTest.shouldParseNullabilityOfIntegerAliasColumns`, and
  `DefaultSchemaParserTest.shouldParseSerialColumnAsNotNullable` cover parsed
  nullability.

Connection ownership and transactions:

- `JdbcQueryExecutorTest` covers both construction paths for `queryOne`,
  `queryOptional`, `queryMany`, and `execute`, including
  `shouldCloseAcquiredConnectionForEachDataSourceOperation`,
  `shouldCloseAcquiredConnectionWhenDataSourceOperationFails`,
  `shouldCloseAcquiredConnectionWhenCardinalityFails`,
  `shouldLeaveCallerOwnedConnectionOpenAndItsTransactionStateUnchanged`,
  `shouldCloseStatementsAndResultSetsOfCallerOwnedConnection`,
  `shouldCloseStatementWhenExecutionFailsOnCallerOwnedConnection`,
  `shouldCloseStatementWhenCardinalityFailsOnCallerOwnedConnection`, and
  `shouldWrapSqlExceptionForCallerOwnedConnection`.
- `PostgresIntegrationTest.shouldCommitGeneratedOperationsOnCallerOwnedConnection`
  and
  `PostgresIntegrationTest.shouldRollBackGeneratedOperationsOnCallerOwnedConnection`
  run a generated affected-row write, a generated returning write, and a
  generated read on one caller-owned connection with auto-commit disabled, and
  prove the application's own `commit` and `rollback`.
