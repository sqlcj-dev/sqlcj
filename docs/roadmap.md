# sqlcj Roadmap

This roadmap defines the path from the current implementation to the first
public `0.1.0` release, the rules for choosing work after it, and the direction
beyond it. It is a scope contract, not a promise of sqlc parity and not a
backlog of every useful database feature.

sqlcj is still pre-release. The generated API, configuration, and runtime API
may change while the milestones below are implemented. Publishing is deferred
until the release gates in this document are satisfied.

## Product Direction

sqlcj is a PostgreSQL-first SQL compiler and type-safe Java data-access code
generator. A user owns the schema and the named SQL. sqlcj analyzes those
inputs at build time and generates a small, readable Java API that executes
through JDBC.

```text
schema (migrations or snapshot) + named SQL
            -> compile-time analysis
            -> generated repositories, row records, and enums
            -> small JDBC runtime
```

The product promise is:

> Write SQL, catch supported schema/query/type mistakes before the application
> runs, and call the resulting operations through an ordinary Java data-access
> object.

sqlcj is not an ORM, a migration runner, a query builder, or a Java clone of
sqlc. SQL stays visible and authoritative. Generated code has no persistence
context, dirty tracking, lazy loading, relationship management, or runtime
reflection.

The generated API is one concrete, final repository per configured query group,
constructed once from a `QueryExecutor`:

```java
AuthorRepository authors = new AuthorRepository(new JdbcQueryExecutor(dataSource));

AuthorsRow created = authors.createAuthor("Ada", null);
Optional<AuthorsRow> found = authors.findAuthor(created.id());
```

A transaction constructs another repository instance over an executor bound to
a caller-owned `Connection`. The name `Repository` describes a cohesive
data-access API; it does not imply JPA semantics.

### Product principles

- Make the common path pleasant before making the supported SQL set broad.
- Read the schema the application already maintains; never require a second
  hand-written copy of it.
- Generate ordinary Java that can be read, debugged, and constructed directly.
- Keep one explicit boundary between generated code and JDBC execution.
- Preserve known database types, parameter order, selected-column order, and
  source SQL meaning.
- Leave no dead end. When sqlcj cannot infer a type, the SQL states it with a
  cast, in any clause, rather than in a sqlcj-specific language. When a schema
  statement cannot change what sqlcj models, sqlcj ignores it.
- Never model silently wrong. A table, column, or type sqlcj cannot model
  exactly is rejected or left invisible; it never becomes a wrong Java type or
  a record with missing components.
- Analyze a finite, documented subset and reject the rest with a diagnostic
  that says what to write instead.
- Require no database, Docker, or network access at generation time.
- Add one tested vertical slice at a time; do not build abstractions for an
  unimplemented database, framework, query kind, or type system.
- Prefer one good default over configuration switches.
- Keep framework integration optional. Plain Java and JDBC remain sufficient.

## Who `0.1.0` Is For

- **A new PostgreSQL service written in Java 17 or later with Maven.** This is the primary
  target: Flyway-style SQL migrations, named SQL, generated repositories, plain
  Java or Spring.
- **An existing small or medium PostgreSQL service that adopts sqlcj
  incrementally.** sqlcj reads the service's existing SQL migrations, or a
  `pg_dump --schema-only` snapshot when the schema is not kept as SQL (for
  example Liquibase XML or a JPA-generated schema). Generated repositories run
  on the same `DataSource` and inside the same Spring-managed transaction as
  the code they replace, so a team moves one query group at a time from
  `JdbcTemplate`/`JdbcClient`, MyBatis mappers, or JPA native queries. There is
  no big-bang migration and no conversion tool.
- **Larger organizations** can evaluate `0.1.0` on one service, but it makes no
  compatibility promise and has no Gradle plugin, multi-schema support, batch
  execution, streaming, or nullness annotations. Those belong to the
  [post-release tracks](#after-010) and to the
  [1.0 criteria](#long-term-direction-and-10).

sqlcj is not for applications whose main query style is runtime-built dynamic
SQL, for databases other than PostgreSQL, or for teams that want entity
lifecycle management. Those are deliberate boundaries, not missing features.

## Where sqlcj Stands

Milestones 1 through 21 are completed (see
[Completed Milestone Record](#completed-milestone-record)). The implementation
provides:

- Java 21, Maven, a Picocli CLI, strict versioned YAML configuration, and
  `generate --config <path>`,
- PostgreSQL schemas read from a snapshot or an ordered Flyway-style migration
  directory through JSqlParser 5.4, with ordered table DDL, enums, a documented
  list of ignored statements, and unmapped column types recorded until a query
  uses them,
- scalar, temporal, `UUID`, `NUMERIC`, `BYTEA`, `JSON`/`JSONB`, enum, and
  one-dimensional array columns mapped to Java types,
- named `:one`, `:optional`, `:many`, and `:exec` queries with `$N` or `:name`
  placeholders,
- one generated repository per query group, top-level `<TableName>Row` records
  and enums shared by every repository of a package, and query-specific
  `<QueryName>Result` records,
- inner and left equijoins; comparison, `IN`, pattern, null, range, and
  `= ANY` list predicates; cast-typed placeholders in `WHERE` and write values;
  `LIMIT`/`OFFSET`; aliased `COUNT(*)` and cast projections beside grouped
  columns; non-binding write expressions; `ON CONFLICT` upserts; `RETURNING`,
- deterministic output, a generated-code notice, manifest-driven stale-file
  cleanup, and one-line actionable CLI diagnostics,
- a dependency-free runtime with `DataSource` and caller-owned `Connection`
  execution and cardinality exceptions naming the repository and query,
- PostgreSQL 16 integration tests, separate runtime and CLI artifacts, and an
  external Maven sample verified by `scripts/verify-release.sh`.

### First-contact probe

The compiler core is sound and every documented shape is tested. The open
question was whether an ordinary service can adopt it. On 2026-10-05 the CLI
built from `8c8637e` was run against an original, sqlcj-owned probe: the
migration history and queries of a small multi-tenant task-tracking service,
written the way a Java team typically writes them, one statement or query at a
time. Nothing was fetched from sqlc. The results:

| Area | Outcome today | Examples |
| --- | --- | --- |
| Schema statements that load | Accepted | identity and generated columns, `public.`-qualified tables, partial, expression, `GIN`, and `CONCURRENTLY` indexes, PL/pgSQL functions and triggers, sequences, data statements, `citext`/`inet`/`interval` columns recorded as unmapped |
| Schema statements that stop the whole schema | Rejected | `ALTER COLUMN ... SET DEFAULT` and `DROP DEFAULT`, `CREATE`/`DROP VIEW`, materialized views, `CREATE SCHEMA`, `SET`, `TRUNCATE`, row-level security and policies, roles and default privileges, `OWNER TO`, `VALIDATE CONSTRAINT`, `EXCLUDE` constraints, domains, composite types, `ALTER TYPE ... RENAME VALUE` |
| Schema statements the parser cannot read | Syntax error for the whole file | `DO` blocks, `DROP TYPE`, `COMMENT ON TYPE`/`INDEX`, `SET search_path`, `ALTER COLUMN ... SET DATA TYPE`, unnamed `ADD CHECK`, `NOT VALID` |
| Silent mis-models | Wrong model, no error | `CREATE TABLE ... (LIKE t INCLUDING ALL)` is modeled with no columns, so `SELECT *` generates an empty record; tables of other schemas collapse into the default namespace, so `SELECT * FROM reporting.users` compiles against `users` |
| Internal errors | Raw Java exception text | `CREATE TABLE ... PARTITION OF` (`NullPointerException`), `INSERT ... SELECT` and `UNION ALL` (`ClassCastException`) |
| Queries that compile as written | Accepted | named-parameter pagination, `FOR UPDATE [SKIP LOCKED]`, optimistic locking, `now()` writes, upserts, `= ANY`, three-table joins, `GROUP BY` with `COUNT(*)`, `DISTINCT`, `RETURNING` |
| Queries that compile with a cast | Accepted after `::type` | `lower(:email::text)`, `ILIKE '%' \|\| :q::text \|\| '%'`, JSONB path comparisons, `IS DISTINCT FROM`, keyset row comparison, array operators, `MAX`, `COUNT(col)`, `COALESCE(SUM(...))`, and window projections |
| Queries with no way through | Rejected even with casts | a join `ON` with more than one condition (composite keys, `deleted_at IS NULL` filters), any placeholder inside a subquery (`EXISTS`, `IN (SELECT ...)`, scalar), any placeholder in `ORDER BY` (so no dynamic sort) or a cast `LIMIT`, a CTE used as a source, `ON CONFLICT DO NOTHING` without a target, `ON CONFLICT ON CONSTRAINT`, multi-row `VALUES`, `INSERT ... SELECT`, `UPDATE ... FROM`, aliased `RETURNING` items, `UNION`, `RIGHT`/`USING`/`LATERAL` joins |
| Generated API shape | Poor | `SELECT t.*, p.* FROM tasks t JOIN projects p ...` returns one flat record with components `id1`, `organizationId1`, ..., `id2`, `createdAt2` |

The conclusion is that sqlcj is not ready for `0.1.0`, and the reason is
specific and finite: an ordinary service hits a dead end or a silent mis-model
on its first day, in its schema history and in a handful of very common query
shapes. The gap is not SQL breadth or missing features. Most of it closes by
making two existing principles hold everywhere — the cast as the universal
escape hatch, and ignoring what cannot change the model — plus one mechanism,
nested analysis scopes, that the subquery, CTE, and multi-row write shapes
share.

JSqlParser 5.4 (2026-09-13) is still the latest release on Maven Central, and
it cannot read the statements in the third row above.

## What the Comparable Tools Teach Us

The goal of this research is to adopt durable interaction patterns, not feature
lists.

### sqlc

sqlc (1.31.1, 2026-04) parses PostgreSQL with the real PostgreSQL parser, builds
a catalog from schema files or migration directories, and generates one typed
method per named query on a `Queries` object bound to one database handle.
`WithTx` rebinds the same API to a transaction. Enums become generated types,
arrays become slices, and `JSON`/`JSONB` become raw bytes unless overridden.
Where sqlc cannot infer a type it relies on SQL itself: a cast such as
`$1::text`, the `sqlc.arg`/`sqlc.narg` macros, optional filters written as
`(sqlc.narg('x')::text IS NULL OR col = sqlc.narg('x'))`, and
`= ANY($1::bigint[])` instead of dynamic `IN` lists.

Three lessons matter for sqlcj's plan:

- **Static analysis has a ceiling.** Even with the real PostgreSQL parser, sqlc
  documents that its built-in analyzer "can't handle some complex queries and
  type-inference", and it added an optional database-backed analyzer, against
  a user-supplied or managed PostgreSQL, that caches its results. sqlcj's
  answer for `0.1.0` is the cast escape hatch in every clause; a database-backed
  analyzer stays a long-term option.
- **What users ask for.** Ranked by reactions on sqlc's issue tracker, the
  PostgreSQL-relevant requests are user-supplied result types, nullability
  annotations for parameters, dynamic queries and dynamic `ORDER BY`,
  composite types, observability, one-to-many result nesting, `UNNEST`-based
  bulk inserts, views, and multiple schemas. Dynamic queries remain
  deliberately unsupported: one SQL text is one typed method.
- **Embedding needs left-join nullability from day one.** `sqlc.embed` is
  popular, and a whole cluster of its open issues is about embedded rows from
  `LEFT JOIN`s that cannot be null. sqlcj embeds a joined table row only with a
  defined absence rule (Milestone 25).

Adopt: schema input from the migrations the application already has, a
cohesive API bound once to an execution boundary, explicit cardinality,
generated enums and reusable table models, casts as the user's type
statement, `= ANY` instead of dynamic `IN` expansion, and one SQL text per
method.

Already covered differently: `:exec` returns the affected-row count (sqlc
`:execrows`), `RETURNING` replaces `:execlastid`, and `:optional` replaces a
"no rows" error sentinel with `Optional`.

Do not copy for `0.1.0`: multiple engines and languages, plugins, cloud and
database-backed analysis, `vet`/`verify`, batch and `COPY` commands, macros,
type overrides, renames, and `emit_*` switches.

References: [sqlc configuration](https://docs.sqlc.dev/en/latest/reference/config.html),
[query annotations](https://docs.sqlc.dev/en/latest/reference/query-annotations.html),
[macros](https://docs.sqlc.dev/en/latest/reference/macros.html),
[schema and migrations](https://docs.sqlc.dev/en/latest/howto/ddl.html),
[analyzers](https://docs.sqlc.dev/en/latest/howto/generate.html),
[changelog](https://docs.sqlc.dev/en/latest/reference/changelog.html),
[dynamic queries request](https://github.com/sqlc-dev/sqlc/issues/3414), and
[issues ranked by reactions](https://github.com/sqlc-dev/sqlc/issues?q=is%3Aissue+sort%3Areactions-%2B1-desc).

### SQL-first and compile-time-checked tools on the JVM and elsewhere

- [sqlc-gen-java](https://github.com/tandemdude/sqlc-gen-java) is a community
  sqlc WASM plugin generating Java records and a `Queries` class, with JSpecify
  nullness annotations. It needs the Go sqlc toolchain and sqlc's
  configuration, and it is early (0.0.7, 2025-07).
- [SQLDelight](https://sqldelight.github.io/sqldelight/latest/jvm_postgresql/)
  generates Kotlin query classes from `.sq` files and `.sqm` migrations as a
  Gradle plugin: a build plugin and migration-derived schema are the expected
  JVM workflow.
- [Manifold SQL](https://github.com/manifold-systems/manifold/tree/master/manifold-deps-parent/manifold-sql)
  types native SQL through a `javac` plugin and a live database: full coverage,
  at the cost of a database and a compiler plugin in every build.
- [sqlx](https://github.com/launchbadge/sqlx/blob/main/sqlx-cli/README.md)
  (Rust) checks queries against a live database at compile time and commits the
  results (`sqlx prepare`) so that ordinary builds need no database. It is the
  model to study if sqlcj ever adds database-backed analysis.

### Jakarta Persistence, Hibernate, and Spring Data

Jakarta Persistence manages entities through a persistence context; Spring Data
adds repository interfaces, derived queries, and runtime proxies; Spring Data
JDBC keeps aggregate mapping without a persistence context. Hibernate 7's
[Data Repositories](https://docs.hibernate.org/orm/7.4/repositories/html_single/)
generate repository implementations at compile time and fully type-check HQL,
but state that "native SQL queries cannot be validated at compile time".

That sentence is sqlcj's niche: build-time checking of native PostgreSQL SQL,
without entities. sqlcj adopts the call-site clarity of
`AuthorRepository.find...()` and runs inside Spring-managed transactions
without depending on Spring, through Spring's
[`TransactionAwareDataSourceProxy`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/TransactionAwareDataSourceProxy.html),
which exists for code that takes a plain `DataSource`. Coexisting with JPA in
one transaction is sqlcj's migration path for JPA services. Do not adopt entity
state, derived queries, proxies, implicit CRUD, or a mandatory container.

### MyBatis, Jdbi, and Doma

MyBatis groups mapped statements on mapper interfaces; Jdbi SQL Objects bind a
declared interface to a handle; Doma generates DAO implementations at compile
time and checks that SQL templates and DAO parameters agree, but not against a
schema. All three bind parameters by name, so Java developers expect a named
placeholder. MyBatis's dynamic SQL is its most used and most complex feature,
and it is exactly what makes its statements impossible to type-check ahead of
time. A MyBatis user moving to sqlcj rewrites `<if>` filters as the
`(:x::type IS NULL OR ...)` idiom and `<foreach>` lists as `= ANY(:ids)`.

References: [MyBatis dynamic SQL](https://mybatis.org/mybatis-3/dynamic-sql.html),
[Jdbi SQL Objects](https://jdbi.org/releases/3.49.6/), and
[Doma](https://docs.domaframework.org/).

### jOOQ and MyBatis Generator

jOOQ and MyBatis Generator generate reusable table records and run as Maven and
Gradle plugins. jOOQ's [`DDLDatabase`](https://www.jooq.org/doc/latest/manual/code-generation/codegen-meta-sources/codegen-ddl/)
generates from DDL scripts by replaying them in an in-memory H2 database, so
PostgreSQL-specific DDL does not always translate, and the common PostgreSQL
setup instead applies Flyway migrations to a Testcontainers database during
the build. They also show how quickly a schema metamodel, a SQL DSL, generated
CRUD, and naming options become a product of their own. sqlcj generates a
table's row type once per package and plugs into the Maven build, but generates
only methods backed by user-written named SQL, with no DSL and no CRUD.

## Definition of the `0.1.0` Release

`0.1.0` is the first public, useful SQL-first Java data-access release. It does
not need broad PostgreSQL parity. It needs one coherent workflow that an
ordinary PostgreSQL service can adopt without rewriting its schema, without
dead ends in its everyday queries, and without fighting the generated API.

"Enough" is defined by evidence, not by a feature count: `0.1.0` is ready when
the [reference service](#the-reference-service) works end to end and the
[release gates](#release-gates) pass. It is not ready before that, and it does
not wait for anything beyond it.

A user must be able to:

1. Point a query group at the application's existing Flyway-style migration
   directory, a schema snapshot, or a `pg_dump --schema-only` snapshot, and
   have a realistic history load: sqlcj stops only at a statement that changes
   a table or enum in a way it cannot model.
2. Map common PostgreSQL column types, including `TIMESTAMPTZ`, enums, `JSONB`,
   and one-dimensional arrays, to known Java types, and share one generated row
   record and enum per table and type across all repositories of a package.
3. Write the reference service's queries in SQL with named parameters: CRUD,
   exact and optional lookups, search, optional filters, dynamic sorting,
   offset and keyset pagination, counts and common aggregates, grouping,
   upserts, id lists, membership checks with `EXISTS`, CTEs, joins with
   composite or filtered conditions, multi-row and `SELECT`-sourced inserts,
   and joined updates. A cast is needed only where no column gives the type.
4. Receive a joined table's whole row as its shared row record, and explicit
   zero/one/many cardinality without nullable top-level results.
5. Receive, for any statement sqlcj does not accept, a diagnostic that names
   the query and says what to write instead, and never an internal error.
6. Generate repositories from the Maven build or from the CLI, deterministically
   and without stale Java.
7. Construct a repository from a `DataSource`-backed executor, reconstruct it
   over a caller-owned `Connection` for a transaction, and use it inside a
   Spring-managed transaction, including one shared with JPA, without sqlcj
   depending on Spring.
8. Consume documented runtime, CLI, and Maven plugin artifacts from Maven
   Central on Java 17 or later, without cloning this repository.

The first release targets PostgreSQL through blocking JDBC and Maven. That
narrow target is a strength, not unfinished portability work.

### The reference service

The reference service is a sqlcj-owned, original, realistic application used as
the acceptance fixture of Milestones 22 through 27: a small multi-tenant
work-tracking service (organizations, users, projects, memberships, tasks,
comments, and an audit log). It grows out of the 2026-10-05 probe.

- **Schema history.** About ten Flyway migrations that evolve the schema the
  way a real team does: identity and serial keys, enums and an enum
  replacement, `JSONB` and arrays, a column default changed later, a view, a
  function and trigger, a `DO` block, row-level security, indexes and
  comments, a partitioned table, and a table in a second schema that no query
  uses.
- **Query catalogue.** About fifty named queries covering requirement 3 of the
  definition above, written as a Java team would write them, with casts only
  where no column gives the type.
- **Unsupported catalogue.** The probe's constructs that stay outside `0.1.0`,
  each with the diagnostic sqlcj reports.

Each of Milestones 22 to 25 adds the part of the reference service it makes
work and proves it against PostgreSQL. Milestone 26 generates it through the
Maven plugin, and Milestone 27 runs it as an application in plain Java and in a
Spring-managed transaction. A query outside the catalogue is not a `0.1.0`
requirement. Like every fixture, it is original SQL; nothing is copied or
fetched from sqlc.

## `0.1.0` Non-Goals

The following are explicitly out of scope for the first release.

### Persistence model

- JPA entities, persistence contexts, dirty checking, cascades, lazy loading,
  relationship management, or automatic unit-of-work behavior.
- Derived queries from Java method names, automatic CRUD repositories, runtime
  repository proxies, classpath scanning, or generated interface/implementation
  pairs.
- A requirement on Spring, Jakarta, Quarkus, Micronaut, CDI, or another
  container.

### Schema input

- MySQL, SQLite, Oracle, SQL Server, or a generic multi-dialect abstraction.
- Executing, rolling back, diffing, or verifying migrations; database
  provisioning; live database introspection; or database-backed analysis at
  generation time.
- Liquibase XML, YAML, or JSON changelogs (a `pg_dump --schema-only` snapshot
  covers those schemas), Flyway Java migrations, Flyway placeholder
  substitution, and down/undo migrations.
- PostgreSQL schemas (namespaces) other than the default: their tables are
  ignored, not modeled. Views and materialized views as query sources, table
  inheritance, and set-returning functions as sources.
- DDL executed inside `DO` blocks or function bodies, which sqlcj does not see.

### SQL breadth

- Set operations, recursive and data-modifying CTEs, `LATERAL`, `RIGHT`,
  `FULL`, `NATURAL`, and `USING` joins, `MERGE`, and expression type inference
  without a cast beyond the aggregates Milestone 25 lists.
- Dynamic SQL, dynamic `IN` expansion, optional-predicate macros, generated
  query variants, or a Java query DSL. Dynamic sorting is written in SQL with
  `CASE` and a cast placeholder.
- JDBC batch execution and `COPY`. Bulk writes use multi-row `VALUES` or
  `unnest(:ids::bigint[])`.

### Types and customization

- Domains, composites, ranges, multiranges, geometric, network, `INTERVAL`,
  `MONEY`, `XML`, `citext`, and multidimensional arrays.
- Type overrides, custom codecs, mapping `JSONB` to a JSON library type, naming
  overrides, user-supplied result classes, nullness annotations, and output
  style switches.

### Runtime and integrations

- Reactive or asynchronous APIs, streaming cursors, connection pooling,
  transaction orchestration, retries, tracing, metrics, or statement caching.
  Logging and metrics are added by decorating `QueryExecutor` or the
  `DataSource`, which the documentation shows.
- A Gradle plugin, a check/verify goal, IDE integration, watch mode, native
  images, installers, or package-manager distribution.
- A Spring Boot starter, framework transaction adapters, or generated
  framework annotations.
- Other output languages, generator plugins, hosted services, telemetry, or
  cloud features.

Unsupported features must be rejected when they enter an analyzed location;
they are not release defects merely because the underlying SQL parser can
represent them.

## Owner Decisions

The owner decided D-A to D-E on 2026-10-05, accepting each recommendation, and
D-F was decided on 2026-10-09 as recommended, by the orchestrator to whom the
owner delegated the open decisions. They bind the milestones below; like every
pre-release decision, they may be revisited by evidence (see
[Roadmap Update Workflow](#roadmap-update-workflow)).

- **D-A — Java 17 baseline.** The runtime, the generated code, the CLI, and the
  Maven plugin target Java 17, built with `--release 17` and tested on Java 17
  and Java 21. Spring Framework 7 and Spring Boot 4 keep a Java 17 baseline, and
  2026 surveys put Java 17 at roughly a quarter to a third of production use.
  The runtime artifact and the Maven plugin are loaded by the application's JVM
  and the build's JVM, so a Java 21 baseline would exclude those teams entirely
  for no product benefit: generated code uses only records, text blocks, and
  `Optional`. Compiling the sources at `8c8637e` with `--release 17` fails only
  on two record patterns in the runtime and on twelve Java 21 library calls and
  one pattern in the CLI; Jackson 3, JSqlParser, and Picocli all support Java
  17. Milestone 22 applies it first, so no later milestone adds Java 21-only
  code.
- **D-B — Statement boundaries in schema files.** sqlcj finds statement
  boundaries in a schema file with JSqlParser's own lexer and parses each
  statement separately, so that one statement the parser cannot read fails only
  itself, not the whole file. This supersedes the part of Milestone 19's D2
  that forbids splitting SQL. D2 waited for a JSqlParser release that reads more
  `COMMENT ON` targets; none exists, and the probe found seven unreadable
  everyday forms, including every `DO` block and `DROP TYPE`. An applied Flyway
  migration cannot be edited without breaking its checksum, so without this
  decision such a history has no way through. Using the parser's own lexer
  keeps one SQL grammar in the project; the parser's error-recovery mode stays
  rejected for the reasons D2 gives.
- **D-C — Apache License 2.0**, the Java ecosystem norm, with an explicit patent
  grant. The `LICENSE` file may be added at any time; Milestone 28 adds the
  license metadata of the published artifacts.
- **D-D — Maven Central namespace `dev.sqlcj`.** It matches the existing
  `groupId` and the `dev.sqlcj` Java packages used throughout generated code
  and documentation, so no rename is needed. Central verifies it with a DNS
  `TXT` record on `sqlcj.dev`, which was not registered on 2026-10-05, so the
  owner registers that domain before Milestone 28; registering it early also
  protects the package name. Only if registration fails does the `groupId`
  become `io.github.sqlcj-dev`, verified through the GitHub organization, with
  the Java packages unchanged.
- **D-E — Publisher identity.** The owner publishes from a Central Portal
  account they control and is the project's developer in the published POM.
  Artifacts are signed with a dedicated project signing key. Credentials and
  key material stay outside the repository and its CI logs.
- **D-F — Unreadable `ALTER TABLE` statements classified by their tokens.**
  An `ALTER TABLE` statement the parser cannot read is classified with the
  lexer D-B adopts, without rewriting it. It is ignored when each of its
  top-level actions begins with `ADD CONSTRAINT <name>`, with `ADD` followed by
  `CHECK`, `UNIQUE`, `PRIMARY KEY`, `FOREIGN KEY`, or `EXCLUDE`, or with
  `ALTER [COLUMN] <name> ADD GENERATED`; every other unreadable
  `ALTER TABLE`, including `ALTER COLUMN ... SET DATA TYPE`, stays rejected
  naming its file and line. JSqlParser 5.4 cannot read the identity form
  `pg_dump` writes for every identity column
  (`ADD GENERATED ... AS IDENTITY (SEQUENCE NAME ...)`), nor constraint
  additions with `EXCLUDE`, `DEFERRABLE INITIALLY DEFERRED`,
  `UNIQUE USING INDEX`, or `NOT VALID`, nor an unnamed `ADD CHECK`, so without
  this decision no `pg_dump` snapshot with an identity column loads. None of
  the ignored forms can change a modeled column's existence, name, type, or
  nullability: nullability comes from `NOT NULL` alone and never changes a
  generated Java type, and PostgreSQL requires a column to be `NOT NULL` before
  `ADD GENERATED ... AS IDENTITY`. Reusing the D-B lexer adds no second
  grammar. Milestone 22's requirement 7 applies it.

## Milestone Status

| Milestone | Status | Outcome |
| --- | --- | --- |
| 1–9 — Initial compiler and JDBC path | Completed | Named SQL becomes typed, executable Java for the first CRUD subset |
| 10 — Trustworthy compiler boundary | Completed | Accepted parameters and generated JDBC bindings agree |
| 11 — PostgreSQL schema/type baseline | Completed | The supported schema and scalar types run against PostgreSQL |
| 12 — Returning writes | Completed | Supported writes can return typed rows |
| 13 — Transaction-ready runtime | Completed | Generated operations run through a `DataSource` or caller-owned `Connection` |
| 14 — Packaging and adoption baseline | Partially completed | Runtime/CLI split, docs, and external sample are complete; publication (M14-W4) moved to Milestone 28 |
| 15 — Repository-shaped generated API | Completed | One configured query group becomes one generated repository |
| 16 — Result and cardinality contract | Completed | Reusable row types and explicit exact/optional/many behavior |
| 17 — Everyday PostgreSQL read slice | Completed | A bounded set of common read operations becomes practical |
| 18 — Generation workflow hardening | Completed | Configuration and generated output are repeatable and clean |
| 19 — Schema from migrations | Completed | sqlcj reads the schema the application already maintains |
| 20 — Shared models and everyday PostgreSQL types | Completed | Common column types map to Java, and table rows and enums are generated once per package |
| 21 — Everyday SQL expressions | Completed | Common write, upsert, filter, grouping, and id-list idioms compile as written, with named parameters |
| 22 — Real migration histories load | In progress | The build targets Java 17, a realistic migration history or `pg_dump` snapshot loads, and nothing is mis-modeled silently |
| 23 — A way through for every query | Planned | Casts work in every clause, common join and conflict forms compile, and no internal error reaches the user |
| 24 — Nested queries and multi-row writes | Planned | Subqueries, CTEs, multi-row and `SELECT`-sourced inserts, and joined writes are analyzed |
| 25 — Result shapes settled | Planned | Joined table rows embed their shared row record, and common aggregates need no cast |
| 26 — Maven build and Spring transactions | Planned | Generation runs in the Maven build, and repositories work in Spring-managed transactions |
| 27 — `0.1.0` release candidate | Planned | The reference service runs end to end, and the public surfaces are frozen and documented |
| 28 — `0.1.0` publication | Planned; needs the `sqlcj.dev` domain (D-D) | Verified public artifacts on Maven Central |

The milestones are ordered by where an adopting user fails first: loading the
schema history (22), getting each query through at all (23), writing nested and
multi-row SQL naturally (24), and settling the result API before it becomes a
contract (25); then running generation in the build (26), proving the whole on
the reference service (27), and publishing (28). Milestone 24 builds on 23's
clause-wide placeholder handling, and 27 needs 26's plugin. Each milestone is
decomposed into work items only when it is activated.

## Milestone 22 — Real Migration Histories Load

### Goal

Load an ordinary service's migration history, or a `pg_dump --schema-only`
snapshot, unchanged, and never model a table, column, or type silently wrong.

Before its schema work, the milestone applies D-A, because every later
milestone's code must build for Java 17.

### Work items

Activated on 2026-10-09 at the owner's request. Reassessed against the
implementation at `8c8637e`, its tests, the CI workflows, the external sample,
the documentation, the dependency JARs the build resolves, and JSqlParser 5.4
run outside the repository on every form named below. Every requirement is
unimplemented or only partly implemented, as assumed. Facts that correct or
shape the plan:

- Requirement 1: the root `pom.xml` sets release 21 twice, as
  `maven.compiler.release` and as the compiler plugin's `<release>`; the
  external sample and the quickstart's POM set 21; and CI runs `mvn test` and
  `scripts/verify-release.sh` on Java 21 only. A source search finds the
  runtime's two record patterns in `JdbcQueryExecutor.bindParameters`, ten
  `List.getFirst()` calls in the CLI's main sources, and 86 `getFirst`/`getLast`
  calls in nine CLI test classes, which must also compile for the suite to run
  on Java 17. The search did not reproduce the two further library calls and
  the pattern that the 2026-10-05 compile counted, so `javac --release 17` is
  authoritative. Generated code and the sample use no Java 21 construct. Every
  dependency is Java 17 bytecode or older (Jackson 3.2.1 and JUnit 6.1.2 are
  Java 17), and Spotless 3.10.2 supports its Eclipse 4.40 formatter on Java 17,
  whose JDT core 3.46.0 requires JavaSE-17, so `spotless:check` in the
  `validate` phase runs on Java 17.
- Requirements 2, 3, and 8: `DefaultSchemaParser` ignores the enumerated M19-W6
  list and rejects every other statement and `ALTER TABLE` action as
  `Unsupported schema statement: <parser class> at line <n>`, such as `Alter`,
  `CreateView`, `Drop`, or `UnsupportedStatement`. A `CREATE TABLE` `EXCLUDE`
  constraint parses and is rejected. A composite `CREATE TYPE`, which the
  probe's second row requires to load, is rejected; ignoring it leaves a column
  of that type recorded as unmapped.
- Requirement 4: 5.4 parses `ALTER TYPE ... RENAME TO` and `RENAME VALUE`, which
  are rejected today. A column names its enum by type name, so a renamed enum's
  columns must follow it. 5.4 cannot parse `DROP TYPE`, so it is never seen;
  the replace-an-enum variant that drops the old type before renaming the new
  one onto its name therefore needs a `RENAME TO` or `CREATE TYPE` onto a
  modeled enum's name to replace that enum instead of failing as
  `Type already exists` does today.
- Requirement 5: for `(LIKE t ...)` 5.4 reports the source table and no column
  definitions, so the table is modeled with none of `t`'s columns, and it
  reports `(LIKE t, extra int)` and `(extra int, LIKE t)` alike. `PARTITION OF`,
  `AS SELECT`, and `OF type` report no column list, which reaches the column
  loop as a `NullPointerException`. `INHERITS (p)` is reported only as a table
  option, so the child is modeled without `p`'s columns, a further silent
  mis-model that the requirement's rejection covers.
- Requirement 6: the schema parser and the analyzer's table lookup both drop the
  schema qualifier, so `reporting.users` and `public.users` are both `users`.
  `pg_dump` qualifies every user table, type, and column type with `public.`,
  so a `public.`-qualified column type must resolve to the modeled enum. 5.4
  reports `ALTER TABLE ... SET SCHEMA` as an unspecific action that carries its
  text, as it does `OWNER TO`, `VALIDATE CONSTRAINT`, and trigger switches.
- Requirement 7 and D-B: 5.4's token manager, run alone over a file, reports
  separators outside quoted strings, quoted identifiers, nested block comments,
  and untagged `$$` bodies, which it lexes as one token. It reports a tagged
  delimiter such as `$body$` as a token of its own and separators inside that
  body, and it mis-lexes an `E'...\'...'` escape string. A lexical error,
  including the first character of a psql meta-command line such as
  `\restrict`, ends the token stream, so meta-command lines are skipped before
  lexing and a tagged body must be kept whole by pairing its delimiters.
- Requirement 9: JSqlParser 5.4 is still the latest release (Maven Central
  metadata last updated 2026-09-13; nothing newer on 2026-10-09), so no upgrade
  is possible. 5.4 fails on all three named forms and on four `ALTER TABLE`
  forms the requirement does not name:
  `ALTER COLUMN ... ADD GENERATED ... AS IDENTITY (SEQUENCE NAME ...)`, which
  `pg_dump` writes for every identity column, and `ADD CONSTRAINT` with
  `EXCLUDE`, with `DEFERRABLE INITIALLY DEFERRED`, or as `UNIQUE USING INDEX`.
  It also cannot read `DO`, `DROP TYPE`, `DROP POLICY`, `DROP DOMAIN`,
  `COMMENT ON` targets other than `TABLE`, `COLUMN`, and `VIEW`, `SET ... TO`
  with a list, `VACUUM`, `LOCK`, `BEGIN`, `COPY`, `CREATE RULE`, or
  `CREATE EVENT TRIGGER`, all of which requirement 7 ignores. Filing the
  upstream reports publishes content and is the owner's action.

D-F, proposed here on 2026-10-09, was decided the same day as recommended and
is recorded under [Owner Decisions](#owner-decisions); requirement 7 is amended
accordingly. Filing the upstream reports of requirement 9 stays the owner's
action; M22-W9 prepares them.

Decided by the architect on 2026-10-09, under the rule that pre-release
decisions are revisable by evidence:

- **Implementation order:** M22-W1, W2, W3, W4, W7, W5, W6, W9, then W8,
  revised the same day at the owner's request to take W2 first; the original
  order put W3 before W2. A probe of JSqlParser 5.4 on every second-row form
  and on further table, type, function, and session statements found that only
  a `CREATE FUNCTION` or `CREATE PROCEDURE` whose body is not dollar-quoted
  captures later statements when the whole file is parsed; every other parsed
  statement ends at its separator, and the token-chain lines of parsed
  statements are already exact. W2 therefore keeps rejecting such a function or
  procedure, as today, and W3 lifts that rejection once each statement is
  parsed alone. W2 fixes the SQL-term diagnostics and the table-or-type
  classification of an opening that W3 to W7 reuse.
  W7 follows W4, whose action classification its `SET SCHEMA` joins, and
  precedes W5 and W6, so their new type and table lookups resolve names in one
  namespace from the start. W9 precedes W8, so the acceptance runs on the
  parser version the milestone closes with. Revised on 2026-10-10 at the
  owner's request to take W5 before W7, giving W1, W2, W3, W4, W5, W7, W6, W9,
  then W8; see the `ALTER TYPE` decision below for the interim rule that keeps
  W5 free of silent mis-models until W7 lands. Revised again on 2026-10-10 at
  the owner's request to take W6 before W7, giving W1, W2, W3, W4, W5, W6, W7,
  W9, then W8; see the `LIKE` and `PARTITION OF` decision below for the interim
  rule that keeps W6 free of silent mis-models until W7 lands. Revised on
  2026-10-10 at the owner's request to take W8 before W9, giving W1 to W7, W8,
  then W9: Maven Central still lists JSqlParser 5.4 as the latest release
  (metadata last updated 2026-09-13, rechecked 2026-10-10), so W8 runs on the
  parser version W9 can close with unless a release appears first. W9 stays the
  milestone's last item and makes the closing recheck; if it adopts a newer
  release, the full suite it runs includes W8's reference-service test, which
  re-proves the acceptance on that version.
- **`SET DATA TYPE` stays rejected.** Requirement 7, D-F, and requirement 9
  keep the unreadable `ALTER COLUMN ... SET DATA TYPE` rejected, which the
  first acceptance item, read literally, contradicts. The reference service's
  history changes a column type with `ALTER COLUMN ... TYPE`, its unsupported
  catalogue records `SET DATA TYPE` with the diagnostic sqlcj reports, and the
  acceptance item is amended accordingly.
- **Probe inputs are its schema inputs.** The acceptance's "no probe input
  produces an internal error" covers the probe's schema inputs. Its query
  inputs that raise one, `INSERT ... SELECT` and `UNION ALL`, are Milestone
  23's requirement 6.
- **Opaque statements.** A statement the parser returns only as an opaque
  unsupported statement is treated as unreadable, so an opaque table or type
  statement is never ignored silently (M22-W2).
- **`ALTER TYPE` actions (M22-W5).** `OWNER TO` and the attribute actions of
  composite types are ignored without resolving the type, and so is
  `RENAME TO` of a type sqlcj does not model; `ADD VALUE` and `RENAME VALUE`
  still require a modeled enum. `SET SCHEMA` of a modeled enum is rejected,
  because its columns keep the type in a namespace sqlcj does not model. A
  `RENAME TO` or `CREATE TYPE` onto a modeled enum's name replaces that enum,
  because the `DROP TYPE` that freed the name is never seen, so
  `Type already exists` no longer fails. Refined on 2026-10-10: `SET SCHEMA`
  of a type sqlcj does not model is ignored, like `RENAME TO` of one. Until
  M22-W7, a type name is the parser's text with delimiters removed, as
  `CREATE TYPE` and `ADD VALUE` resolve it today, so `public.mood` and `mood`
  are different names; a `RENAME TO` or `SET SCHEMA` whose name contains a
  period and matches no modeled enum therefore fails as `Type not found`
  instead of being ignored, because it may target a modeled enum. M22-W7
  replaces that interim rule with its namespace rule.
- **`LIKE` and `PARTITION OF` sources (M22-W6).** Decided on 2026-10-09 to copy
  a `LIKE` only when it is the only column source of its element list, counted
  with the D-B lexer, because 5.4 was taken to report `(LIKE t, extra int)`,
  `(extra int, LIKE t)`, and `(LIKE a, LIKE b)` without their order or the
  second source. Revised on 2026-10-10: a recheck found that 5.4 reports every
  element of the list in written order, each `LIKE` with its own source table,
  so the columns are modeled in that order, as PostgreSQL creates them, each
  `LIKE` copying its source's columns at its position, and a repeated column
  name fails as `Column already exists`; the D-B lexer is not needed. A
  `LIKE`'s `INCLUDING` and `EXCLUDING` options cannot change a copied column's
  type or nullability and are ignored, and neither `LIKE` nor `PARTITION OF`
  copies the source's recorded constraints, which no generated code reads.
  A `LIKE` copy is independent of its source afterwards, as in PostgreSQL. A
  partition is not: PostgreSQL applies every column change of a partitioned
  table to its partitions, so a partition copied once would silently miss a
  column its parent gains later. A partition created by `PARTITION OF`, or
  attached by `ATTACH PARTITION` when its table is modeled, therefore stays
  linked to its parent: each modeled column action on the parent (`ADD`,
  `DROP`, and `RENAME COLUMN`, a new type, `SET` and `DROP NOT NULL`) applies
  to its partitions, recursively; renaming the parent keeps the link;
  `DROP TABLE` of the parent removes its partitions, as PostgreSQL does; and
  `DETACH PARTITION` ends the link. This revises M22-W4's ignoring of
  `ATTACH` and `DETACH PARTITION`, which stays for a table sqlcj does not
  model. Until M22-W7, a table W6 resolves, the source of `LIKE` or
  `PARTITION OF` or the partition of `ATTACH` or `DETACH PARTITION`, resolves
  by its unqualified name, as every table lookup does today, so `public.t` is
  `t`; such a name qualified with any other schema fails as `Table not found`
  instead of resolving to a modeled `t`, because the namespace rule leaves
  that table unmodeled. M22-W7 replaces that interim rule with its namespace
  rule.
- **One namespace ignores `search_path` (M22-W7).** Unqualified names resolve
  in `public` whatever `SET search_path` states, which the documentation
  states; `pg_dump` qualifies every name it writes.
- **Reference-service fixtures (M22-W8).** Its migrations, snapshot, and
  queries live under `sqlcj-cli/src/test/resources/reference-service/`, beside
  the tests that use them, until Milestone 27 makes the service a runnable
  project.
- **Statement splitting (M22-W3).** A token that begins with a tagged
  delimiter `$tag$` opens a body that ends at the next occurrence of that
  delimiter in the source, PostgreSQL's rule, and lexing resumes after it; an
  unterminated body fails the file with its location. Only a rejected
  statement is reparsed at its file position for its diagnostic. An
  `ALTER TABLE` that D-F ignores is not resolved against the schema, like
  every ignored statement.

- **M22-W1 — Java 17 baseline — Completed.** Implements requirement 1 and
  D-A. Every module compiles with `--release 17`; the runtime's record patterns
  and every Java 21 library call in main and test sources are replaced without
  changing behavior; the external sample and the quickstart compile at release
  17; CI runs `mvn test` and `scripts/verify-release.sh` on Java 17 and Java
  21; and the README, the quickstart, the development guide, and the release
  script state Java 17 or later. The boundary is the build configuration, those
  source replacements, the sample and quickstart POMs, the test workflow, and
  those requirement statements; generated code, the runtime API, diagnostics,
  and schema behavior do not change. Acceptance evidence is `mvn test` passing
  on JDK 17 and on JDK 21, runtime and CLI class files of major version 61, an
  unchanged test count and unchanged generator expectations,
  `mvn spotless:check`, and `scripts/verify-release.sh` on both JDKs where
  PostgreSQL is reachable.
  Review evidence: the root POM, the external sample, and the quickstart POM
  set release 17; the runtime's record patterns became type patterns with
  record accessors, `getFirst`/`getLast` became index access in main and test
  sources, `SourceVersion.RELEASE_21` became `RELEASE_17` (its keyword answers
  are identical for the probed words), and the parameter compiler's
  unconditional `instanceof` became a null check with the same message. The
  test diff changes no string literal or expectation. The test workflow runs
  on a Java 17 and 21 matrix. Reviewer runs: `mvn spotless:check` passed;
  `mvn -Dsqlcj.test.requireDocker=true clean test` passed with 32 + 912 = 944
  tests on JDK 17 and on JDK 21; every runtime and CLI class file is major
  version 61; `scripts/verify-release.sh` passed on JDK 17 against a
  disposable PostgreSQL 16.
- **M22-W2 — One rule for ignored statements, in SQL terms — Completed.**
  Implements requirements 2 and 8. Every parsed statement other than `CREATE`,
  `ALTER`, or `DROP TABLE` and `CREATE` or `ALTER TYPE` is ignored without being
  resolved against the schema, replacing the enumerated list, and a
  `CREATE TYPE` that is not an enum is ignored; an opaque statement that begins
  as table or type DDL is handled as unreadable and rejected at its line. A
  table opening is `CREATE`, `ALTER`, or `DROP`, then any of PostgreSQL's table
  modifiers (`GLOBAL`, `LOCAL`, `TEMP`, `TEMPORARY`, `UNLOGGED`, `FOREIGN`),
  then `TABLE`; a type opening is `CREATE` or `ALTER` then `TYPE`. Until M22-W3,
  a `CREATE FUNCTION` or `CREATE PROCEDURE` whose body is not dollar-quoted
  stays rejected, because parsed with the whole file it captures every later
  statement. Every schema diagnostic names the statement or action in SQL
  terms, never a parser class; a rejected `ALTER TABLE` or `ALTER TYPE` action
  names its statement, and M22-W4 and M22-W5 name the action. The boundary is
  statement classification, schema diagnostics, and the schema documentation,
  including that DDL inside `DO` blocks and function bodies and columns removed
  by `DROP TYPE ... CASCADE` are not seen; `ALTER TABLE` actions are M22-W4's
  and `ALTER TYPE` actions M22-W5's. Acceptance evidence is schema-parser tests
  for each parsed statement kind of the probe's second row and for a rejected
  statement's message, a compiler test naming the file and line,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser.apply` models only `CREATE`, `ALTER`,
  and `DROP TABLE`, `CREATE TYPE ... AS ENUM`, and `ALTER TYPE ... ADD VALUE`
  and ignores every other parsed statement, including views, materialized
  views, schemas, session, role, policy, domain, and non-enum `CREATE TYPE`
  statements, without resolving it; it rejects other `ALTER TYPE` actions, a
  function or procedure without a dollar-quoted body, and an opaque statement
  opening as table or type DDL with the SQL-term messages
  `Unsupported ALTER TABLE action`, `Unsupported ALTER TYPE action`,
  `CREATE FUNCTION`/`CREATE PROCEDURE without a dollar-quoted body`, and the
  upper-cased opening such as `ALTER FOREIGN TABLE`, each at its line; no
  schema diagnostic builds a message from a parser class. A reviewer probe
  against the built parser confirmed that an ignored opaque statement ends at
  its separator, so a following `CREATE TABLE` is still modeled and a later
  rejection keeps its line. Reviewer runs: `mvn spotless:check` passed; the
  focused `DefaultSchemaParserTest` and `SqlcjCompilerIntegrationTest` run
  passed with 298 tests; `mvn test` passed with 32 + 939 tests, including the
  33 `PostgresIntegrationTest` tests.
- **M22-W3 — Statement boundaries from the parser's lexer — Completed.**
  Implements requirement 7 with D-B and D-F. A line whose first non-blank
  character is a backslash, a psql meta-command such as `\restrict`, is
  blanked before lexing, keeping every line number. A schema file is split at
  the separators JSqlParser's own token manager reports, with a tagged
  dollar-quoted body kept whole, and each statement is parsed alone with its
  own line. A statement the parser cannot read, reports only as opaque text,
  or reads as several statements is ignored unless it opens as table or type
  DDL, as M22-W2 classifies an opening; such a statement is rejected with the
  parser's reason at its line and column in the file, or with M22-W2's message
  when the parser states none, except an `ALTER TABLE` whose every top-level
  action D-F names, which is ignored without resolving its table. M22-W2's
  rejection of a function or procedure body that is not dollar-quoted is
  lifted. A lexical error, or an unterminated tagged body, still fails the
  file with its location. The boundary is schema-source splitting,
  per-statement parsing and lines, D-F, and the schema documentation; query
  parsing and the handling of parsed statements are unchanged. Acceptance
  evidence is schema tests for every form of the probe's third row and the
  unreadable forms requirement 9 lists, statements around an unreadable one
  applied and a later rejection at its line, DDL inside a tagged body not
  modeled, a skipped `\restrict` line, every D-F form ignored, `SET DATA TYPE`
  rejected at its file line and column, `mvn spotless:check`, and `mvn test`.
  Review evidence: `SchemaSourceSplitter` blanks meta-command lines keeping
  line breaks, cuts statements at the token manager's separators, and resumes
  lexing after the next occurrence of a tagged delimiter, glued or not;
  `DefaultSchemaParser` parses each statement alone, applies a single parsed
  statement as before, ignores an unreadable statement that does not open as
  table or type DDL and a D-F `ALTER TABLE` without resolving its table, and
  reparses only a rejected statement at its file position. The function and
  procedure body rejection is removed, and the existing syntax, end-of-input,
  and lexical-error expectations are unchanged in the test diff. Reviewer
  probes confirmed file columns with tabs, `\r\n` and `\r` line breaks, a
  meta-command line mid-file, an empty and a glued tagged body, a `$tag$`
  inside a string or comment, and linear load time (8,000 and 16,000 lines in
  about 0.6 and 1.0 s). Reviewer runs: `mvn spotless:check` passed; `mvn test`
  passed with 32 + 983 tests, including the 33 `PostgresIntegrationTest`
  tests.
- **M22-W4 — `ALTER TABLE` actions and table constraints — Completed.**
  Implements requirement 3. Every parsed `ALTER TABLE` action that cannot
  change a column's existence, name, type, or nullability is ignored,
  including `SET`/`DROP DEFAULT`, `SET STATISTICS` and `STORAGE`, identity
  changes, `OWNER TO`, row-level-security and trigger switches,
  `VALIDATE CONSTRAINT`, partition attachment, and adding or dropping any
  constraint; a `CREATE TABLE` accepts a table constraint of any kind,
  including `EXCLUDE`. An action that would change columns in a way sqlcj does
  not model, or that sqlcj does not recognize, is rejected naming the file,
  line, and action in SQL terms. The boundary is action classification and
  table-constraint parsing; the M19-W5 modeled actions are unchanged, and
  `SET SCHEMA` is M22-W7's. Acceptance evidence is schema-parser tests per
  ignored and rejected action, alone and beside a modeled action,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4 and a
  PostgreSQL 16 `pg_dump`: the ignored set is every PostgreSQL 16 action 5.4
  parses that cannot change a column, which adds `SET COMPRESSION`,
  `DROP EXPRESSION`, rule switches, `REPLICA IDENTITY`, `CLUSTER ON`,
  `SET WITHOUT CLUSTER`, `SET WITHOUT OIDS`, storage parameters,
  `SET TABLESPACE`, `SET LOGGED`/`UNLOGGED`, `SET ACCESS METHOD`,
  `[NO] INHERIT`, `[NOT] OF`, and `DETACH PARTITION`. As for constraint
  actions today, an ignored action resolves its table but not its column. 5.4
  reports an action it has no form for as unspecific text that runs to the end
  of the statement, so that text is split into actions at top-level commas
  with the D-B lexer and every one must be ignored; a modeled action written
  after such an action is rejected. A rejection reads
  `Unsupported ALTER TABLE action: <action> at line <n>`, quoting the action's
  SQL. Unreadable forms, such as `ALTER COLUMN ... SET (...)` or
  `ALTER CONSTRAINT`, stay under D-F and requirement 9.
  Review evidence: `DefaultSchemaParser` returns the table unchanged for
  `SET`/`DROP DEFAULT`, for `ALTER COLUMN` entries that are all identity
  changes, `SET STATISTICS`, `STORAGE`, `COMPRESSION`, or `DROP EXPRESSION`,
  and for the row-level-security and partition operations, without resolving
  the column; it lexes unspecific text with `SchemaSourceSplitter.tokens`,
  splits it at top-level commas, and accepts it only when every action begins
  with one of the listed table-level words, otherwise rejecting the first
  other action, so `OWNER TO app, ADD COLUMN x int` and `SET SCHEMA` are
  rejected with the quoted action and the statement's line. An ignored
  parsed-form action written after such text, such as
  `OWNER TO app, ENABLE ROW LEVEL SECURITY`, is likewise rejected rather than
  ignored; written first, it is ignored. `CREATE TABLE` ignores a named or
  unnamed `EXCLUDE`. The test diff converts the three old rejection tests and
  leaves the D-F, unreadable-statement, and `ALTER TYPE` expectations
  unchanged. Reviewer probes against the built parser confirmed the ignored
  forms beside `ADD COLUMN` in either parsed order, `ALTER` without `COLUMN`,
  a missing column under `SET DEFAULT` and `DROP IDENTITY`, and
  `ATTACH PARTITION ... DEFAULT` and `DETACH PARTITION ... CONCURRENTLY`.
  Reviewer runs: `mvn spotless:check` passed on an uncached copy of the
  working tree; `mvn test` passed with 32 + 1041 tests, including the 33
  `PostgresIntegrationTest` tests.
- **M22-W5 — Enum renames — Completed.** Implements requirement 4.
  `ALTER TYPE ... RENAME TO` renames a modeled enum and the columns of that
  type follow it; `ALTER TYPE ... RENAME VALUE` renames a label in its
  position. A `RENAME TO` or `CREATE TYPE` onto a modeled enum's name replaces
  that enum, and the other `ALTER TYPE` actions follow the architect decision
  above. The boundary is enum and type-statement modeling in the schema parser
  and the enum documentation. Acceptance evidence is schema tests of both
  replace-an-enum variants and of each action, a PostgreSQL test that applies
  one variant and runs generated code over the replaced column,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4: it reports
  every named form as one `ALTER TYPE` statement whose action is `OWNER`,
  `RENAME`, `SET_SCHEMA`, `RENAME_ATTRIBUTE`, `ATTRIBUTES`, `ADD_VALUE`, or
  `RENAME_VALUE`, with the type's name as written, qualifier included. A
  qualified `RENAME TO` target, a combined action list, and `SET (...)` are
  unreadable and stay rejected as unreadable type DDL. A rejected `SET SCHEMA`
  reads `Unsupported ALTER TYPE action: SET SCHEMA <schema> at line <n>`.
  Columns, including enum-array columns, follow a rename by their recorded
  type name; a column still naming a replaced enum is the documented
  `DROP TYPE ... CASCADE` case.
  Review evidence: `DefaultSchemaParser.applyAlterType` ignores `OWNER`,
  `RENAME_ATTRIBUTE`, and `ATTRIBUTES` without resolving the type, keeps
  `ADD VALUE` as before, renames a label in its position, renames a modeled
  enum in its position and every scalar and array column recorded with its
  name, removes a modeled enum of the new name, rejects `SET SCHEMA` of a
  modeled enum with the recorded message, and ignores `RENAME TO` and
  `SET SCHEMA` of an unmodeled, unqualified type while a qualified one fails as
  `Type not found`; `CREATE TYPE ... AS ENUM` onto a modeled name replaces it
  in its position. Schema tests cover each action and both replace-an-enum
  variants, and a PostgreSQL test runs generated code over a replaced column.
  The enum documentation and the `renameEnumType` Javadoc state that a column
  still naming a replaced enum keeps that name and is modeled with the
  replacing enum's labels. Reviewer probes against the built parser confirmed
  that behavior after a `CREATE TYPE` replacement and after a `RENAME TO` onto
  the name, delimiters removed from both rename names, and a qualified
  `RENAME TO` target, `OWNER TO app, RENAME TO feel`, and `SET (...)` rejected
  as unreadable at their line and column. Reviewer runs:
  `mvn spotless:check` passed; `mvn test` passed with 32 + 1062 tests,
  including the 34 `PostgresIntegrationTest` tests.
- **M22-W6 — Tables whose columns come from another table — Completed.**
  Implements requirement 5. `CREATE TABLE ... (LIKE t ...)` and
  `CREATE TABLE ... PARTITION OF t` copy the columns, types, and nullability of
  the modeled table `t`, and an empty element list `()` is a table without
  columns; a `CREATE TABLE` whose columns sqlcj cannot determine, such as
  `AS SELECT`, `INHERITS`, or `OF type`, is rejected with its file and line.
  The boundary is `CREATE TABLE` modeling and the schema documentation.
  Acceptance evidence is schema tests per form and rejection, generated
  `SELECT *` records over a `LIKE` and a `PARTITION OF` table that compile,
  `mvn spotless:check`, and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4, taken before
  M22-W7 at the owner's request: a `LIKE` beside column definitions, table
  constraints, or further `LIKE` clauses is modeled in written order; a
  partition stays linked to its parent and follows its column changes, its
  rename, and its `DROP TABLE` until `DETACH PARTITION`; and the tables W6
  resolves follow the interim rule, all as the `LIKE` and `PARTITION OF`
  decision above states. The boundary therefore also covers the partition
  link in the schema model, propagation of the modeled `ALTER TABLE` column
  actions, `DROP TABLE`, and `ATTACH` and `DETACH PARTITION`. 5.4 reads
  `LIKE` with any `INCLUDING`/`EXCLUDING` options, `PARTITION OF t` with
  `FOR VALUES FROM ... TO`, `IN`, `WITH (MODULUS ...)`, or `DEFAULT` and an
  optional list of table constraints, which are recorded as in any
  `CREATE TABLE`, and `()`. Without an element list of column
  definitions, as for `PARTITION OF`, `AS SELECT`, `OF type`, and `()`, today's
  column loop fails as a `NullPointerException`; `INHERITS (p)` is reported
  only as a table option, so the child is modeled without `p`'s columns. A
  rejection reads `Unsupported CREATE TABLE clause: <clause> at line <n>`,
  naming `AS SELECT`, `INHERITS`, or `OF <type>`, and a `CREATE TABLE` with
  neither an element list, `()`, nor `PARTITION OF`, such as
  `CREATE TABLE c LIKE t`, reads
  `Unsupported schema statement: CREATE TABLE at line <n>`. Forms 5.4 cannot
  read, such as column options after `PARTITION OF` or `OF type`
  (`id WITH OPTIONS NOT NULL`) and `AS SELECT ... WITH NO DATA`, stay rejected
  as unreadable table DDL at their line and column. Acceptance evidence adds
  schema tests of each propagated column action, a nested partition, the
  parent's rename and `DROP TABLE`, `ATTACH` and `DETACH PARTITION`, a `LIKE`
  copy that does not follow its source, and the interim rule.
  Review evidence: `DefaultSchemaParser.parseTable` models a partition's
  parent columns first and then every element in written order, each `LIKE`
  appending its source's columns, fails a repeated name as
  `Column already exists`, records only the table's own constraints, models
  `()` as a table without columns, and rejects a table with no element list,
  `()`, or `PARTITION OF` as `Unsupported schema statement: CREATE TABLE`;
  `AS SELECT`, `OF <type>`, and `INHERITS` fail as
  `Unsupported CREATE TABLE clause: <clause> at line <n>`. `Table` records
  `partitionOf`; `ADD`, `DROP`, and `RENAME COLUMN`, a new type, and
  `SET`/`DROP NOT NULL` reach partitions recursively; a parent rename relinks
  them; `DROP TABLE` removes them recursively and resolves every name of one
  statement first, so `DROP TABLE parent, partition` loads; `ATTACH` and
  `DETACH PARTITION` link and unlink a modeled table and still ignore an
  unmodeled one; and a `LIKE`, `PARTITION OF`, `ATTACH`, or `DETACH` name
  qualified with a schema other than `public` fails as `Table not found`.
  Schema tests cover each form, rejection, propagated action, and the interim
  rule, and a compiler test compiles `SELECT *` repositories over a `LIKE` and
  a `PARTITION OF` table. Reviewer probes against the built parser confirmed
  `DROP TABLE events, events_2026` after `PARTITION OF` and after
  `ATTACH PARTITION`, and an unmodeled name failing or skipped with
  `IF EXISTS` as before. Reviewer runs: `mvn spotless:check` passed;
  `mvn test` passed with 32 + 1106 tests, including the 34
  `PostgresIntegrationTest` tests.
- **M22-W7 — One namespace — Completed.** Implements requirement 6. Unqualified
  and `public`-qualified table and type names, including column types and
  their arrays, are modeled alike; tables and types of any other schema, and
  statements that target them, readable or not, are ignored, and `SET SCHEMA`
  that moves a modeled table to another schema removes it. A query that names
  a table qualified with another schema is rejected as an unknown table. The
  boundary is name resolution in the schema parser and the analyzer and the
  schema documentation, including that `search_path` is not followed. It
  covers every `ALTER TYPE` action M22-W5 resolves and replaces W5's interim
  rule that rejects a qualified, unmodeled `RENAME TO` or `SET SCHEMA` type
  name as `Type not found`, and it covers the tables M22-W6 resolves, the
  sources of `LIKE` and `PARTITION OF` and the partitions of `ATTACH` and
  `DETACH PARTITION`, replacing W6's interim rule that rejects such a name
  qualified with a schema other than `public` as `Table not found`.
  Acceptance evidence is schema and analyzer tests showing `reporting.users`
  invisible to queries and `public.users` equal to `users`, a
  `public.`-qualified enum and enum-array column mapped, `mvn spotless:check`,
  and `mvn test`.
  Refined by the architect on 2026-10-10 against JSqlParser 5.4: it reports a
  table name's schema apart from its name, delimiters kept, in `CREATE`,
  `ALTER`, and `DROP TABLE`, `LIKE`, `PARTITION OF`, `ATTACH` and
  `DETACH PARTITION`, and every query source, but a type name in `CREATE` and
  `ALTER TYPE`, a column type, or a cast as one text with its qualifier and
  delimiters, such as `"public"."mood"`, with a qualified type's array brackets
  inside that text (`public.mood[]`) rather than as array dimensions. A schema
  qualifier is compared case-insensitively without delimiters, as every name
  is. A statement whose own table or type is qualified with another schema is
  ignored without resolving anything it names, and `DROP TABLE` skips such a
  name without failing; an unreadable `CREATE` or `ALTER TABLE` or `CREATE` or
  `ALTER TYPE` so qualified is ignored, while an unreadable `DROP TABLE` keeps
  today's handling, because another of its names may be modeled. A column or
  cast type qualified with another schema never resolves to a modeled enum and
  is recorded with its declared text, as today. `ALTER TABLE ... SET SCHEMA`,
  which PostgreSQL accepts only as the sole action, removes a modeled table,
  does nothing when the schema is `public`, and is rejected as
  `Unsupported ALTER TABLE action: SET SCHEMA <schema> at line <n>` when the
  table has modeled partitions, which would stay in `public` following a
  parent sqlcj no longer models, and beside another action, as today;
  `ALTER TYPE ... SET SCHEMA public` of a modeled enum does nothing. W6's
  interim rule gives way: a `LIKE` or `PARTITION OF` source of another schema
  is an unmodeled source and still fails as
  `Table not found in schema: <schema>.<t>`, and an `ATTACH` or
  `DETACH PARTITION` partition of another schema is an unmodeled partition and
  is ignored. W5's interim rule gives way: a `public`-qualified type name
  resolves as its unqualified name, and `RENAME TO` or `SET SCHEMA` of an
  unmodeled type is ignored however it is qualified. A query that qualifies a
  column or `qualifier.*` with a schema, and a `public` partition attached to a
  table of another schema, are namespaces as a feature and outside the
  boundary. Acceptance evidence adds schema tests of each modeled statement
  with a `public`, a quoted `"public"`, and another schema's qualifier, of
  `SET SCHEMA`, and of both replaced interim rules, and an analyzer test of a
  `public`-qualified cast type.
  Review evidence: `SchemaNamespace` states the rule once, comparing a
  qualifier case-insensitively without delimiters and splitting a type text at
  dots outside double quotes. `DefaultSchemaParser` ignores a readable
  `CREATE` or `ALTER TABLE` and `CREATE` or `ALTER TYPE` of another schema
  before resolving anything, and an unreadable one by the qualifier after the
  opening words, an optional `IF [NOT] EXISTS`, and `ONLY`; `DROP TABLE` skips
  such a name; a `LIKE` or `PARTITION OF` source of another schema fails as
  `Table not found in schema: <schema>.<t>` and an `ATTACH` or
  `DETACH PARTITION` partition of another schema is ignored; a sole
  `SET SCHEMA` removes the table, is a no-op for `public`, and is rejected for
  a table with modeled partitions and beside another action;
  `ALTER TYPE ... SET SCHEMA public` of a modeled enum is a no-op and
  `RENAME TO` or `SET SCHEMA` of an unmodeled type is ignored however
  qualified. `ColumnTypeMapping` reads a qualified type's inline array
  brackets as dimensions and resolves the enum in the one namespace, and
  `QueryAnalyzer` rejects a `FROM`, `JOIN`, `INSERT`, `UPDATE`, or `DELETE`
  source of another schema as `Table not found in schema: <schema>.<t>`.
  Reviewer probes against the built parser confirmed `SET SCHEMA` before
  `OWNER TO` or `ADD COLUMN` rejected, `IF EXISTS`, quoted-`public`, and
  multi-line `SET SCHEMA` removing the table, a moved partition leaving its
  parent, `public`-qualified `LIKE`, `PARTITION OF`, and `ATTACH PARTITION`
  resolving, `public.status RENAME TO` and `"public"."status" SET SCHEMA`
  ignored, and unmapped `public.citext[4]` and `public.mood[3][2]` recorded
  as `PUBLIC.CITEXT[]` and `PUBLIC.MOOD[][]`. Reviewer runs:
  `mvn spotless:check` passed; `mvn test` passed with 32 + 1173 tests,
  including the 34 `PostgresIntegrationTest` tests.
- **M22-W8 — Reference service history and `pg_dump` snapshot — Completed.**
  Proves the milestone acceptance. Adds the reference service's schema history
  as original, sqlcj-owned Flyway migrations covering every construct of the
  probe's first three rows except `SET DATA TYPE`, an equivalent hand-written
  snapshot, and a query group over its tables; a PostgreSQL test applies the
  history to a `postgres:16-alpine` Testcontainers server, runs
  `pg_dump --schema-only` inside that container, and generates byte-identical,
  compiling repositories from the history, the snapshot, and the dump, then
  runs generated queries against the migrated database. Every schema input of
  the probe either loads or fails with a located diagnostic that names no
  exception or parser class, and `SET DATA TYPE` is recorded as unsupported.
  The boundary is test fixtures, tests, and the `pg_dump` documentation;
  production changes only close a gap in M22-W2 to M22-W7's requirements that
  the fixture exposes. Acceptance evidence is that PostgreSQL test,
  `mvn spotless:check`, and `mvn test` on Java 17 and Java 21.
  Refined by the architect on 2026-10-10 against the sources at `f4f9ab1`,
  JSqlParser 5.4, and the `postgres:16-alpine` image's PostgreSQL and
  `pg_dump` 16.15: a draft ten-migration history covering every construct of
  the probe's first three rows except `SET DATA TYPE`, plus a `LIKE` copy, two
  partitions, an enum replacement, and a table of a second schema sharing a
  `public` table's name, was applied with `psql`, and the history, its
  `pg_dump` snapshot, and a hand-written snapshot generated byte-identical
  output trees that compile, so no gap in M22-W2 to M22-W7 was found and no production
  change is planned. `pg_dump` writes each partition as a plain
  `CREATE TABLE` followed by `ALTER TABLE ONLY ... ATTACH PARTITION`, which
  M22-W6's link covers. The fixture avoids one parser limit: 5.4 reads
  `settings` as a keyword, so an unquoted column of that name fails its
  `CREATE TABLE` and any query naming it; M22-W9's report includes it. The
  `pg_dump` output is produced at test time and not committed, because each
  run writes a new `\restrict` key. The unsupported catalogue is a fixture file
  applied after the history whose rejection the test asserts with its file,
  line, and column, and a query naming the second schema's table is asserted
  rejected as an unknown table. The documentation adds the `pg_dump` snapshot
  as a schema input.
  Review evidence: the reference service's ten sqlcj-owned migrations, its
  hand-written snapshot, its Tracker query group, and the unsupported
  `SET DATA TYPE` fixture live under
  `sqlcj-cli/src/test/resources/reference-service/`, with no production
  change. `ReferenceServiceIntegrationTest` applies the history with `psql`
  to PostgreSQL 16, runs `pg_dump --schema-only` against the migrated
  database, generates one byte-identical, compiling output tree, manifest
  included, from the history, the snapshot, and the dump, executes the
  generated repository against the migrated database, and asserts the located
  `SET DATA TYPE` diagnostic and the unknown-table rejection of a query naming
  the second schema's table, each writing no file. The configuration and
  PostgreSQL documentation add the `pg_dump` snapshot as a schema input, its
  ignored statements, and its partition and `public` handling, and limit
  dump-history equality to histories that change the schema only through
  statements sqlcj reads, linking the indirect-change caveats. Reviewer runs:
  `mvn spotless:check` passed; `mvn -Dsqlcj.test.requireDocker=true clean test`
  passed with 32 + 1177 tests on Java 17 and Java 21, including the four
  `ReferenceServiceIntegrationTest` tests, none skipped.
- **M22-W9 — JSqlParser recheck and upstream report — Planned.** Implements
  requirement 9. The activation recheck above found no release after 5.4.
  Maven Central is rechecked; a release that reads any rejected form is adopted
  with only parser-wording expectations changed, and otherwise the result is
  recorded. A ready-to-file report, kept outside the repository, gives for each
  table or type form 5.4 cannot read the minimal SQL and 5.4's failure; filing
  it is the owner's action and not part of the item. Acceptance evidence is the
  recorded recheck, the report handed to the owner, and, after an upgrade, the
  full suite.

### Required

1. Apply D-A as the first work item: build every module with `--release 17`,
   replace the Java 21-only constructs, run the test suite in CI on Java 17 and
   Java 21, and update the documented requirements. Generated code and runtime
   behavior do not change.
2. Replace the enumerated list of ignored statements with one rule. `CREATE`,
   `ALTER`, and `DROP TABLE` and `CREATE` and `ALTER TYPE` update the model as
   they do today; every other statement is ignored, because it cannot change a
   modeled table's columns, column types, or nullability. That includes views
   and materialized views, `CREATE SCHEMA`, `SET` and other session statements,
   `TRUNCATE` and every data statement including `SELECT`, `DO` blocks,
   functions, triggers, policies, roles and privileges, `COMMENT ON` of any
   target, extensions, sequences, indexes, and `DROP TYPE`. Document that DDL
   executed inside a `DO` block or a function body, and columns removed by
   `DROP TYPE ... CASCADE`, are not seen.
3. Within `ALTER TABLE`, ignore every action that cannot change a column's
   existence, name, type, or nullability, such as `SET`/`DROP DEFAULT`,
   `SET STATISTICS`, `OWNER TO`, row-level security switches, trigger switches,
   `VALIDATE CONSTRAINT`, and adding or dropping any constraint. Accept a table
   constraint of any kind, including `EXCLUDE`, in `CREATE TABLE` as well.
   Reject an action that would change columns in a way sqlcj does not model,
   naming the file, line, and action in SQL terms.
4. Model `ALTER TYPE ... RENAME TO` and `ALTER TYPE ... RENAME VALUE` for
   enums, so the common replace-an-enum migration loads.
5. Never model a table with missing columns. `CREATE TABLE ... (LIKE t ...)`
   and `CREATE TABLE ... PARTITION OF t` copy the columns, types, and
   nullability of the modeled table `t`. Any other `CREATE TABLE` whose columns
   sqlcj cannot determine, such as `AS SELECT`, `INHERITS`, or `OF type`, is
   rejected with its location.
6. Model one namespace: unqualified names and names qualified with `public`.
   Tables and types created in any other schema are ignored, and a query that
   names a table qualified with another schema is rejected as an unknown
   table, so `reporting.users` never resolves to `users`.
7. Statement boundaries per D-B: a statement the parser cannot read is ignored
   when it is not a `CREATE`, `ALTER`, or `DROP TABLE` or a `CREATE` or
   `ALTER TYPE`, and rejected naming its file and line when it is, except an
   unreadable `ALTER TABLE` that D-F ignores. psql meta-command lines, such as
   the `\restrict` lines that recent `pg_dump` releases write, are skipped.
8. Schema diagnostics name statements in SQL terms, never by parser class name
   (`Unsupported schema statement: Alter` becomes the statement or action
   kind).
9. Recheck JSqlParser for a release that reads the forms that remain rejected
   under requirement 7 (today `ALTER COLUMN ... SET DATA TYPE`, unnamed
   `ADD CHECK`, and `NOT VALID`), upgrade if one exists, and prepare the
   upstream reports of the rest, which the owner files.

### Out of scope

- namespaces as a feature, views as query sources, table inheritance,
- domains, composite types, and other new type mappings,
- executing migrations, Liquibase XML/YAML/JSON, Flyway placeholders and Java
  migrations,
- reading DDL inside `DO` blocks or function bodies.

### Acceptance

- The reference service's migration history, including every schema construct
  of the probe's first three rows except `ALTER COLUMN ... SET DATA TYPE`,
  which requirement 7 keeps rejected and the unsupported catalogue records,
  loads and generates the same repositories as an equivalent hand-written
  snapshot.
- A `pg_dump --schema-only` snapshot of that schema, produced by the PostgreSQL
  used in the integration tests, loads and generates the same repositories.
- `mvn test` passes on Java 17 and on Java 21, and the runtime and CLI class
  files target Java 17.
- A `LIKE` or `PARTITION OF` table has its source table's columns; a table in
  another schema is invisible to queries; no probe input produces an internal
  error or a parser class name.
- Focused schema, compiler, and PostgreSQL tests, `mvn spotless:check`, and
  `mvn test` pass.

## Milestone 23 — A Way Through for Every Query

### Goal

Make every query of a supported statement kind either compile, compile once
the user states the missing types with casts, or fail with a diagnostic that
says what to write; and never report an internal error.

### Required

1. A placeholder written as the direct operand of `::type` or
   `CAST(... AS type)` is typed by its cast in every clause of the statement:
   projections, join conditions, `ORDER BY`, `LIMIT`/`OFFSET`, and nested
   `SELECT`s, as well as the clauses where it is accepted today. This makes
   dynamic sorting, as in
   `ORDER BY CASE WHEN :sort::text = 'title' THEN title END`, and placeholders
   inside `EXISTS` and `IN (SELECT ...)` subqueries compile.
2. A join's `ON` condition may be any boolean expression. Its placeholders
   follow the `WHERE` rules, so composite-key joins and filtered joins such as
   `LEFT JOIN users u ON u.id = t.assignee_id AND u.deleted_at IS NULL`
   compile. Nullability still follows the join kind.
3. Accept the conflict clauses that bind nothing sqlcj must type:
   `ON CONFLICT DO NOTHING` without a target, `ON CONFLICT ON CONSTRAINT name`,
   conflict-target predicates, and `DO UPDATE ... WHERE`, whose placeholders
   follow the `WHERE` rules.
4. A `RETURNING` item may carry an alias that names its result component, as a
   `SELECT` item does.
5. A placeholder sqlcj cannot type is reported with its name and the fix, for
   example `Cannot infer the type of :email in lower(:email); state it with a
   cast, such as :email::text`, instead of listing analyzed parameter sets.
6. No internal error reaches the user: set operations, `INSERT ... SELECT`
   (until Milestone 24), and every other probe input produce a located
   diagnostic in SQL terms. An unexpected failure is reported as a sqlcj
   internal error naming the query or schema source, never as a raw exception
   message.

### Out of scope

- type inference beyond today's rules (Milestones 24 and 25),
- subqueries, CTEs, and multi-row writes as analyzed shapes (Milestone 24),
- set operations, `LATERAL`, and other join kinds.

### Acceptance

- Every "no way through" query of the probe that is not Milestone 24's either
  compiles as written or with casts, against PostgreSQL through generated code,
  and the reference service adds those queries.
- Every probe input that stays unsupported fails with a located diagnostic that
  says what to write; none shows an exception class or analyzer internals.
- Focused analyzer and generator tests, generated-source compilation,
  `mvn spotless:check`, and `mvn test` pass.

## Milestone 24 — Nested Queries and Multi-Row Writes

### Goal

Analyze the subquery, CTE, and multi-row write shapes of an ordinary service
with the same rules as a top-level query, so that their placeholders and
results are typed from columns without casts.

### Required

1. A subquery in `WHERE` or a join condition (`EXISTS`, `NOT EXISTS`,
   `IN (SELECT ...)`, or a scalar comparison) is analyzed in its own source
   scope, with the outer sources visible for correlation, and its placeholders
   are typed by the predicate rules.
2. A non-recursive CTE, and a subquery in `FROM`, whose `SELECT` is itself in
   the supported subset, is a source whose columns take that `SELECT`'s
   analyzed names, types, and nullability. This is one mechanism; it is also
   the future basis for views as sources.
3. An `INSERT` may write several `VALUES` rows, each value typed by its target
   column, or a `SELECT` whose items are typed against the target columns by
   position, so `INSERT ... SELECT :projectId, unnest(:userIds::bigint[])`
   compiles.
4. `UPDATE ... FROM` and `DELETE ... USING` add their sources to the scope of
   `SET`, `WHERE`, and `RETURNING`; `RETURNING` still returns target-table
   columns or the target's full row.
5. Parameter order stays first textual occurrence across all scopes, and every
   occurrence is bound exactly once.

### Out of scope

- recursive and data-modifying CTEs, set operations, `LATERAL`,
- views as sources,
- JDBC batch execution and `COPY`.

### Acceptance

- The reference service's membership checks, CTE reports, bulk inserts, and
  joined updates compile without casts where a column gives the type, and run
  against PostgreSQL.
- Correlated subqueries resolve outer columns; an unknown or ambiguous column in
  any scope is rejected naming the scope's sources.
- Focused analyzer, parameter-order, generator, and PostgreSQL tests,
  `mvn spotless:check`, and `mvn test` pass.

## Milestone 25 — Result Shapes Settled

### Goal

Settle the generated result API for joins and aggregates before `0.1.0` makes
it a contract.

### Required

1. In a result that is not exactly one table's full row, each source's
   complete column set selected by a wildcard (`<source>.*`, or `*` over
   several sources, which is every source's `.*` in order) becomes one
   component of that table's shared `<TableName>Row` record, named after the
   source's exposed name (its alias, or its table name when unaliased), instead
   of that table's flattened columns. Other projection items stay flat
   components in their selected order, so
   `SELECT u.*, COUNT(t.id) AS tasks ... GROUP BY u.id` returns `UsersRow u`
   and `Long tasks`. This changes the type of already-accepted shapes, which is
   why it lands before publication.
2. An embedded row of a left-joined source is `null` when the joined row is
   absent, detected as every column of the embedded row reading `NULL`; the
   documentation states that this is exact for any table with a `NOT NULL`
   column.
3. Type these aggregates of a direct column without a cast, alias required:
   `COUNT(expr)` and `COUNT(DISTINCT column)` as a non-null `Long`; `MIN` and
   `MAX` as the column's type; and `SUM` and `AVG` as PostgreSQL's result type
   for the column's type. All but `COUNT` are nullable.

### Out of scope

- nesting one-to-many results into lists, user-supplied result classes,
- inference for other functions and expressions, which keep the cast,
- configurable component names.

### Acceptance

- `SELECT t.*, p.* FROM tasks t JOIN projects p ...` returns a result with
  `TasksRow t` and `ProjectsRow p`; a left-joined row is `null` for an
  unmatched row against PostgreSQL.
- The reference service's list-with-count and latest-activity queries compile
  without casts.
- Focused analyzer, generator, and PostgreSQL tests, generated-source
  compilation, `mvn spotless:check`, and `mvn test` pass.

## Milestone 26 — Maven Build and Spring Transactions

### Goal

Run generation as part of an ordinary Maven build, and make generated
repositories correct inside the most common Java application framework without
depending on it.

### Required

1. Add a `sqlcj-maven-plugin` with a `generate` goal bound by default to
   `generate-sources`. It reads the same `sqlcj.yaml` (default
   `${project.basedir}/sqlcj.yaml`, overridable), writes the same output
   through the same compiler, registers `java.out` as a compile source root,
   and fails the build with the same one-line diagnostics. It is released at
   the runtime's version, runs on Java 17, and publishes no public compiler
   library API.
2. The CLI stays supported and documented for non-Maven builds, including
   Gradle through an exec task.
3. Document and verify with tests that a repository built over
   `new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource))`
   takes part in a transaction of `DataSourceTransactionManager` and of
   `JpaTransactionManager` on the same `DataSource`, and that a plain
   `DataSource` does not. The runtime and generated code gain no Spring
   dependency; Spring and the JPA provider are test-scoped only.
4. The external sample and quickstart generate through the plugin with no
   manual generation step.

### Out of scope

- a Gradle plugin, a check/verify goal, incremental or watch generation, or IDE
  integration,
- a Spring Boot starter, auto-configuration, generated annotations, or a
  Spring-specific executor,
- plugin configuration that duplicates `sqlcj.yaml` settings.

### Acceptance

- `mvn package` on the external sample, from a clean checkout and with no
  prior generation, generates, compiles, and runs it against PostgreSQL.
- A configuration or query error fails the Maven build with the CLI's
  diagnostic and no stack trace.
- The Spring transaction tests commit and roll back generated writes as
  documented, beside a JPA write in the same transaction.
- Plugin tests, `scripts/verify-release.sh`, `mvn spotless:check`, and
  `mvn test` pass.

## Milestone 27 — `0.1.0` Release Candidate

### Goal

Prove the whole workflow on the reference service as a real consumer would
use it, and freeze the public surfaces.

### Required

1. Make the reference service a runnable example project: Flyway migrations,
   generation through the Maven plugin, and application flows in plain Java and
   in a Spring-managed transaction, covering every query of its catalogue,
   against the oldest and the newest PostgreSQL major versions in community
   support.
2. Fix only release-blocking findings; record the rest for post-release
   selection.
3. Freeze and document the `0.1.0` public surfaces: configuration, query
   annotations and placeholder forms, the generated API, the runtime API, CLI
   and plugin behavior, artifacts, and Java and PostgreSQL requirements.
4. Write the adoption guide: moving query groups from `JdbcTemplate`/
   `JdbcClient`, MyBatis mappers, and JPA native queries; coexisting with JPA in
   one transaction; using migrations or a `pg_dump` snapshot; adding logging or
   metrics by decorating `QueryExecutor`; and what sqlcj deliberately does not
   do.

### Acceptance

- The reference service builds from a clean checkout with `mvn package` and
  runs every catalogue query against both PostgreSQL versions, in plain Java and
  in a Spring-managed transaction.
- The [release gates](#release-gates) other than publication pass for the
  release-candidate build.

## Milestone 28 — `0.1.0` Publication

### Goal

Publish the first immutable artifacts after the release candidate passed.

### Entry conditions

- Milestone 27 is completed and reviewed.
- The `dev.sqlcj` namespace is verified on Maven Central (D-D).

### Required

1. Add the Apache License 2.0 (D-C), release notes, project/developer/SCM metadata,
   source and Javadoc JARs, signatures, and Central publication configuration.
2. Produce the runtime, the dependency-inclusive CLI, and the Maven plugin at
   one version. Publish no compiler library API and no redundant distribution
   artifact.
3. Build and validate a non-snapshot Central bundle without automatically
   publishing it. Keep credentials and signing material outside the
   repository.
4. Run the external sample and the reference service from a clean temporary
   project against the staged release artifacts and PostgreSQL, then rerun all
   gates and publish `0.1.0` with its checksums and release notes.

Maven Central requires main, source, and Javadoc artifacts, signatures,
checksums, and project metadata. The official publishing plugin creates a bundle
but does not supply every prerequisite. See the
[Central requirements](https://central.sonatype.org/publish/requirements/) and
[Central Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/).

`0.1.0` is an initial-development release, not a declaration that every future
change will be compatible. Nevertheless, its public API must be explicit and
useful; major version zero is not a reason to publish a knowingly poor API. See
[Semantic Versioning 2.0.0](https://semver.org/).

### Release gates

#### Product gate

- The reference service loads its Flyway migrations and its `pg_dump`
  snapshot, and every catalogue query compiles and runs without editing
  generated code or the schema.
- Repositories share table row records and enums, and joined table rows are
  embedded row records.
- Every construct of the unsupported catalogue fails with a located diagnostic
  that says what to write instead.

#### Correctness gate

- Accepted SQL has complete type, cardinality, result-order, logical-parameter,
  and textual-binding evidence.
- No schema input is modeled with missing columns or in the wrong namespace.
- Unsupported nearby SQL fails before output replacement, and no input
  produces an internal error.
- Generated source compiles independently against only the runtime and JDK.
- PostgreSQL integration is required in release CI and cannot silently skip.

#### Distribution gate

- A clean consumer resolves staged release artifacts without a source checkout,
  on Java 17 and on Java 21.
- The CLI and plugin report or use the release version, honor default and
  explicit config paths, and have documented failure behavior.
- Runtime consumers receive no parser, YAML, CLI, plugin, or compiler
  dependency.
- The validated release bundle contains only intended artifacts and no secrets.

#### Verification gate

- `mvn spotless:check` passes.
- `mvn test` passes with the PostgreSQL tests required to run.
- Packaged CLI smoke tests, plugin tests, and `scripts/verify-release.sh` pass
  for the exact release version.
- The final documentation and artifact contents are reviewed against the
  release candidate, not against an earlier snapshot.

## After `0.1.0`

Post-release work is selected from observed usage: issues, the reference
service, and queries users report as rejected. Adoption blockers that users
report always come first. The tracks below are ordered by expected value; the
order is not permission to implement all of them. Each chosen item becomes its
own narrow, typed, end-to-end milestone. "Support PostgreSQL" and "support
expressions" are not acceptable work items.

1. **Gradle.** A Gradle plugin with the same contract as the Maven plugin.
   Gradle is the default build of Spring Initializr and of Kotlin projects,
   and it is the largest audience the CLI-only path serves poorly.
2. **Nullness annotations.** JSpecify annotations on generated parameters and
   components, so Kotlin and null checkers see what sqlcj already knows. This
   is additive, but it adds a dependency to generated code, which must be
   decided deliberately.
3. **SQL breadth by evidence.** Set operations, views as sources (reusing
   Milestone 24's derived sources), non-default schemas, `LATERAL` and other
   join kinds, window functions without a cast, recursive CTEs, `MERGE`,
   domains mapped to their base type, and `citext` as `String`.
4. **Throughput.** JDBC batch execution for repeated writes and streaming for
   large reads, once a user shows a workload that `unnest` bulk writes and
   paging do not serve.
5. **Controlled customization.** Type overrides for concrete interoperation
   needs (for example `JSONB` to a JSON library type, or typed ids),
   user-supplied result types, and query comments carried into method Javadoc.
   Prefer a small explicit mapping over templates, `emit_*` switches, or a
   generator plugin protocol.
6. **Build checks.** A check goal that fails when committed CLI output is stale
   (sqlc's `diff`), for teams that commit generated sources.
7. **Framework convenience** only if users ask, such as Spring Boot
   configuration. It must not make plain Java second-class.

## Long-Term Direction and `1.0`

`1.0` is a promise, not a feature level. It means that real services outside
this project run sqlcj in production, their reported blockers are closed, and
sqlcj commits to:

- semantic-versioning stability of the configuration format, the named-query
  syntax, the generated API, and the runtime API, with a documented
  deprecation policy,
- Maven and Gradle builds, every Java LTS from the baseline up, and every
  PostgreSQL major version in community support, verified in CI,
- generated code that states nullness and supports the type overrides users
  showed they need,
- batch and streaming execution and a documented observability approach.

Two strategic options are evaluated only when evidence demands them:

- **Database-backed analysis.** The static analyzer will never type all of
  PostgreSQL. If cast-required or rejected SQL becomes the dominant complaint,
  evaluate an optional analyzer that asks PostgreSQL to describe each
  statement, as sqlc's database analyzer, sqlx, Manifold SQL, and jOOQ's
  Testcontainers setup do, with its results committed so that ordinary builds
  stay database-free. It must stay optional.
- **Another database or output language.** Each multiplies parsing, type
  mapping, runtime, documentation, and testing contracts. Treat it as a product
  expansion, not a generic-core refactor performed in advance.

Permanent non-goals: ORM features, runtime-built dynamic SQL, a query DSL,
runtime reflection, and a mandatory framework.

## Roadmap Update Workflow

- Only one product milestone is Active at a time.
- `Required` defines the milestone; `Out of scope` is an explicit exclusion and
  is not a review finding.
- Activate a milestone only after rechecking its assumptions against the
  implementation, tests, examples, and current user evidence, and record that
  reassessment under the milestone's `Work items`.
- Decompose an active milestone into small work items (`M<milestone>-W<n>`)
  with observable behavior, boundaries, acceptance evidence, and scoped review.
- Use `Planned`, `In progress`, `Completed`, `Deferred`, and `Blocked` honestly.
- Do not activate the next milestone automatically. Reassess and simplify the
  plan after each completed milestone.
- A feature discovered during implementation belongs in a later milestone
  unless it is required to make the active behavior correct.
- Until `0.1.0` is published, no earlier decision, baseline, or generated-API
  shape is binding. When evidence shows a better choice, change it, and record
  the change and its reason under [Plan Changes](#plan-changes); never keep a
  worse design only because it was decided earlier.
- When a milestone is completed, move its section into the
  [Completed Milestone Record](#completed-milestone-record) unchanged.

## Definition of Done for Any Milestone

A milestone is complete only when its required behavior is implemented,
documented where public, covered by focused tests, and reviewed against its
stated boundary. Generated Java must compile when relevant; runtime behavior
must run against PostgreSQL when relevant; `mvn spotless:check` and `mvn test`
must actually pass; and the final diff must contain no machinery justified only
by an out-of-scope feature.

## Plan Changes

- **2026-10-05.** After Milestone 21, a first-contact probe of an original
  task-tracking service found dead ends and silent mis-models (see
  [Where sqlcj Stands](#where-sqlcj-stands)). The previously planned Milestone
  22 (Maven build and framework use) became Milestone 26, with JPA coexistence
  added to its Spring requirement; the previously planned Milestone 23 (release
  candidate and publication) was split into Milestones 27 and 28, with the
  reference service replacing the earlier unspecified dogfood service and its
  JSqlParser recheck moved to Milestone 22. Milestones 22 to 25 are new.
  `pg_dump` snapshots moved from a non-goal into Milestone 22, and the
  enumerated ignored-statement list of Milestone 19's requirement 4 gives way
  to Milestone 22's single rule.
- **2026-10-05.** The owner decided D-A to D-E as recommended. The release
  targets Java 17 instead of Java 21, applied first in Milestone 22; D-B
  supersedes the part of Milestone 19's D2 that forbids splitting schema SQL;
  and the owner set the rule that pre-release decisions are revisable by
  evidence.
- **2026-10-09.** Milestone 22 was activated and decomposed into M22-W1 to
  M22-W9. Its reassessment found that JSqlParser 5.4, still the latest release,
  cannot read the identity form `pg_dump` writes for every identity column, so
  requirement 7 as written made the `pg_dump` acceptance unreachable. D-F,
  decided the same day, ignores an unreadable `ALTER TABLE` whose every action
  only adds a constraint or an identity, and requirement 7 was amended to apply
  it. Requirement 9 now prepares the upstream reports, which the owner files,
  because filing publishes content. The first acceptance item now excludes
  `ALTER COLUMN ... SET DATA TYPE`, which requirements 7 and 9 keep rejected.
  The architect's decisions of the same date, including the implementation
  order, are recorded under Milestone 22's work items.
- **2026-10-09.** At the owner's request, M22-W2 moved ahead of M22-W3. A
  JSqlParser 5.4 probe showed that only a function or procedure body that is
  not dollar-quoted captures later statements in a whole-file parse, so W2
  keeps rejecting such a body until W3 parses each statement alone, and no
  statement is ignored silently in the meantime.
- **2026-10-10.** M22-W3 started. A JSqlParser 5.4 recheck found that a
  tagged dollar-quote delimiter is a token of its own only when whitespace
  separates it: `AS $_$SELECT` and `END$fn$` each lex as one identifier. A
  tagged body is therefore kept whole by finding its closing delimiter in the
  source text and resuming the token manager after it, instead of by pairing
  delimiter tokens. Positioning every statement at its file offset for parsing
  doubled the parse time of a 14,000-line file and grows with the square of
  its length, so only a rejected statement is reparsed that way.
- **2026-10-10.** M22-W4 started. A JSqlParser 5.4 recheck found that every
  action it parses without a form of its own, such as `OWNER TO`, a trigger
  switch, or `VALIDATE CONSTRAINT`, captures the rest of the statement, so
  `OWNER TO app, ADD COLUMN x int` is one action. Ignoring such an action by
  its first words would drop the `ADD COLUMN` silently, so the captured text
  is split into its actions and each one is classified.
- **2026-10-10.** At the owner's request, M22-W5 moved ahead of M22-W7. W7 is
  not implemented: no schema or analyzer path handles a `public` or other
  qualifier, and JSqlParser 5.4 reports an `ALTER TYPE` or `CREATE TYPE` name
  with its qualifier, so `public.mood` and `mood` are distinct names today.
  W5's type lookups therefore keep that literal name, as `ADD VALUE` already
  does, and W7 changes them with every other lookup. The one silent mis-model
  the order could cause, ignoring `ALTER TYPE public.mood RENAME TO ...` or
  `SET SCHEMA` of a modeled `mood` as an unmodeled type, is closed by an
  interim rule: such a qualified, unmodeled name fails as `Type not found`
  until W7 replaces the rule. `SET SCHEMA` of an unmodeled type is ignored,
  like `RENAME TO` of one.
- **2026-10-10.** At the owner's request, M22-W6 moved ahead of M22-W7, giving
  W1 to W6, W7, W9, then W8. Every table lookup still drops the schema
  qualifier, so a `LIKE` or `PARTITION OF` source qualified with another
  schema would silently copy a modeled table of the same unqualified name. An
  interim rule closes that: `public.t` resolves to `t`, and a table name W6
  resolves that is qualified with any other schema fails as `Table not found`
  until W7 replaces the rule. A JSqlParser 5.4 recheck also found that it
  reports a `CREATE TABLE` element list in written order, each `LIKE` with
  its own source, so the 2026-10-09 decision to reject a `LIKE` beside other
  column sources, made for a parser limitation that does not exist, gives way
  to modeling the columns in written order, as PostgreSQL does. Planning W6
  found that a partition copied once would silently miss a column its parent
  gains later, because PostgreSQL applies a partitioned table's column changes
  to its partitions; W6 therefore keeps a partition linked to its parent,
  which revises M22-W4's ignoring of `ATTACH` and `DETACH PARTITION` for a
  modeled partition.
- **2026-10-10.** M22-W7 started. A JSqlParser 5.4 recheck found that it
  reports a qualified type name, in `CREATE` and `ALTER TYPE`, a column type,
  or a cast, as one text with its qualifier and delimiters, and a qualified
  type's array brackets inside that text, so `public.mood[]` reaches the type
  mapping as no array at all; the namespace rule therefore also reads those
  brackets. Planning W7 found that removing a partitioned table moved by
  `SET SCHEMA` would leave its partitions in `public` following a parent sqlcj
  no longer models, so such a move is rejected, as M22-W5 rejects moving a
  modeled enum whose columns keep it; and that an unreadable `DROP TABLE` may
  name a modeled table beside one of another schema, so only an unreadable
  statement naming a single table or type is ignored by its qualifier.
- **2026-10-10.** At the owner's request, M22-W8 moved ahead of M22-W9, giving
  W1 to W7, W8, then W9. W9 was ordered first so that the acceptance would run
  on the parser version the milestone closes with, but a recheck of Maven
  Central the same day found JSqlParser 5.4 still the latest release, so the
  order cannot change W8's parser version. W9 still makes the closing recheck,
  and an upgrade it adopts runs the full suite, W8's reference-service test
  included.

## Completed Milestone Record

The sections below are the record of completed milestones 15 through 21,
kept as they were accepted. Milestones 1 through 14 predate this record; their
outcomes are summarized in [Milestone Status](#milestone-status). The
per-query API and nested row records described here are the state at the time
of each milestone; later milestones supersede them where stated. References
in this record to Milestones 22 and 23 mean the plan in force when the record
was written; that plan was replaced on 2026-10-05, as
[Plan Changes](#plan-changes) records.

### Milestone 15 — Repository-Shaped Generated API

#### Goal

Replace the fragmented per-query API with one generated concrete repository per
configured query group before any public release makes the old shape a contract.

#### Work items

- **M15-W1 — Repository-shaped generated API — Completed.** Implements
  requirements 1–7 as one coordinated vertical slice: each explicitly named SQL
  entry generates one concrete repository containing its typed named-query
  methods and one executor constructor, and the documented author sample uses
  that API. The boundary is configuration group identity, grouped analysis and
  generation, repository-scoped query/method collisions, removal of per-query
  output, and corresponding sample/documentation updates; all Milestone 15
  exclusions and Milestone 16 result/cardinality changes remain out of scope.
  Acceptance evidence is the milestone's focused configuration, compiler,
  generator, generated-compilation, PostgreSQL, formatting, and full-suite
  verification.
  Review evidence: each `sql` entry requires a Java-safe `name` and generates
  one final `<Name>Repository` with one `QueryExecutor` field, one constructor,
  and one method per query in declared order, with nested result records and
  row mappers; per-query output is removed. Query and method collisions,
  including result types that differ only by case, are rejected per repository
  before any file is written, while two repositories may reuse a query name;
  repository path collisions stay global. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (332 tests, 0 skipped,
  including 11 PostgreSQL tests through both executor construction paths), and
  `scripts/verify-release.sh` generated one `AuthorRepository` for the external
  sample and ran it against PostgreSQL 16.

#### Required

1. Add a required, Java-safe `name` to each `sql` configuration entry. An entry
   named `Author` generates `AuthorRepository`.
2. Analyze all queries in an entry before generation and pass an explicit query
   group model to Java generation. The generator must not rediscover grouping
   from file paths or tables.
3. Generate one final repository class with one `QueryExecutor` field, one
   constructor, and one public method per named query, preserving method
   parameter types and logical order.
4. Nest query-specific result records and row mappers in the repository until a
   result qualifies for the reusable row model in Milestone 16.
5. Scope query-name uniqueness and method-collision checks to a repository;
   repository class/path collisions remain global within one output package.
6. Remove the per-query class output. Do not carry both APIs or add a
   compatibility mode; there is no public release to remain compatible with.
7. Update the sample and user documentation so application code creates one
   repository per execution context and never constructs a type per query.

#### Out of scope

- generated repository interfaces, implementation suffixes, annotations,
  scanning, proxies, service locators, or a repository factory,
- automatic grouping by table or filename,
- inferred CRUD methods not present as named SQL,
- new SQL constructs, types, or transaction orchestration.

#### Acceptance

- The five-query author sample generates one `AuthorRepository` with five
  methods and one executor constructor.
- Two repositories may contain the same query name without collision, while
  duplicate method names inside one repository fail before output is written.
- The same generated repository compiles and executes through both existing
  executor construction paths.
- Generated source remains readable and contains no compiler or parser
  dependency.
- Focused configuration, compiler, generator, generated-compilation, and
  PostgreSQL tests pass, followed by `mvn spotless:check` and `mvn test`.

### Milestone 16 — Result and Cardinality Contract

#### Goal

Make method return types predictable and reusable, and remove `null` as the
top-level signal that a query returned no row.

#### Work items

Reassessed on activation against the implementation, tests, sample, and
documentation: requirements 1–6 and 9 are unimplemented as assumed, `SELECT`
aliases are still accepted and ignored, `RETURNING` aliases are rejected, and
requirement 7 already holds and must be preserved.

- **M16-W1 — Projection aliases and conventional Java names — Completed.**
  Implements requirements 3 and 4. An explicit `SELECT` projection alias names
  its result component instead of being ignored, and repository, result, and
  method names, record components, and method parameters follow one documented
  deterministic camel-case rule set covering word splitting, acronyms, quoted
  names, keywords, and collisions. The boundary is alias capture in analysis,
  Java naming in generation, and the naming documentation; `RETURNING` aliases
  stay rejected, and row types, cardinality, and runtime changes remain out of
  scope. Acceptance evidence is focused naming and analyzer tests, compiled
  generated source for aliased partial and join projections, updated
  integration expectations, `mvn spotless:check`, and `mvn test`.
  Review evidence: an explicit alias on a direct `SELECT` column names its
  result column while type and nullability stay the column's; `RETURNING`
  aliases stay rejected. Repository and result types are upper camel case and
  methods, record components, and parameters lower camel case, with documented
  word-splitting, acronym, quoted-name, digit-initial, keyword, and no-word
  rules; same-method and case-only result-type rejection and numeric
  disambiguation are unchanged, and row mapping stays positional. The aliased
  join `u.id AS user_id, p.id AS profile_id, p.nickname` generates and executes
  `userId`, `profileId`, `nickname`. `mvn spotless:check` and
  `mvn test -Dsqlcj.test.requireDocker=true` passed (363 CLI and 18 runtime
  tests, 0 skipped, including 11 PostgreSQL tests).
- **M16-W2 — Reusable table-row records — Completed.** Implements requirements 1
  and 2. A single-source `SELECT *` or `<source>.*` and a `RETURNING *` return
  the repository's nested `<TableName>Row`, generated once per table used, while
  every other shape keeps its `<QueryName>Result`, including an explicit list of
  every column and a wildcard combined with another item. The boundary is analyzed
  full-row identity, row records and mappers with their name collisions, and
  the sample and documentation changes that make create, get, and list share
  `AuthorRepository.AuthorsRow`; cardinality remains out of scope. Acceptance
  evidence is focused analyzer and generator tests, generated-source
  compilation, PostgreSQL execution, the external sample, `mvn spotless:check`,
  and `mvn test`.
  Review evidence: a single-source `SELECT *` or `<source>.*` (any alias or
  table spelling) and a `RETURNING *` on `INSERT`, `UPDATE`, or `DELETE` return
  one nested `<TableName>Row` per table, generated once after the constructor
  with one positional mapper in schema column order; explicit column lists
  (including every column), a wildcard plus another item, joined wildcards,
  and `RETURNING` column lists keep `<QueryName>Result`. A query named
  `Authors` keeps `authorsRowMapper` while the row mapper becomes
  `authorsRowMapper1`, and `user_data`/`userdata` row types are rejected naming
  both tables and types with no output written. Binding order is unchanged.
  `mvn spotless:check` and `mvn test -Dsqlcj.test.requireDocker=true` passed
  (383 CLI and 18 runtime tests, 0 skipped, including 12 PostgreSQL tests), and
  `scripts/verify-release.sh` ran the external sample, whose `createAuthor`,
  `getAuthor`, and `listAuthors` return `AuthorRepository.AuthorsRow`, against
  PostgreSQL 16.
- **M16-W3 — Exact, optional, and many cardinality — Completed.** Implements
  requirements 5–9. `:one` returns exactly one row or fails with a focused
  runtime exception, the new `:optional` returns `Optional<T>`, `:many` and
  `:exec` keep their contracts, runtime execution and cardinality failures name
  the repository and query, and the documentation separates row absence from
  nullable components. The boundary is annotation parsing, analyzer annotation
  checks, the runtime executor contract, generated calls, the sample, and
  documentation. Acceptance evidence is runtime unit tests, generated-source
  compilation, PostgreSQL zero/one/multiple-row execution through both executor
  paths, the external sample, `mvn spotless:check`, and `mvn test`.
  `:optional` is accepted wherever `:one` is, and the external sample gains an
  optional lookup replacing its null missing-row checks.
  Review evidence: `:one` returns its row directly and raises
  `QueryCardinalityException`, a `QueryExecutionException` subclass, for no row
  and for more than one; `:optional` is accepted for a `SELECT` and a returning
  write, returns `Optional<T>`, `Optional.empty()` for no row, and fails for more
  than one; `:many` keeps `List<T>` with an empty list and `:exec` its `int`
  count. Every generated executor call passes the repository class name and
  query name, so failures read `Query 'GetAuthor' in AuthorRepository returned
  no row; expected exactly one` and `Failed to execute query 'GetAuthor' in
  AuthorRepository`; statements, result sets, and acquired connections close on
  a cardinality failure, and binding order is unchanged. `java.util.Optional`
  is imported only for an `:optional` query, and the documentation states that
  a component is `null` only for a nullable column while row absence is
  expressed only by the annotation. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (391 CLI and 25 runtime
  tests, 0 skipped, including 13 PostgreSQL tests exercising zero, one, and two
  rows through both executor paths), and `scripts/verify-release.sh` ran the
  external sample, whose `findAuthor` returns an empty `Optional` and whose
  `getAuthor` raises the cardinality exception for a missing row, against
  PostgreSQL 16.

#### Required

1. Generate one nested `<TableName>Row` record per repository for every complete
   table-row shape the repository uses. Use deterministic Java normalization,
   not English singularization (`authors` becomes `AuthorsRow`).
2. Reuse that row type for a direct full-table projection or `RETURNING *` in
   schema column order. Keep a `<QueryName>Result` record for partial
   projections, joins, and other query-specific shapes.
3. Honor an explicit projection alias as the query-specific result component
   name. Do not continue accepting an alias and silently ignoring it.
4. Generate conventional Java names: SQL `snake_case` columns and aliases
   become lower-camel-case record components and parameters, while repository,
   row, and result types use upper camel case. Define acronym, quoted-name,
   keyword, and collision behavior deterministically; do not add a configurable
   naming strategy for `0.1.0`.
5. Redefine `:one` as exactly one row. It returns the result directly and fails
   with a focused runtime exception when zero or more than one row is returned.
6. Add `:optional` for zero-or-one queries. It returns `Optional<T>`, returns
   `Optional.empty()` for no row, and fails when more than one row is returned.
7. Keep `:many` as `List<T>` and `:exec` as the affected-row count. Use empty
   collections rather than `null`.
8. Preserve the distinction between row absence and a nullable column. Keep the
   current Java scalar mappings for `0.1.0` and document which record components
   may be `null`; do not add a nullness-annotation dependency solely for code
   generation.
9. Include the repository and query name in runtime execution/cardinality
   errors without exposing compiler implementation types to the runtime.

#### Out of scope

- domain entities, identity maps, record mutation, relationship mapping, or
  converting rows into application domain objects,
- configurable model names, user-supplied result classes, reflection mapping,
  or a general structural type-deduplication algorithm,
- optional wrappers for every nullable column or a new nullness type system.

#### Acceptance

- Create, get, and list queries that return `authors.*` share
  `AuthorRepository.AuthorsRow`.
- A partial projection and a join have stable query-specific records whose
  components honor aliases, Java naming conventions, and selected-column order.
- Exact-one, optional, and many methods exercise zero, one, and multiple rows
  against PostgreSQL and enforce their documented contracts.
- Existing parameter and JDBC binding order is unchanged.
- Focused runtime/generator tests, generated-source compilation,
  PostgreSQL integration, `mvn spotless:check`, and `mvn test` pass.

### Milestone 17 — Everyday PostgreSQL Read Slice

#### Goal

Add the smallest high-value read features needed by an ordinary CRUD service,
without attempting a general SQL expression type system.

#### Work items

Reassessed on activation against the implementation, tests, sample, and
documentation: requirements 1–5 are unimplemented as assumed. `LIKE $N`,
`ILIKE $N`, `BETWEEN $N AND $M`, and `LIMIT`/`OFFSET $N` fail only through the
generic unanalyzed-placeholder rejection, `COUNT(*)` fails as an unsupported
`Function` projection, and `LEFT JOIN` fails as a non-inner join. Forms that bind
no placeholder — `IS NULL`/`IS NOT NULL`, a literal `LIKE` pattern or `BETWEEN`
range, and literal `LIMIT`/`OFFSET` — are accepted unanalyzed, so an unknown
column in a null test, literal pattern, or literal range passes analysis. The external sample already has the optional
lookup (`FindAuthor`) but no search, count, page, or join, and the quickstart
uses `CountAuthors` as its invalid-query example.

- **M17-W1 — Text and null predicates — Completed.** Implements requirement 1.
  A predicate `<column> LIKE $N` or `<column> ILIKE $N` on a direct `VARCHAR` or
  `TEXT` column binds one `String` parameter typed from that column, and
  `<column> IS NULL` / `IS NOT NULL` on a direct column resolves that column
  against the query sources, so an unknown or ambiguous column is rejected; both
  apply wherever the read predicate forms are accepted. The boundary is
  predicate analysis and the query documentation: a `LIKE`-family placeholder
  that is negated, uses another keyword such as `SIMILAR TO`, has an `ESCAPE`
  clause, targets a non-text column, or stands on the left is rejected with a
  focused diagnostic; a placeholder inside a pattern expression and `$N IS NULL`
  stay rejected as unanalyzed placeholders; literal patterns stay unanalyzed.
  The sample is M17-W6. Acceptance evidence is focused analyzer tests,
  generated-source compilation and PostgreSQL execution of `LIKE`, `ILIKE`,
  `IS NULL`, and `IS NOT NULL` reads, `mvn spotless:check`, and `mvn test`.
  Review evidence: `QueryAnalyzer.resolveParameters` gained a `LikeExpression`
  and an `IsNullExpression` branch, so the shared `WHERE` analysis of `SELECT`,
  `UPDATE`, and `DELETE` types a pattern placeholder from the tested `VARCHAR`
  or `TEXT` column and resolves a null test's column through the existing
  column resolution without adding a parameter; `name ILIKE $2 AND id > $1`
  yields `[id BIGINT, name VARCHAR]` with binding order `[2, 1]`, `bio LIKE $1`
  yields a `TEXT` parameter, and `missing IS NULL` is rejected with `Column not
  found in sources users: missing`. `NOT LIKE`, `NOT ILIKE`, `SIMILAR TO`,
  `ESCAPE`, `BINARY`, a non-text tested column, a placeholder as the tested
  value, and `name LIKE :pattern` each fail before generation with their own
  message, while `'%' || $1 || '%'` and `$1 IS NULL` stay rejected by the
  placeholder accounting check and a literal `LIKE` pattern stays unanalyzed.
  `QueryModel`, the generator, and the runtime are unchanged.
  `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test` passed
  (407 tests, 0 skipped, including 14 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedTextAndNullPredicatesAgainstPostgres`
  compiles and runs `LIKE`, case-insensitive `ILIKE`, `IS NULL`, and
  `IS NOT NULL` reads against PostgreSQL 16), and `docs/queries.md` documents
  the new predicate forms, the text-column requirement, and the caller-supplied
  wildcards.
- **M17-W2 — Range predicates — Completed.** Implements requirement 2.
  `<column> BETWEEN $a AND $b` and `NOT BETWEEN` on a direct column bind each
  placeholder bound with that column's type in textual order, named by the
  existing column-derived naming and numeric disambiguation rules. A
  placeholder as the tested operand or inside a bound expression stays
  rejected. Acceptance evidence is analyzer tests including out-of-order
  indexes, PostgreSQL execution, `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: both forms currently fail only through the
  generic placeholder-accounting rejection, while a named bound such as
  `id BETWEEN :lo AND :hi` is accepted and reaches the executable SQL unbound.
  The boundary is predicate analysis and the query documentation, applied
  wherever the read predicate forms are accepted: a placeholder bound beside a
  literal bound is typed the same way, a named bound is rejected with the
  existing named-parameter diagnostic, a range that binds no placeholder stays
  unanalyzed like a literal `LIKE` pattern, and `BETWEEN SYMMETRIC` already
  fails to parse. The sample is M17-W6.
  Review evidence: `QueryAnalyzer.resolveParameters` gained a `Between` branch,
  so the shared `WHERE` analysis of `SELECT`, `UPDATE`, and `DELETE` types each
  `$N` bound from the resolved tested column and records the start bound before
  the end bound; `id BETWEEN $1 AND $2` and `id NOT BETWEEN $1 AND $2` yield
  `[id BIGINT, id BIGINT]` with binding `[1, 2]` and executable
  `BETWEEN ? AND ?`, `name = $1 AND id NOT BETWEEN $3 AND $2` binds `[1, 3, 2]`,
  `id BETWEEN $1 AND 10` yields one parameter, a literal-only range yields none,
  `missing BETWEEN $1 AND $2` fails column resolution, and
  `id BETWEEN :lo AND :hi` fails with the named-parameter diagnostic, while
  `$1 BETWEEN id AND id` and `id BETWEEN $1 + 1 AND $2` stay rejected by the
  placeholder accounting check. A qualified tested column and the `UPDATE` and
  `DELETE` paths were also probed and bind in textual order. `QueryModel`, the
  generator, and the runtime are unchanged. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (417 CLI and 25 runtime
  tests, 0 skipped, including 15 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedRangePredicatesAgainstPostgres`
  compiles and runs a `BETWEEN` read and an out-of-order `NOT BETWEEN` read
  against PostgreSQL 16), and `docs/queries.md` documents the range forms, bound
  typing and order, and the rejected nearby forms.
- **M17-W3 — Pagination — Completed.** Implements requirement 3. A read
  accepts a literal or `$N` `LIMIT` and `OFFSET`; each placeholder is an `Integer`
  parameter named `limit` or `offset` (sqlc types PostgreSQL limit/offset
  parameters as `integer`, plain `int` arguments autobox, and PostgreSQL widens
  the value to `bigint`), bound in textual order including `OFFSET ... LIMIT
  ...`. Values must be non-negative; PostgreSQL rejects a negative value at
  execution, and generated code adds no validation. `LIMIT ALL`, computed
  values, `LIMIT a, b`, and `FETCH` stay rejected when they carry a
  placeholder. Acceptance evidence is analyzer tests, PostgreSQL execution of
  a paged list, `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: `LIMIT $N` and `OFFSET $N` fail only through
  the generic placeholder-accounting rejection, while a named `LIMIT :n` or
  `OFFSET :n` is accepted and reaches the executable SQL unbound. The SQL
  parser stores `OFFSET a LIMIT b` exactly like `LIMIT b OFFSET a`, so textual
  order must come from the placeholders' source positions. The boundary is
  `SELECT` analysis and the query documentation: the `LIMIT` row count and the
  `OFFSET` value are analyzed independently after the `WHERE` parameters, so
  `LIMIT ALL OFFSET $N` binds its offset; a named value is rejected with the
  existing named-parameter diagnostic; a value that binds no placeholder stays
  unanalyzed; and a placeholder in a computed value, in `LIMIT a, b`, or in
  `FETCH` stays rejected by the accounting check. The sample is M17-W6.
  Review evidence: `QueryAnalyzer.resolveBindingParameters` now resolves the
  `WHERE` parameters when present and then the pagination parameters, so a
  `LIMIT` row count or `OFFSET` value that is a `$N` placeholder binds one
  `INTEGER` parameter named `limit` or `offset`, ordered by source position when
  both are placeholders; `name = $1 ... LIMIT $2 OFFSET $3` yields
  `[name VARCHAR, limit INTEGER, offset INTEGER]` with binding `[1, 2, 3]`,
  `OFFSET $2 LIMIT $1` yields `[limit INTEGER, offset INTEGER]` with binding
  `[2, 1]`, `LIMIT 10 OFFSET $1` and `LIMIT ALL OFFSET $1` each yield one
  `offset` parameter, and `LIMIT 10 OFFSET 5` yields none. `LIMIT :n` and
  `OFFSET :n` fail with the named-parameter diagnostic, while `LIMIT $1 + 1`,
  `OFFSET $1 + 1`, `LIMIT 5, $1`, `LIMIT $1, $2`, and `FETCH FIRST $1 ROWS ONLY`
  stay rejected by the placeholder accounting check. `QueryModel`, the
  generator, and the runtime are unchanged. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (430 CLI and 25 runtime
  tests, 0 skipped, including 16 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedPaginationAgainstPostgres`
  compiles both clause orders as `(Integer, Integer)` methods and returns the
  same page from each against PostgreSQL 16), and `docs/queries.md` documents
  the pagination forms, their `Integer` parameters, binding order, and the
  rejected nearby forms.
- **M17-W4 — Scalar count — Completed.** Implements requirement 4. A
  projection that is exactly `COUNT(*) AS <alias>` produces a
  `<QueryName>Result` with one non-null `Long` component named after the alias;
  `COUNT(*)` without an alias or beside another projection item,
  `COUNT(column)`, `COUNT(DISTINCT ...)`, `FILTER`/`OVER`, and every other
  function stay rejected. The quickstart's invalid-query example changes
  because `CountAuthors` becomes valid.
  Acceptance evidence is analyzer and generator tests, PostgreSQL execution,
  `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: every `COUNT(*)` spelling reaches `SELECT`
  projection analysis as a parser `Function` and fails with `Unsupported SELECT
  expression: Function`, while the `FILTER` and `OVER` forms parse as an
  `AnalyticExpression` and fail the same way under that name; no test covers a
  function projection. `COUNT(t.*)` parses its argument as a subtype of the
  `COUNT(*)` argument, so recognition must exclude it explicitly. The generator
  needs no change: a `BIGINT` result column already generates a `Long`
  component read by position, and an aliased projection is never a shared full
  row, so it generates `<QueryName>Result`; PostgreSQL's `count(*)` returns
  `bigint` and `0`, never null, for no rows. `CountAuthors` is not in the
  quickstart's `queries.sql` listing; it appears only in the sample diagnostic,
  which is the only quickstart change. The boundary is `SELECT` projection
  analysis and the query documentation: the count is an unqualified
  case-insensitive `COUNT` whose single argument is exactly `*`, with an alias
  written with or without `AS`, over any already-accepted source, predicate, and
  pagination shape and under any result annotation. A missing alias and a count
  beside another projection item each fail with a focused diagnostic; a
  qualified name such as `pg_catalog.count(*)`, `COUNT(t.*)`, and the other
  listed forms keep the existing unsupported-expression rejection. `RETURNING`
  is unchanged and `GROUP BY` stays unanalyzed. Because the generator is
  unchanged, the generator evidence is the pipeline's compiled record exposing a
  `Long` component. The sample is M17-W6.
  Review evidence: `QueryAnalyzer.resolveColumns` gained a `Function` branch
  that accepts only a one-part, case-insensitive, non-`DISTINCT` `count` whose
  single argument is exactly the `*` argument class, checks the item count
  before the alias, and resolves `QueryColumn(<alias>, BIGINT, false)`;
  `SELECT COUNT(*) AS total FROM users` yields that one column with no
  parameters and no row table, and `count(*) user_count ... WHERE name LIKE $1`
  keeps its one `VARCHAR` parameter. `COUNT(*)` without an alias fails with
  `COUNT(*) requires a result alias, such as COUNT(*) AS total.`, a count before
  or after `id` fails with `COUNT(*) must be the only SELECT item.`, and
  `COUNT(id)`, `COUNT(DISTINCT id)`, `COUNT(u.*)`, `pg_catalog.count(*)`, and
  `lower(name)` keep `Unsupported SELECT expression: Function` while the
  `FILTER` and `OVER` forms keep it as `AnalyticExpression`. `QueryModel`, the
  generator, and the runtime are unchanged. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (443 tests, 0 skipped,
  including 150 analyzer and 17 PostgreSQL tests; the new
  `PostgresIntegrationTest.shouldExecuteGeneratedScalarCountAgainstPostgres`
  compiles a `:one` count whose record exposes one `Long` component `total` and
  returns `2L` and `0L` against PostgreSQL 16), and `docs/queries.md` and the
  quickstart diagnostic document the accepted count and its rejected forms.
- **M17-W5 — Narrow left joins — Completed.** Implements requirement 5. `LEFT
  JOIN` (including its `LEFT OUTER JOIN` spelling) is accepted with exactly the
  inner-join `ON` shape, and every column of a left-joined source is analyzed
  as nullable; every other join kind stays rejected. Acceptance evidence is
  analyzer tests, PostgreSQL execution returning `null` components for an
  unmatched row, `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: both spellings parse as a join reporting
  `isLeft()` (`LEFT OUTER JOIN` also `isOuter()`) and fail the supported-join
  gate with `Only unmodified INNER JOIN clauses are supported.`; no test covers
  any outer join. A bare `OUTER JOIN` reports only `isOuter()`, while
  `NATURAL LEFT JOIN`, `LEFT SEMI JOIN`, and `LEFT APPLY` also report
  `isLeft()`, so recognition must exclude every other modifier explicitly.
  Result nullability comes only from the schema column today; the generator
  resolves Java types from the column type alone and reads every component with
  `ResultSet.getObject(position, Class)`, so generated code already returns
  `null` for an unmatched row and needs no change. The boundary is `SELECT`
  source and projection analysis and the query and null documentation: a left
  join keeps the existing `ON` equality rules and diagnostics and may be mixed
  with inner joins in any order; every projected column of a left-joined
  source, whether direct, aliased, `qualifier.*`, or `*`, is nullable even when
  declared `NOT NULL`, while the base and inner-joined sources keep schema
  nullability. Parameters, binding order, full-row identity (a join never
  shares a row type), the generator, and the runtime are unchanged; `RIGHT`,
  `FULL`, bare `OUTER`, `NATURAL`, `SEMI`, `APPLY`, `USING`, comma lists, and
  non-table join sources stay rejected under an updated diagnostic. The sample
  is M17-W6.
  Review evidence: `QueryAnalyzer` records whether each `SELECT` source is
  left-joined and accepts a join only when it is an unmodified inner join or a
  `LEFT`/`LEFT OUTER` join with no other modifier, join hint, or `USING`; the
  table-source check and `ON` equality rules are unchanged. A column read from a
  left-joined source is nullable in direct, aliased, `qualifier.*`, and `*`
  projections, while base, inner-joined, and write sources keep schema
  nullability. `SELECT u.id, p.id AS profile_id, p.nickname` over either
  spelling yields `[id BIGINT false, profile_id BIGINT true, nickname VARCHAR
  true]`; `*` and `p.*, u.name` mark every `profiles` column nullable; an inner
  join followed by a left join and the reverse order each make only the
  left-joined source nullable; and `WHERE u.id = $1` keeps `[id BIGINT]` with
  binding `[1]`. `RIGHT`, `RIGHT OUTER`, `FULL OUTER`, bare `OUTER`,
  `NATURAL LEFT`, `LEFT SEMI`, and `LEFT JOIN ... USING` fail with `Only
  unmodified INNER JOIN and LEFT JOIN clauses are supported.`, and a
  multi-predicate or non-equality left-join `ON` fails with `A join requires
  exactly one ON equality.` `QueryModel`, the generator, and the runtime are
  unchanged. `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test`
  passed (460 CLI and 25 runtime tests, 0 skipped, including 166 analyzer and 18
  PostgreSQL tests;
  the new `PostgresIntegrationTest.shouldExecuteGeneratedLeftJoinAgainstPostgres`
  compiles a `:many` left join and returns a matched row and an unmatched
  customer whose `orderId` and `quantity` are `null` against PostgreSQL 16), and
  `docs/queries.md` and `docs/postgresql.md` document the accepted join, its
  nullability, and the rejected forms.
- **M17-W6 — External sample read slice — Completed.** Satisfies the sample
  acceptance line after M17-W1 and M17-W3 through M17-W5: the external sample and
  quickstart add a text search, count, paged list, and left-join projection
  beside the existing optional lookup, through generated repository methods.
  Acceptance evidence is `scripts/verify-release.sh` against PostgreSQL 16,
  `mvn spotless:check`, and `mvn test`.
  Reassessed before implementation: the sample and quickstart still hold only
  the six `authors` queries, and the one-table schema cannot express a
  meaningful left join, so the sample schema gains a `books` table referencing
  `authors` and one `:exec` insert that gives the join a matched row. The
  acceptance line lists no range predicate, so M17-W2 is deliberately not
  demonstrated in the sample. The boundary is the external sample's schema,
  queries, and application, the quickstart listings, output, and diagnostic line,
  and the release script's schema reset and generated-method check; the
  compiler, generator, runtime, `sqlcj.yaml`, and the single generated
  `AuthorRepository` are unchanged.
  Accepted: the sample schema adds `books`, and its eleven queries generate one
  `AuthorRepository` exposing `searchAuthors(String name)`,
  `countAuthors()` returning `CountAuthorsResult`,
  `listAuthorPage(Integer limit, Integer offset)`,
  `createBook(Long authorId, String title)` returning `int`, and
  `listAuthorBooks()` returning `List<ListAuthorBooksResult>`. The sample checks
  that `ILIKE '%lovelace%'` returns only Ada, the count is `2`, page `(1, 1)`
  returns only Grace, the book insert affects one row, and the left join returns
  Ada with a `null` title before Grace with the inserted title, then still ends
  with one author after the delete. `scripts/verify-release.sh` passed twice in
  a row against PostgreSQL 16, proving the `books, authors` reset is rerunnable;
  the quickstart schema and query listings are byte-identical to the sample, its
  `App.java` listing compiled against the generated repository and printed
  exactly the documented output against PostgreSQL 16, and appending
  `CountAuthorBios` after one blank line reproduces the documented line-57
  diagnostic. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (460 CLI tests, 0 skipped).
  M17-W2 is intentionally not demonstrated in the sample.

#### Required

Implement these as separate reviewed work items so one does not silently widen
another:

1. Text and null predicates: typed `LIKE`/`ILIKE` parameters and direct-column
   `IS NULL`/`IS NOT NULL`.
2. Range predicates: `BETWEEN`/`NOT BETWEEN` with both bounds inferred from one
   direct column.
3. Pagination: literal or placeholder `LIMIT` and `OFFSET` with one documented
   non-negative integral Java type and correct placeholder accounting.
4. Scalar count: `COUNT(*)` with a required result alias and a documented
   non-null `Long` mapping. Do not generalize this into arbitrary function or
   aggregate inference.
5. Narrow left joins: `LEFT JOIN` with the same direct qualified equijoin shape
   already supported for inner joins. Columns from the right side remain
   nullable at runtime.

Every slice must fail clearly for a nearby unsupported form rather than passing
an untyped placeholder or inventing a generic `Object` result.

#### Out of scope

- arbitrary functions, arithmetic or computed projections,
- `GROUP BY`, `HAVING`, aggregate expressions other than the stated `COUNT(*)`,
- dynamic page/sort objects, keyset pagination helpers, or generated count
  companions,
- other join kinds, arbitrary join predicates, CTEs, subqueries, and set
  operations,
- write-expression or upsert expansion.

#### Acceptance

- The external sample includes a text search, optional lookup, count, paged
  list, and narrow left-join projection through generated repository methods.
- All new parameters have one known Java type and every accepted placeholder is
  bound exactly once per textual occurrence.
- Focused analyzer/generator tests, generated-source compilation, PostgreSQL
  execution, `mvn spotless:check`, and `mvn test` pass.

### Milestone 18 — Generation Workflow Hardening

#### Goal

Make repeated local and CI generation predictable without adding a build-system
plugin.

#### Work items

Reassessed on activation against the implementation, tests, external sample,
packaged-CLI smoke checks in `scripts/verify-release.sh`, and documentation.
Requirement 1 is unimplemented: `GenerateCommand` always loads `sqlcj.yaml` from
the working directory and `docs/configuration.md` states that no path option
exists, but `YamlConfigLoader` already resolves relative `schema`, `queries`,
and `java.out` against the configuration file's directory. Requirement 2 is
unimplemented: no manifest exists, `GeneratedFileWriter` creates or truncates
each file in place, and the documentation relies on `mvn clean` for stale
output. Since Milestone 15 one repository file is generated per `sql` entry, so
a renamed or removed query only changes methods inside that file; stale Java
arises when an entry is renamed or removed or `java.package` changes, and
requirement 6 and the stale-output acceptance line are proved with a query
group. Requirement 3 is half met: every file is generated before the first
write (`shouldReportLateFailureAndLeavePreviousOutputIntact`), but a write
failure escapes `GenerateCommand` as an `UncheckedIOException` through picocli's
unhandled-exception path instead of one `sqlcj:` diagnostic. Requirement 4 is
partly met: each repository's class Javadoc reads `Generated by sqlcj.` and
names the repository without timestamps or paths, but it follows the imports and
says nothing about editing. Requirement 5 already holds for configuration,
source, and query failures (one `sqlcj:` line, exit status `1`, covered by
`GenerateCommandTest` and the packaged smoke check); output failures do not, and
nothing detects a runtime-version mismatch because the CLI never sees the
application's runtime and the documentation only requires matching versions.
Requirement 6 is unimplemented: the release script generates once and expects
exactly one file. Generation emits content from ordered collections, but no test
compares two runs.

- **M18-W1 — Explicit configuration path — Completed.** Implements
  requirement 1. `sqlcj generate --config <path>` loads that file, a relative
  option value is resolved against the process working directory, and the
  file's own relative paths keep resolving against its directory, so generation
  works from another working directory; without the option `sqlcj.yaml` in the
  working directory stays the default. A missing or invalid explicit file
  reports the existing configuration diagnostics. The boundary is the `generate`
  command and the configuration, README, and quickstart statements about
  configuration discovery; no short alias, environment variable, or parent
  directory discovery is added. Acceptance evidence is `dev.sqlcj.Main`
  subprocess tests run from a different working directory, the unchanged
  default-path test, `mvn spotless:check`, and `mvn test`.
  Review evidence: `generate --config ../project/sqlcj-users.yaml` run as a
  `dev.sqlcj.Main` subprocess from a sibling directory exits 0, writes
  `project/generated/.../UsersRepository.java`, and leaves the working directory
  empty; `--config missing.yaml` beside a valid `sqlcj.yaml` exits 1 with
  `sqlcj: Cannot read configuration file: <workingDir>/missing.yaml`, no stack
  trace, and no output directory. The option defaults to `sqlcj.yaml`, the four
  default-path `GenerateCommandTest` cases are unchanged, and `generate` in an
  empty directory still prints one `sqlcj:` line and exits 1. The configuration,
  README, and quickstart documentation describe the option and its path
  resolution. `mvn spotless:check` and `mvn test` passed (462 tests, 0 failures,
  0 skipped).
- **M18-W2 — Focused output-failure reporting — Completed.** Implements the
  write half of requirement 3. A filesystem failure while creating an output
  directory or writing a generated file ends the run with one `sqlcj:`
  diagnostic naming the failing path and exit status `1`, with no stack trace,
  while analysis stays all-or-nothing before the first write. The boundary is
  the output layer, `GenerateCommand`, and the documented non-transactional
  write behavior; no rollback or staging directory is added. Decided on
  activation: the diagnostic reads `Cannot create output directory: <directory>`
  or `Cannot write generated file: <file>` with the resolved path, matching the
  existing `Cannot read ... source: <path>` form; adding the filesystem's reason
  or a corrective fact stays with M18-W5. Failures are forced portably, without
  permission changes, by a regular file where an output directory is needed and
  a directory where a generated file goes. Acceptance evidence is compiler and
  CLI tests that force a write failure as far as the filesystem permits, the
  existing previous-output test, `mvn spotless:check`, and `mvn test`.
  Review evidence: `GeneratedFileWriter` reports `Cannot create output
  directory: <resolved parent>` when `java.out` is a regular file and `Cannot
  write generated file: <resolved file>` when the target is a directory, keeping
  the original exception as the cause; `SqlcjCompiler` rethrows it as a
  `CompilationException` with that message, so `GenerateCommand`'s existing
  handler prints it. A `dev.sqlcj.Main` subprocess with `out: generated` naming
  a regular file exits 1 with exactly one stderr line, `sqlcj: Cannot create
  output directory: <workingDir>/generated/dev/example/generated`, no stack
  trace, and the file's content unchanged; `compile` with
  `UsersRepository.java` pre-created as a directory throws `Cannot write
  generated file: <absolute path>`. `shouldReportLateFailureAndLeavePreviousOutputIntact`
  and the existing `GenerateCommandTest` cases pass unmodified, and the
  configuration and README documentation describe the diagnostics and that
  already-written files stay in place. `mvn spotless:check` and `mvn test`
  passed (466 CLI tests, 0 failures, 0 skipped).
- **M18-W3 — Generated-output manifest and stale cleanup — Completed.**
  Implements requirement 2 and the cleanup half of requirement 3. Decided on
  activation as the smallest output-owned format: a successful run records
  `sqlcj-manifest.txt` in `java.out`, a UTF-8 file listing every generated file
  as a sorted `/`-separated path relative to `java.out`, one per line; after
  every generated file is written, a file listed by the previous manifest and
  not produced by this run is deleted, and nothing else is. Entries that are not
  a normalized relative path inside `java.out`, directories, and unlisted files
  are never deleted; a missing previous manifest deletes nothing; empty
  directories are left in place; a cleanup or manifest failure is reported like
  M18-W2. Changing `java.out` leaves the previous directory untouched. Acceptance
  evidence is compiler tests for renamed and removed entries and a changed
  package, an unlisted user file that survives, a failed compilation that
  leaves files and manifest unchanged, byte-identical manifests from two runs,
  documentation, `mvn spotless:check`, and `mvn test`.
  Refined on activation: every manifest line ends with `\n` and entries match
  produced paths exactly; the previous manifest is read after generation and
  before the first write, and stale files are deleted before the new manifest
  is written, so a failed write or cleanup keeps the previous manifest and the
  next successful run retries. Only a listed regular file is deleted, never one
  that is the same file as one this run wrote, which a case-only rename
  produces on a case-insensitive filesystem. Failures read `Cannot read output
  manifest: <file>`, `Cannot delete stale generated file: <file>`, or `Cannot
  write output manifest: <file>`; the manifest-read failure is forced portably
  by a directory at the manifest path. Documentation covers the configuration
  guide and README, and in the quickstart only the sentence stating that sqlcj
  never deletes files; its regeneration steps stay with M18-W6.
  Review evidence: `SqlcjCompiler` reads `sqlcj-manifest.txt` after generation
  and before the first write, writes every file, deletes stale entries, then
  writes the sorted, `\n`-terminated UTF-8 manifest through the output-layer
  `GeneratedOutputManifest`. Generating `Users` and `Orders` and then only
  `Customers` deletes both old repositories and leaves a manifest listing only
  `CustomersRepository.java`; a changed `java.package` deletes the old
  repository and keeps its package directory; a hand-written file and a file
  present before the first manifest survive; two runs of `Users` then `Orders`
  give byte-identical repositories and the manifest
  `dev/example/generated/OrdersRepository.java\ndev/example/generated/UsersRepository.java\n`.
  The late-failure test also keeps the manifest bytes unchanged, and a directory
  at the manifest path fails with `Cannot read output manifest: <path>` before
  any repository is written. Listed `../`, absolute, non-normalized, and empty
  entries, a directory, and a hard link to a produced file are never deleted.
  `mvn spotless:check` and `mvn test` passed (479 CLI and 25 runtime tests,
  0 failures, 0 skipped).
- **M18-W4 — Stable generated-code notice — Completed.** Implements
  requirement 4. Every generated Java file begins with one fixed notice stating
  that sqlcj generated it and that it must not be edited, with no version,
  timestamp, or machine-specific path; the repository Javadoc stays. The
  boundary is Java generation and the documented generated-source listings.
  Acceptance evidence is generator tests, compiled generated source, a
  byte-identical second generation, `mvn spotless:check`, and `mvn test`.
  Refined on activation: the notice is the single line
  `// Code generated by sqlcj. DO NOT EDIT.`, the first line of every
  generated repository, followed by one blank line and the `package`
  declaration. It is rendered by Java generation, not the output layer, and
  the output manifest is not Java source and gets no notice. The only
  generated-source listing is in the query guide; the quickstart and README
  list none.
  Review evidence: `JavaCodeGenerator` emits the constant
  `// Code generated by sqlcj. DO NOT EDIT.` as the first template element,
  followed by one blank line and the `package` declaration, with no version,
  time, path, or environment-derived text; everything after the package line,
  including the `Generated by sqlcj.` Javadoc, is unchanged. Generator tests
  assert the notice-plus-package prefix for the default and a configured
  package, a single notice occurrence, and the retained Javadoc, and the
  configured-package source compiles; `SqlcjCompiler` and CLI `generate` tests
  assert the same prefix, and the unmodified
  `shouldGenerateByteIdenticalRepositoriesAndManifestForTwoRuns` passes. The
  query guide's `AuthorRepository` listing starts with the notice and a bullet
  describes it. `mvn spotless:check` and `mvn test` passed (480 CLI tests,
  0 failures, 0 skipped).
- **M18-W5 — Actionable CLI diagnostics — Completed.** Implements
  requirement 5 after M18-W1 through M18-W3: configuration, source, analysis,
  and output failures each print one concise `sqlcj:` line naming the failing
  file and the corrective fact, exit with status `1`, and the documented exit
  behavior covers them. Decided by the owner on activation: the CLI cannot
  observe the application's runtime, so the documented rule that the CLI and
  the runtime are always used at the same version covers runtime-version
  mismatch; no CLI detection, version in generated source, or runtime version
  check is added. Acceptance evidence is CLI tests per failure category, the
  packaged smoke check, `mvn spotless:check`, and `mvn test`.
  Refined on activation: every such failure already prints one `sqlcj:` line
  naming its file and exits with status `1`, so the work adds the missing
  corrective fact while keeping each existing message prefix: the filesystem
  reason for an unreadable configuration file or schema or queries source; the
  YAML problem and its line and column for a malformed configuration file; the
  parser's reason without exception class names for a schema or query SQL
  syntax error, with line and column for the schema; and the filesystem reason
  for each output diagnostic, which M18-W2 deferred here. The diagnostics
  documentation states exit status `1` for every category.
  Review evidence: every existing message prefix is kept and ends with its
  corrective fact. A missing `--config` file or queries source ends with
  `: No such file or directory`, a configuration path that is a directory with
  `: Is a directory`, and malformed YAML with the parser's problem and one-based
  position, such as `expected <block end>, but found '<block mapping start>' at
  line 2, column 3`. Output directory, generated file, manifest read or write,
  and stale-delete failures append the filesystem reason (`Not a directory`,
  `Is a directory`) through `FileSystemReason`. A schema syntax error reads
  `Invalid schema source <file>: Encountered unexpected token: ";"
  <ST_SEMICOLON> at line 4, column 1` instead of `Failed to parse schema`, and a
  query syntax error reads `Invalid query 'GetUser' in <file> at line 1:
  Encountered unexpected token: "AND" "AND"`; a lexical error such as an
  unterminated string literal prints the parser's own `Lexical error at line L,
  column C. ...` wording, and no syntax diagnostic contains `net.sf.jsqlparser`.
  `GenerateCommandTest` asserts exactly one stderr line, exit status `1`, and no
  stack trace for the missing configuration, malformed YAML, schema syntax,
  query syntax, missing source, and output-directory cases, with no output
  written before the output stage and the blocking file left unchanged.
  The packaged CLI jar run in an empty directory exits 1 with one `sqlcj:` line
  and no exception text, and on a query and a schema with an unterminated string
  literal exits 1 with one clean line and writes nothing. The configuration,
  PostgreSQL, and README documentation state exit status `1` and show the
  facts. No runtime-version check was added. `mvn spotless:check` and
  `mvn test` passed (488 CLI tests, 0 failures, 0 skipped).
- **M18-W6 — External sample regeneration proof — Completed.** Implements
  requirement 6 and the sample acceptance lines after M18-W1 and M18-W3: the
  release script generates the external sample with `--config` from another
  working directory, generates twice and compares the source and manifest
  bytes, then renames and removes a query group and proves no stale repository
  remains before regenerating the sample's own configuration to compile and run
  it; the quickstart's regeneration guidance reflects the cleanup. Acceptance
  evidence is `scripts/verify-release.sh` against PostgreSQL 16,
  `mvn spotless:check`, and `mvn test`.
  Refined on activation: after copying the sample, the script runs
  `generate --config ../sample/sqlcj.yaml` twice from an empty sibling
  directory, which stays empty, and requires the second output tree, including
  `sqlcj-manifest.txt`, to match the first byte for byte. Two scratch
  configurations outside the sample, sharing its schema, queries, package, and
  `java.out`, then generate the groups `Author` and `Library` and next only
  `Writer`, after which the output holds only `WriterRepository.java` and a
  manifest listing it alone. The existing default-path generation from the
  sample directory then restores exactly `AuthorRepository.java`, identical with
  the manifest to the first run, before the unchanged compile and run. The
  quickstart keeps `mvn clean`, because sqlcj never deletes compiled classes,
  lists the manifest among the generated files, attributes a stale repository to
  a renamed or removed `sql` entry rather than a query, and says in its re-run
  section that regeneration deletes that repository. No product code, sample
  source, or CI workflow changes.
  Review evidence: `scripts/verify-release.sh` against PostgreSQL 16 runs
  `generate --config ../sample/sqlcj.yaml` from an empty sibling directory,
  leaving it empty, and a second run's output tree, including
  `sqlcj-manifest.txt`, matches the first under `diff -r`. Scratch
  configurations generating `Author` and `Library` and then only `Writer`
  leave exactly one Java source, `WriterRepository.java`, and a manifest equal
  to `com/example/app/db/WriterRepository.java\n` under `cmp`. The unchanged
  default-path generation from the sample directory then leaves one source with
  all eleven methods and matches the first output tree, and the sample compiles
  and runs; the script exits 0 with `Release verification passed for version
  0.1.0-SNAPSHOT` and removes its workspace. Every new check aborts through
  `fail`. The quickstart keeps `mvn clean` for compiled classes, lists the
  manifest, attributes a stale repository to a renamed or removed `sql` entry,
  and its re-run section describes manifest-driven deletion with a link to the
  configuration rules. `mvn spotless:check` and `mvn test` passed (488 CLI and
  25 runtime tests, 0 failures, 0 skipped).

#### Required

1. Add `generate --config <path>` while retaining `sqlcj.yaml` in the working
   directory as the default.
2. Write a sqlcj-owned manifest of generated relative paths. After a fully
   successful compilation, remove only stale files listed by the previous
   manifest; never delete an unlisted user file.
3. Keep analysis all-or-nothing before output mutation. Define and test how a
   write or stale-cleanup failure is reported.
4. Mark generated sources consistently with a stable generated-code notice;
   do not add timestamps or machine-specific paths that break deterministic
   output.
5. Make CLI diagnostics concise and actionable for configuration, source,
   analysis, and output failures. Runtime-version mismatch is covered by the
   documented rule that the CLI and the runtime are always used at the same
   version, not by a CLI diagnostic.
6. Update the external sample and release verification to generate twice, then
   rename/delete a query and prove no stale class remains.

#### Out of scope

- Maven and Gradle plugins, daemon/watch mode, IDE integration, or incremental
  compiler protocols,
- `generate --check`, SQL formatting, schema diffing, or migration verification,
- deleting an entire output directory or any file sqlcj did not record as its
  own output.

#### Acceptance

- Generation works from another working directory with an explicit config path.
- Identical input produces byte-identical source and manifest output.
- Removed and renamed queries do not leave compilable stale Java behind.
- A compilation failure leaves the last successful generated set unchanged;
  output I/O failures are focused and covered as far as the filesystem permits.
- Packaged-CLI smoke tests, the external sample, `mvn spotless:check`, and
  `mvn test` pass.

### Milestone 19 — Schema From Migrations

#### Goal

Let a query group read the schema the application already maintains, so that
an existing PostgreSQL service loads without a hand-written snapshot and
without rewriting its DDL.

#### Work items

Reassessed on activation against the implementation, tests, external sample,
documentation, JSqlParser 5.3 and 5.4 run on the statements below, Flyway's
documentation and `MigrationVersion` source, and sqlc's `booktest` and `ondeck`
PostgreSQL schemas. Requirements 2–7 are unimplemented as assumed:
`sql[].schema` is one file, `DefaultSchemaParser` accepts only `CREATE TABLE`
and fails the whole schema on the first unknown type (`Unsupported SQL column
type: TIMESTAMPTZ`, `CHARACTER VARYING`), and the sample reads one
`schema.sql`. Both parser versions report `TIMESTAMPTZ`, `INT8`, `SERIAL8`,
`CHARACTER VARYING (20)`, and the other requirement 6 spellings verbatim, and
parse every requirement 3 `ALTER TABLE` form including `IF [NOT] EXISTS` (only
`SET DATA TYPE`, which is not required, fails), so requirements 2, 3, 5, and 6
do not depend on the upgrade. Corrections to the milestone's assumptions:

- An array column does not fail the schema, contrary to
  [Where sqlcj Stands](#where-sqlcj-stands): the parser reports `varchar[]` as
  type `varchar` with separate array dimensions, which `DefaultSchemaParser`
  ignores, so `tags varchar[]` is silently modeled as a `VARCHAR` `String`
  today. Requirement 5 must record any array column as unsupported.
- JSqlParser 5.4 (Maven Central, 2026-09-13) parses `CREATE TYPE ... AS ENUM`,
  `CREATE EXTENSION IF NOT EXISTS`, and `ALTER TYPE ... ADD VALUE`, which 5.3
  rejects or returns as an opaque unsupported statement. But requirement 1's
  "every existing test unchanged" cannot hold: 5.4 removed
  `net.sf.jsqlparser.parser.SimpleNode` (merged into `Node`), which
  `SqlParameterCompiler` and the `SqlParameterCompilerTest` fixture use, and
  with that mechanical fix six existing tests still fail against 5.4. Four
  assert the parser's diagnostic wording, which 5.4 changed (`Encountered:
  <ST_SEMICOLON> / ";", at line 4, column 1, in lexical state DEFAULT.` with
  sqlcj's appended location repeated, `after:` instead of `after prefix`) and
  which `docs/postgresql.md` documents. `SqlParserTest.shouldRejectDollarQuotedText`
  fails because 5.4 lexes `$$...$$` as one literal and leaves a `$1` inside it
  unbound, and `shouldPreserveParameterTextInsideBlockCommentWithNestedDelimiter`
  fails because 5.4 nests block comments as PostgreSQL does and treats that
  test's unterminated nested comment as running to the end of the query.
- JSqlParser 5.4 parses `COMMENT ON` only for `TABLE`, `COLUMN`, and `VIEW`;
  `COMMENT ON TYPE`, `INDEX`, `CONSTRAINT`, `FUNCTION`, `SCHEMA`, and
  `EXTENSION` are syntax errors (support exists on JSqlParser's main branch
  after 5.4 but is unreleased), and `ALTER INDEX` parses only as an opaque
  unsupported statement. `ondeck`'s `0002_venue.sql` contains
  `COMMENT ON TYPE status IS ...`, so "sqlc's `ondeck` schema loads" cannot be
  met with 5.4 without the pre-processing requirement 1 forbids. Every
  statement of `booktest` parses with 5.4, including its dollar-quoted
  `CREATE FUNCTION`. `ondeck`'s migration files are named `0001_city.sql` to
  `0003_add_column.sql`, so they load in the lexical order.
- The parser reports no per-statement position, but the token chain of its
  parse tree carries lines, so requirement 4's rejected-statement line can be
  derived without splitting SQL text.

Owner decisions, decided by the owner on 2026-09-27:

- **D1 (M19-W4):** the upgrade may change exactly the parser-node fixture and
  nine expectations: the six above and three that assert the same syntax
  diagnostic through the CLI and compiler,
  `GenerateCommandTest.shouldFailWithConciseDiagnosticForSchemaSyntaxError`,
  `GenerateCommandTest.shouldFailWithConciseDiagnosticForQuerySyntaxError`, and
  `SqlcjCompilerIntegrationTest.shouldReportTheMigrationFileThatFailsToParse`
  (amended from six by the owner on 2026-09-27 after a 5.4 run of the suite
  failed those nine). 5.4's dollar quoting and block-comment nesting
  are accepted because they match PostgreSQL: a `$N` inside `$$...$$` is text,
  like a `$N` inside a quoted string, and the nested-comment test's input is an
  unterminated comment that PostgreSQL itself rejects. Syntax diagnostics stay
  one line without class names or parser-internal token kinds and with one
  location, composed from the parser's unexpected token (`Encountered
  unexpected token: ";" at line 4, column 1` for a schema, without a location
  for a query) rather than from the parser's message text; a syntax error at
  the end of input reports `Encountered unexpected end of input`, with the same
  location rule. Lexical errors keep the parser's own wording. Requirement 1 is
  amended accordingly.
- **D2 (M19-W6, M19-W8):** requirement 4's `COMMENT ON` is limited to the
  `TABLE`, `COLUMN`, and `VIEW` targets 5.4 parses, and the `ondeck` acceptance
  is amended: its schema fails only on its `COMMENT ON TYPE` statement, with a
  located syntax diagnostic, and loads without that statement. A syntax error
  fails the whole schema, so `ondeck` as published does not load. sqlcj does
  not split or pre-process SQL to skip the statement, and the parser's
  unsupported-statement mode is not a substitute: it drops the statement's
  leading keywords and turns every syntax error into an opaque statement.
  Because an applied Flyway migration cannot be edited without breaking its
  checksum, this limitation blocks a user whose history contains another
  `COMMENT ON` target; Milestone 23 therefore rechecks for a JSqlParser release
  that parses those targets.
- **D3 (M19-W8; Milestones 20 and 21):** decided by the owner on 2026-09-28.
  sqlc's example files are not copied into the repository. An acceptance item
  that names sqlc's example schemas or queries is proven by sqlcj-owned
  fixtures, original SQL reproducing every construct of those files that bears
  on the item, together with a one-off probe of the published upstream files
  whose outcome is recorded in this roadmap. The same policy applies to later
  milestones whose acceptance names sqlc examples. Milestone 19's probe ran the
  files of sqlc commit `bdbe55db3ee08745827090d4aad1781bea2ee4ee` through sqlcj
  at `2652c19`: `booktest`'s `schema.sql` loads, recording `books.book_type` as
  `BOOK_TYPE` and `books.tags` as `VARCHAR[]`; the `ondeck` directory fails as
  `Invalid schema source <0002_venue.sql>: Encountered unexpected token:
  "TYPE" at line 2, column 12`, and without that statement it loads into
  `city` and the renamed `venue`, without the dropped `dropped` and with the
  added `created_at`, recording `status` as `STATUS`, `statuses` as
  `STATUS[]`, and `tags` as `TEXT[]`.
  Amended by the owner on 2026-10-04, effective for Milestone 21's M21-W7 and
  every later milestone: sqlc's example files are neither copied into the
  repository nor fetched, cloned, or run, in or outside it. An acceptance item
  that names sqlc's example schemas or queries is proven only by sqlcj-owned
  fixtures of original SQL reproducing the constructs that bear on the item,
  and the one-off upstream probe is no longer part of the policy. The probe
  outcomes already recorded, this milestone's above, Milestone 20's D2, and
  Milestone 21's activation reassessment, stay as historical record.

- **M19-W1 — Schema input from files and migration directories —
  Completed.** Implements requirement 2. `sql[].schema` accepts one path or a
  non-empty list of paths, each resolved against the configuration file's
  directory; a file contributes itself and a directory contributes its regular
  `.sql` files, non-recursively. Flyway versioned files
  (`V<version>__<description>.sql`, version parts separated by `.` or `_`,
  compared numerically part by part with missing parts as zero) come first in
  version order, then every other `.sql` file in lexical file-name order;
  undo files (`U<version>__...`) are ignored. Two versioned files with equal
  versions, or a directory that contributes no file, fail naming the
  directory. Files are parsed in order into one schema, and a schema failure
  names the file that contains it. The boundary is configuration, source
  loading, schema assembly, and the configuration documentation; the single
  file form and its diagnostics are unchanged, and no new statement or type is
  accepted. Acceptance evidence is configuration, source-loading, and compiler
  tests, `mvn spotless:check`, and `mvn test`.
  Review evidence: `SqlConfig.schema` is a list that also accepts a single
  YAML string, and the built CLI reports a missing, null, blank, numeric,
  boolean, or mapping scalar `schema` with the same message as before the
  change. `DefaultSourceLoader` expands a directory in place into its regular,
  case-sensitive `.sql` files, pre-sorts them by file name and stably sorts the
  versioned ones by `BigInteger` version parts; the loader test orders a file
  and a directory as `V1`, `V1_1`, `V2`, `V10`, `a_extra.sql`, `baseline.sql`
  while ignoring `U1__undo.sql`, `V3__upper.SQL`, `notes.txt`, and the
  subdirectory `nested.sql`. `SqlcjCompiler` parses each `SchemaFile`
  separately and concatenates the tables; a two-file directory generates a
  repository byte-identical to the one-file snapshot that also compiles, and a
  syntax error in `V2__orders.sql` names that file and writes no output. Run
  through the built CLI, `V01__a.sql` and `V1__b.sql`, a directory of only
  `readme.md` and a `sub.sql` subdirectory, and an unlistable directory fail
  with the documented one-line diagnostics, exit 1, and create no output
  directory. `mvn spotless:check` and `mvn test` passed (474 CLI and 25
  runtime tests, 0 skipped, including 18 PostgreSQL tests).
- **M19-W2 — PostgreSQL spellings of supported types — Completed.**
  Implements requirement 6: `TIMESTAMPTZ`, `TIMESTAMP WITHOUT TIME ZONE`,
  `INT2`/`INT4`/`INT8`, `SMALLSERIAL`/`SERIAL2`/`SERIAL4`/`SERIAL8` (never
  nullable, like `SERIAL`), `CHARACTER VARYING`, and `CHAR`/`CHARACTER` map to
  the existing Java types. "Every spelling" means the names and aliases
  PostgreSQL's data-type table documents for the supported types, which is
  this list; JSqlParser 5.3 reports each verbatim, with any type arguments,
  so the existing case-insensitive, argument-stripping match applies.
  `CHAR`/`CHARACTER` is a `String` character column like `VARCHAR`, read back
  blank-padded as PostgreSQL stores it. Grammar-only synonyms (`CHAR VARYING`,
  `DEC`, `NCHAR`, `BPCHAR`), quoted or schema-qualified type names, and the
  distinct `"char"` type stay unsupported. The boundary is the schema parser's
  type mapping, its tests, one PostgreSQL execution test, and the type
  documentation. Acceptance evidence is schema-parser tests, PostgreSQL
  execution of the new spellings, the type documentation,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` maps `INT4`/`SERIAL4` to `INTEGER`,
  `INT8`/`SERIAL8` to `BIGINT`, `INT2`/`SMALLSERIAL`/`SERIAL2` to `SMALLINT`,
  `CHARACTER VARYING`/`CHAR`/`CHARACTER` to `VARCHAR`,
  `TIMESTAMP WITHOUT TIME ZONE` to `TIMESTAMP`, and `TIMESTAMPTZ` to
  `TIMESTAMP_WITH_TIME_ZONE`, and models every serial spelling non-null; the
  rejection message is unchanged. Parser tests cover each spelling in upper
  and lower case, with type arguments where allowed, the integer aliases'
  `NOT NULL`-only nullability, and the rejection of `JSONB`,
  `DOUBLE PRECISION`, and `"char"`. A PostgreSQL 16 table declared through the spellings round trips
  through generated code with the mapped Java types, `ab` in `CHAR(3)` read
  back as `ab `, `TIMESTAMPTZ` equal by instant, and the serial columns filled
  by their sequences. `docs/postgresql.md` lists the spellings. The test source
  also compiles under ECJ 3.46.0. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (531 CLI and 25 runtime
  tests, 0 skipped, including 76 schema-parser and 19 PostgreSQL tests).
- **M19-W3 — Unsupported column types recorded until used — Completed.**
  Implements requirement 5. A column of an unmapped type, including any array
  column, is recorded with its declared type instead of failing the schema; a
  query that reads, binds, or expands it (`*` or `RETURNING *`) fails naming the
  query, column, and type, while other queries over the table compile.
  The recorded type is the canonical spelling the type match already uses
  (upper case, arguments removed) followed by `[]` per array dimension, so
  `varchar(20)[]` is `VARCHAR[]`. A query reads such a column when it is a
  `SELECT` or `RETURNING` item, binds it when a placeholder takes its type (a
  comparison, `IN`, range, or pattern bound, an `INSERT` column, or an
  `UPDATE` assignment), and expands it through `*`, `qualifier.*`, or
  `RETURNING *`; a reference that neither returns nor types anything, such as
  `IS NULL` or a join `ON` equality, compiles. The boundary is the schema
  column model, the schema parser's type mapping, the analyzer's column and
  parameter typing, and the type documentation; statements, table
  constraints, and syntax errors fail as before. Acceptance evidence is schema
  and analyzer tests for a reading and a non-reading query, a compiler test of
  the query-naming diagnostic and of generated code that compiles beside an
  unsupported column, `mvn spotless:check`, and `mvn test`.
  Review evidence: `Column` gains a nullable `unsupportedType`, and its
  three-argument constructor keeps every mapped column equal to before.
  `DefaultSchemaParser` records `JSONB`, `DOUBLE PRECISION`, `"CHAR"`,
  `VARCHAR[]`, `INTEGER[][]`, `NUMERIC[]`, and `INT[]` (the canonical spelling
  of `int[]`) beside unchanged mapped columns, with `NOT NULL` parsed as
  before. `QueryAnalyzer` types every selected, expanded, returned, and bound
  column through one check, so over a `VARCHAR[]` column `tags` each of
  `SELECT tags`, `SELECT *`, `SELECT b.*`, `tags = $1`, `tags IN ($1)`,
  `tags LIKE $1`, an `INSERT` column, an `UPDATE` assignment, `RETURNING tags`,
  and `RETURNING *` fails with `Column 'tags' has unsupported type VARCHAR[]`;
  a probe also confirmed `BETWEEN`, `ILIKE`, `$1 = tags`, `o.*` in a join, and
  a `JSONB` comparison fail, while `IS NULL`, a join `ON` equality, a literal
  `IN` list, and `ORDER BY tags` compile. Queries that do not use the column,
  including `COUNT(*)`, analyze to models equal to those over a table without
  it. Through `SqlcjCompiler` the reading query fails as
  `Invalid query 'ListTags' in <file> at line 5: ...` and writes no output, and
  a repository generated beside a `VARCHAR(20)[]` and a `JSONB` column
  compiles. `docs/postgresql.md` documents the recording and failure rules.
  `mvn spotless:check` and `mvn test` passed (550 CLI and 25 runtime tests,
  0 skipped, including 19 PostgreSQL tests).
- **M19-W4 — JSqlParser 5.4 — Completed.** Implements requirement 1 and the
  `SimpleNode` API change, with the diagnostic and lexing expectations decided
  in D1. The boundary is the dependency version, the parser-node use and its
  fixture, syntax-diagnostic composition, the nine expectations of D1, and the
  documented schema diagnostic; no new statement is accepted. Acceptance
  evidence is the unchanged remainder of the suite, PostgreSQL tests,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: the build uses JSqlParser 5.4, and no `SimpleNode` remains;
  `SqlParameterCompiler` and its test fixture use `Node`, with the span
  guards and parameter order unchanged. `SqlParseReason` composes a syntax
  reason from the parser's unexpected token, `Encountered unexpected token:
  "<image>"` with line breaks escaped or `Encountered unexpected end of input`,
  followed by one location for a schema and none for a query, and keeps the
  lexical-error wording. Exactly the fixture and D1's nine expectations
  changed, now asserting `Encountered unexpected token: ";" at line 4,
  column 1` (schema), `Encountered unexpected token: "AND"` (query), `after:`
  (lexical), a bound `$1` beside an unchanged `$1` in `$$...$$`, and `$8` and
  `$9` preserved in a terminated nested comment. New tests assert the
  end-of-input reason with and without a location and a one-line escaped
  multi-line literal. A probe of twelve malformed queries and eight schemas
  produced no reason containing a token kind, a lexical state, or a class
  name, and each named the token the parser's own message names.
  `docs/postgresql.md` shows the new schema diagnostic. `mvn spotless:check`
  and `mvn -Dsqlcj.test.requireDocker=true test` passed (553 CLI and 25
  runtime tests, 0 skipped, including 19 PostgreSQL tests).
- **M19-W5 — Ordered table DDL — Completed.** Implements requirement 3 across
  the ordered files of M19-W1: `CREATE TABLE`, `DROP TABLE`, and the listed
  `ALTER TABLE` forms with their `IF [NOT] EXISTS` variants update one schema
  model, and a reference to a missing table or column fails naming the file.
  Each statement applies to the schema left by the statements and files before
  it. `CREATE TABLE IF NOT EXISTS` and `ADD COLUMN IF NOT EXISTS` do nothing
  when the name exists; `DROP TABLE IF EXISTS`, `ALTER TABLE IF EXISTS`, and
  `DROP COLUMN IF EXISTS` do nothing when it is missing. Otherwise a missing
  table or column fails, and so does a statement that would give two tables, or
  two columns of one table, the same name, as PostgreSQL rejects it; a repeated
  `CREATE TABLE`, which today loads silently, therefore fails. Names match
  case-insensitively, as query analysis looks them up. An added column is
  appended and typed like a `CREATE TABLE` column; a renamed or retyped column
  keeps its position, and a retyped one keeps its nullability; recorded
  primary-key and unique constraints follow a renamed column and are removed
  with a dropped one. Every other `ALTER TABLE` action and `DROP` object is
  rejected as before; M19-W6 owns the ignored list and its diagnostics. The
  boundary is the schema parser, schema assembly in the compiler, and the
  schema documentation. Acceptance evidence is schema tests per form and per
  failure, a migration directory that renames, adds, drops, and alters columns
  generating the same compilable repository as the equivalent snapshot, a
  missing reference naming its migration file, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: `SchemaParser.parse(Schema, String)` applies one source to
  the schema the earlier sources left, and `SqlcjCompiler` threads one schema
  through an entry's files in order; `parse(String)` applies to an empty
  schema. A probe against the built parser confirmed `ADD [COLUMN]`,
  `DROP [COLUMN]` (with `CASCADE`), `RENAME [COLUMN]`, `RENAME TO`,
  `ALTER [COLUMN] ... TYPE` with `USING` and `COLLATE` (position and
  nullability kept, `INET` recorded), `SET`/`DROP NOT NULL`, several actions of
  one statement, multi-name `DROP TABLE`, and every `IF [NOT] EXISTS` variant,
  with unique constraints following a rename and removed with a drop. `SET`/
  `DROP DEFAULT`, `SET STATISTICS`/`STORAGE`/`COMPRESSION`, `ADD`/`DROP`/
  `SET GENERATED` identity, `RESTART WITH`, `DROP EXPRESSION`,
  `ADD`/`DROP`/`RENAME CONSTRAINT`, `ADD PRIMARY KEY`, `ADD UNIQUE`, and a
  supported action beside an unsupported one each fail with exactly
  `Unsupported schema statement: Alter`, and `DROP INDEX`/`DROP VIEW` with
  `Unsupported schema statement: Drop`. Missing and repeated names fail with
  the four documented messages. A migration directory that renames, adds,
  drops, and alters columns generates a repository byte-identical to its
  snapshot's that compiles, and a missing table fails naming its migration
  file with no output written. `docs/postgresql.md` and
  `docs/configuration.md` document the forms and messages.
  `mvn spotless:check` and `mvn test` passed (580 CLI and 25 runtime tests,
  0 skipped, including 19 PostgreSQL tests).
- **M19-W6 — Ignored and rejected statements — Completed.** Implements
  requirement 4 as limited by D2; its M19-W4 dependency is completed. The
  documented list is accepted and leaves the schema model unchanged:
  `CREATE INDEX`, `ALTER INDEX`, and `DROP INDEX`; `COMMENT ON` `TABLE`,
  `COLUMN`, or `VIEW`; `CREATE EXTENSION`; `CREATE`, `ALTER`, and
  `DROP SEQUENCE`; `GRANT` and `REVOKE`; `CREATE FUNCTION` with a
  dollar-quoted body, `DROP FUNCTION`, `CREATE TRIGGER`, and `DROP TRIGGER`;
  the `ALTER TABLE` actions that add a `PRIMARY KEY`, `UNIQUE`,
  `FOREIGN KEY`, or `CHECK` constraint, `DROP CONSTRAINT`, and
  `RENAME CONSTRAINT`, alone or beside modeled actions; `INSERT`, `UPDATE`,
  and `DELETE`; and `CREATE TYPE ... AS ENUM`. An ignored statement is not
  resolved against the schema, while an `ALTER TABLE` still resolves its
  table. A function body that is not dollar-quoted is rejected, because the
  parser then captures the function through the end of the file. Every other
  statement, including every other `ALTER TABLE` action and `ALTER TYPE`,
  fails as `Unsupported schema statement: <kind> at line <n>`, with the
  statement kind named as today and the line of the statement's first token;
  missing-reference and syntax diagnostics are unchanged. The boundary is the
  schema parser, its tests, one compiler test, and the schema documentation.
  Acceptance evidence is schema tests per listed kind and per rejection, the
  line of a rejected statement after comments and a dollar-quoted body, a
  compiler test naming the migration file and line with no output written,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` ignores one spelling of every listed
  statement, including lower-case `ALTER INDEX` (an opaque unsupported
  statement matched by its opening words) and
  `CREATE OR REPLACE FUNCTION ... $$ ... $$ LANGUAGE plpgsql`, without
  resolving it against the schema, and ignores the named and unnamed
  `PRIMARY KEY`, `UNIQUE`, and `FOREIGN KEY` actions, the named `CHECK`
  action, `DROP CONSTRAINT [IF EXISTS]`, and `RENAME CONSTRAINT` while still
  resolving the table and applying a modeled action beside them. A
  `CREATE FUNCTION` is ignored only when a part before its first `;` part is
  dollar-quoted, so a single-quoted body is rejected at its own line even
  when it captures a later `$$` function. The line is derived from the parse
  tree's token chain, so a rejected `CREATE VIEW` after comments containing
  `;` and a multi-line `$$` body reports line 15. Rejected kinds and lines
  cover `CreateView`, a composite `CreateType`, `AlterType`, `CreateDomain`,
  `CreateSchema`, `DROP VIEW` as `Drop`, `PlainSelect`, `SET DEFAULT` as
  `Alter`, `CreateFunction`, and `ALTER FUNCTION` as `UnsupportedStatement`.
  A probe of the built parser confirmed statements after a `$$` function are
  applied or rejected at their own lines, and a tagged `$body$` delimiter
  fails with the documented `Encountered unexpected token: "$body$" at line 1,
  column 74`. Through `SqlcjCompiler`, a `CREATE VIEW` after an ignored
  `CREATE INDEX` fails as `Invalid schema source <V2__views.sql>:
  Unsupported schema statement: CreateView at line 3` with no output written.
  `docs/postgresql.md` documents the ignored list, the three parser
  limitations, and the new diagnostic. `mvn spotless:check` and `mvn test`
  passed (631 CLI and 25 runtime tests, 0 skipped, including 19 PostgreSQL
  tests).
- **M19-W7 — Sample and quickstart from a migration directory —
  Completed.** Implements requirement 7; its M19-W1, M19-W5, and M19-W6
  dependencies are completed. The external sample and the quickstart replace
  their `sql/schema.sql` snapshot with a `sql/migrations` directory named by
  `sql[].schema`: a `V1` migration creates `authors` without `created_at` and
  `books`, and a `V2` migration adds `authors.created_at` with
  `ALTER TABLE ... ADD COLUMN` and creates an index on `books (author_id)`.
  The migrations describe the tables the snapshot did, so the generated
  repository, the sample application's behavior, and the documented output are
  unchanged. `scripts/verify-release.sh` generates from the directory, checks
  that the generated row carries the added column, and applies both migrations
  with `psql` in version order before running the sample. The boundary is the
  sample, the quickstart, and the release script; no compiler behavior,
  migration tool, or other document changes. Acceptance evidence is
  `scripts/verify-release.sh` passing against PostgreSQL 16, a generated
  repository byte-identical to the one the former snapshot generated, the
  quickstart, `mvn spotless:check`, and `mvn test`.
  Review evidence: the sample's `sqlcj.yaml` names `sql/migrations`, whose
  `V1__create_authors_and_books.sql` creates `authors (id, name, bio)` and
  `books`, and whose `V2__add_author_created_at_and_book_index.sql` adds
  `created_at TIMESTAMP` and creates `books_author_id_idx`; no sample file,
  quickstart text, or script line references `sql/schema.sql`. The packaged
  CLI generated the sample from the directory and from the former
  `schema.sql` taken from `HEAD`, and the two outputs are identical and carry
  `LocalDateTime createdAt`. `scripts/verify-release.sh` passed against
  `postgres:16-alpine`, applying `V1` then `V2` with `ON_ERROR_STOP`, and
  the sample printed the documented output; `\d authors` then listed `id`,
  `name`, `bio`, and `created_at` and `books_author_id_idx` existed. The
  quickstart's two migration listings and `sqlcj.yaml` match the sample's
  files, and its two `docker exec ... psql` commands applied both files
  against the same server. `mvn spotless:check` and `mvn test` passed (631
  CLI and 25 runtime tests, 0 skipped, including 19 PostgreSQL tests).
- **M19-W8 — sqlc schemas and migrated-schema execution — Completed.**
  Proves the milestone acceptance as amended by D2: `booktest` loads, `ondeck`
  fails only on its `COMMENT ON TYPE` statement and loads without it, enum and
  array columns are recorded as unsupported, and the migrated schema runs
  against PostgreSQL through generated code. The other acceptance items are
  proven by M19-W1 through M19-W7. The probe recorded in D3 confirmed the
  behavior already holds, so the item is acceptance evidence only: no
  production code or public documentation changes. Per D3, the evidence is
  sqlcj-owned inline fixtures. A single-file schema declares an enum type, an
  enum column, a `varchar[]` column, indexes, and a dollar-quoted function
  followed by another statement, and loads with the enum and array columns
  recorded as unsupported. A migration directory of non-Flyway file names
  creates tables with an enum, an enum-array, and a `text[]` column, states
  `COMMENT ON TYPE`, `TABLE`, and `COLUMN`, and later renames a table, drops a
  column, and adds one; its file fails only on the `COMMENT ON TYPE` statement
  with a located syntax diagnostic and loads once the test removes that
  statement, since sqlcj does not pre-process SQL. Acceptance evidence is
  schema-parser tests of both fixtures, a PostgreSQL test that applies the
  loadable migration files in order and executes generated code over the
  migrated tables from the same directory, `mvn spotless:check`, and
  `mvn -Dsqlcj.test.requireDocker=true test`.
  Review evidence: only `DefaultSchemaParserTest` and
  `PostgresIntegrationTest` changed; no production code, documentation, or
  test resource did. The single-file fixture, with an enum type, a
  `varchar[] NOT NULL DEFAULT '{}'` column, two indexes, and a `$$` function
  followed by a `CREATE INDEX`, parses to `shelves` and `records` with `state`
  recorded as `SHELF_STATE`, `genres` as `VARCHAR[]`, `released_on` as
  `TIMESTAMP_WITH_TIME_ZONE`, and their primary-key and unique constraints.
  After `001`, the second migration file fails with exactly
  `Encountered unexpected token: "TYPE" at line 14, column 12`, the line of
  its `COMMENT ON TYPE`; with that statement removed, the three files applied
  in order leave `region` and the renamed `stage` without `legacy_code` and
  with `opened_at` appended, recording `setting` as `STAGE_SETTING`,
  `past_settings` as `STAGE_SETTING[]`, and `keywords` as `TEXT[]`, after the
  `COMMENT ON TABLE` and `COLUMN` statements are ignored. Against
  PostgreSQL 16, `001_region.sql` to `003_reshape_stage.sql` written to a
  temporary directory are applied in file-name order, and a repository
  generated and compiled from that directory creates a region and reads a
  stage row as `Integer`, `String`, `String`, `String`, and `LocalDateTime`
  with the inserted values. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (635 CLI and 25 runtime
  tests, 0 skipped, including 159 schema-parser and 20 PostgreSQL tests).

#### Required

1. Upgrade JSqlParser from 5.3 to 5.4, which parses `CREATE TYPE ... AS ENUM`,
   `CREATE EXTENSION IF NOT EXISTS`, and `ALTER TYPE ... ADD VALUE`, with every
   existing test unchanged except the parser-node fixture and the nine
   expectations decided in D1. Do not split or pre-process SQL to work around
   either parser version.
2. `sql[].schema` accepts a file, a directory, or a list of files and
   directories, resolved relative to the configuration file as today. A
   directory contributes its `.sql` files, non-recursively, in one documented
   deterministic order that matches Flyway versioned migrations
   (`V1__`, `V1_1__`, `V2__`, `V10__` by version) and is lexical otherwise.
   Flyway undo (`U...__`) files are ignored.
3. Apply statements in order to one schema model. Model `CREATE TABLE`,
   `DROP TABLE`, and the `ALTER TABLE` forms `ADD COLUMN`, `DROP COLUMN`,
   `RENAME COLUMN`, `RENAME TO`, `ALTER COLUMN ... TYPE`, and
   `ALTER COLUMN ... SET/DROP NOT NULL`, including their `IF [NOT] EXISTS`
   variants. A statement that refers to a missing table or column fails.
4. Accept and ignore a documented list of statements that cannot change a
   table's columns, types, or nullability: index statements, `COMMENT ON`
   `TABLE`, `COLUMN`, or `VIEW`,
   `CREATE EXTENSION`, sequence statements, grants, functions and triggers,
   table-constraint `ALTER TABLE` forms, and data statements such as seed
   `INSERT`s. `CREATE TYPE ... AS ENUM` is accepted and ignored until
   Milestone 20 models it. Every other statement is rejected naming the file,
   line, and statement kind.
5. Record an unsupported column type instead of failing the schema. The type
   fails only when an analyzed query reads, binds, or expands that column, with
   a diagnostic naming the query, column, and type.
6. Accept every PostgreSQL spelling of the already supported types, including
   `TIMESTAMPTZ`, `TIMESTAMP WITHOUT TIME ZONE`, `INT2`/`INT4`/`INT8`,
   `SMALLSERIAL`/`SERIAL2`/`SERIAL4`/`SERIAL8`, `CHARACTER VARYING`, and
   `CHAR`/`CHARACTER`, mapped to the existing Java types.
7. The external sample and quickstart read a Flyway-style migration directory
   whose later migration adds an index and alters a table.

#### Out of scope

- executing, ordering against a database, or verifying migrations,
- Liquibase formats, Flyway Java migrations, placeholders, repeatable-migration
  semantics beyond the documented order, down migrations, and `pg_dump` output,
- recursive directory scanning or glob patterns,
- modeling views, enums (Milestone 20), non-default schema namespaces,
  inheritance, or partitions,
- multiple query files per group.

#### Acceptance

- sqlc's `booktest` PostgreSQL schema loads, and its `ondeck` schema fails
  only on its `COMMENT ON TYPE` statement and loads without it, with their enum
  and array columns recorded as unsupported until Milestone 20, proven as D3
  decides.
- A migration directory whose later files rename, add, drop, and alter columns
  produces the same generated repository as the equivalent single snapshot.
- A query that reads an unsupported-type column fails naming the query,
  column, and type, while a query over the same table that does not read it
  compiles.
- Focused schema, configuration, and compiler tests, PostgreSQL execution of
  the migrated schema, the external sample, `mvn spotless:check`, and
  `mvn test` pass.

### Milestone 20 — Shared Models and Everyday PostgreSQL Types

#### Goal

Give the column types that services commonly use a known Java type, and
generate each schema-derived Java type once per package so every repository
shares it.

#### Work items

Reassessed on activation on 2026-09-28 against the implementation at
`7ed5547`, its tests, the external sample and `scripts/verify-release.sh`, the
documentation, the sources of the PostgreSQL JDBC driver 42.7.13 the tests use,
and sqlc's `booktest` files at commit
`bdbe55db3ee08745827090d4aad1781bea2ee4ee`, as Milestone 19's D3 directs. Every
requirement is unimplemented as assumed. Facts that correct or shape the plan:

- Requirement 1: `CodeGenerator.generate` returns one file per entry, and each
  repository nests the `<TableName>Row` of every table whose full row it
  returns, built from that entry's schema; `JavaNames` rejects row types equal
  ignoring case only within one entry. `scripts/verify-release.sh` already
  generates the entries `Author` and `Library` over one migration directory,
  which today yields two unrelated `AuthorsRow` types. The requirement governs
  the record only: each repository keeps its private row mapper, so the shared
  record stays a plain record with no runtime or JDBC dependency. "Define the
  same table differently" is read as two entries that both return the full row
  of a table, named alike ignoring case, whose analyzed row columns differ in
  order, name, type, or nullability. An entry that never returns that full row
  generates no shared model and is not compared.
- Requirement 2: the driver reads `REAL`, `DOUBLE PRECISION`, `BYTEA`, and
  `TIME` as `Float`, `Double`, `byte[]`, and `LocalTime` through the generated
  `getObject(position, Class)` call and binds them through `setObject`, so they
  need only a type mapping. `JSON` and `JSONB` do not: the driver reports them as
  `Types.OTHER`, its `getObject(position, String.class)` throws for every type
  but `CHAR` and `VARCHAR`, and a `String` bound through `setObject` is sent as
  `varchar`, which PostgreSQL does not assign to a `json` or `jsonb` column.
  They need a runtime binding change and a different generated read, so they
  are a separate work item.
- Requirement 3: `CREATE TYPE ... AS ENUM` is ignored and `ALTER TYPE` rejected
  (M19-W6), the schema model holds no types, and `ColumnType` is a closed Java
  enum that cannot name a user-defined type, so an enum column needs a type
  representation carried through schema, analysis, and generation. The driver
  reports an enum column as `VARCHAR`, so its label reads as a `String`, but a
  label bound as `varchar` is rejected for an enum column or comparison; enums
  need the same binding change as `JSON`. `DROP TYPE` and `ALTER TYPE ...
  RENAME` stay rejected as enum removals and renames.
- Requirement 4: binding a list needs `Connection.createArrayOf` with the
  element's server type name, which the runtime's per-value `setObject` cannot
  do, and the driver's `getArray` decodes `date`, `time`, and `timestamptz`
  elements as `java.sql.Date`, `Time`, and `Timestamp`, which must become the
  mapped `java.time` types. This is the largest item.
- Requirement 5 is met by the items that introduce each binding.
  `setObject(position, value, Types.OTHER)` and `createArrayOf` are standard
  JDBC, so neither the runtime nor generated code needs the driver.
- `booktest`: `schema.sql` declares the enum `book_type ('FICTION',
  'NONFICTION')` and the columns `books.book_type book_type` and
  `books.tags varchar[]`, both `NOT NULL`. `GetBook`, `BooksByTitleYear`,
  `CreateBook`, `UpdateBook`, and `UpdateBookISBN` read or bind them.
  `BooksByTags` (`&&`) and `SayHello` (a set-returning function) are outside
  this milestone and belong to Milestone 21's accounting of sqlc's queries.

Owner decision proposed on 2026-09-28 and decided by the owner on 2026-09-29:

- **D1 (M20-W5):** accepted by the owner on 2026-09-29. Requirement 4's
  element types are limited to enums and the mapped scalar types other than
  `BYTEA`, `JSON`, and `JSONB`, whose arrays stay recorded as unsupported.
  `List<byte[]>` and arrays of JSON text are rare in services, and each adds an
  element encoding and tests that the everyday tag and id-list cases, and
  Milestone 21's `= ANY`, do not need. Requirement 4 is amended accordingly.

Owner decision proposed on 2026-09-29 and decided on 2026-09-29:

- **D2 (M20-W6):** decided on 2026-09-29 as proposed, by the orchestrator to
  whom the owner delegated the choice. The one-off probe
  Milestone 19's D3 requires ran sqlc's published `booktest` `schema.sql` and
  `query.sql` at commit `bdbe55db3ee08745827090d4aad1781bea2ee4ee` through the
  sqlcj CLI sources at `c3655a6`, compiled outside the repository. The schema
  loads. The query file fails first on `BooksByTitleYear` with `SQL
  placeholders [1, 2] are not the analyzed parameters [1]; a placeholder is in
  an unsupported location`: JSqlParser 5.4 parses the unqualified `year` of
  `year = $2` as a `DateUnitExpression`, not a column, so the predicate is not
  analyzed. `"year" = $2` and `books.year = $2` compile, `SELECT year` fails as
  `Unsupported SELECT expression: DateUnitExpression`, and `year` as an
  `INSERT` column or `UPDATE` target compiles. Without that query the file
  fails on `BooksByTags` (a placeholder in an unsupported location), then on
  `SayHello` (`Only table sources are supported.`). Without those three, the
  other seven queries generate the enum `BookType` (`FICTION`, `NONFICTION`),
  `BooksRow` with `BookType bookType`, `OffsetDateTime available`, and
  `List<String> tags`, and a repository whose `createBook` binds the enum label
  and a `varchar` array, and the output compiles against the runtime.
  Proposed: M20-W6's `booktest` fixtures reproduce `GetBook`, `CreateBook`,
  `UpdateBook`, and `UpdateBookISBN`, which read the full row and bind both the
  enum and the array column, and `BooksByTitleYear` moves to Milestone 21's
  accounting of sqlc's queries, recorded as unsupported for its unqualified
  `year` until a work item resolves a column named like a date unit. That
  resolution is query analysis, not a type mapping, and the milestone
  acceptance names only `booktest`'s enum and array columns, which the four
  queries cover. The rejected alternative was to add that analysis to M20-W6.
  M20-W6 is amended accordingly, and `BooksByTitleYear` is recorded as
  unsupported for its unqualified `year` in Milestone 21's accounting of sqlc's
  queries.

- **M20-W1 — Shared top-level row records — Completed.** Implements
  requirement 1. Every `<TableName>Row` becomes one top-level `public record`
  file in `java.package`, generated once per table whose full row any entry
  returns, and every repository that returns that row uses it; query-specific
  `<QueryName>Result` records and every row mapper stay nested in their
  repository. A row's name, components, component order, and types are
  unchanged. Two entries whose returned rows share a row type but whose
  analyzed row columns differ fail, and row types equal ignoring case are
  rejected across the package rather than within one entry; both fail before
  output is written, reported against the later entry with the existing query
  group diagnostic prefix. Row files are listed in the manifest and deleted as
  stale once no entry returns them. The boundary is Java generation, the
  compiler's generation step, the external sample, `scripts/verify-release.sh`,
  and the documentation of the generated API, naming, and diagnostics; there is
  no analysis, type-mapping, or runtime change. Acceptance evidence is
  generator and compiler tests, including two entries over one migration
  directory whose compiled repositories return one `AuthorsRow` class, the
  conflict and stale-row cases, PostgreSQL tests, `scripts/verify-release.sh`,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `JavaCodeGenerator` records each group's rows and
  `SqlcjCompiler` appends one `<TableName>Row.java` per recorded table after the
  repositories. The file is built by the former record renderer, imports only
  the JDK types of its components, and no repository nests a row record any
  more; each repository keeps its row mapper. A second group returning a
  recorded row with different analyzed columns, or a row type equal ignoring
  case to another table's, fails under the later entry's
  `Invalid query group` prefix, and nothing is written. Compiler tests compile
  two entries over one migration directory into `AuthorRepository` and
  `LibraryRepository`, whose `getAuthor` methods return the one top-level
  `dev.example.generated.AuthorsRow`. They also list that file in the manifest,
  delete it once no query returns the full row, and cover both cross-entry
  diagnostics. `scripts/verify-release.sh` passed against a throwaway
  PostgreSQL 16 container. It generated `AuthorRepository.java` and
  `AuthorsRow.java`, one `AuthorsRow` shared by `Author` and `Library`, and
  `AuthorsRow.java` beside `WriterRepository.java` after the rename, and the
  sample ran. `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true
  test` passed (643 CLI and 25 runtime tests, 0 skipped, including 20
  PostgreSQL tests).
- **M20-W2 — Floating-point, binary, and time types — Completed.** Implements
  requirement 2 for `REAL`/`FLOAT4` as `Float`, `DOUBLE PRECISION`/`FLOAT8` as
  `Double`, `BYTEA` as `byte[]`, and `TIME`/`TIME WITHOUT TIME ZONE` as
  `java.time.LocalTime`, with "spelling" defined as in M19-W2. `FLOAT` and
  `FLOAT(p)`, whose precision selects the type, `TIMETZ`, and
  `TIME WITH TIME ZONE` stay recorded as unsupported. No runtime change.
  Acceptance evidence is schema-parser tests per spelling, generated-source
  compilation, a PostgreSQL round trip of `null` and non-null values as
  parameters and result components, the type documentation,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` maps `REAL`/`FLOAT4`,
  `DOUBLE PRECISION`/`FLOAT8`, `BYTEA`, and `TIME`/`TIME WITHOUT TIME ZONE` to
  the new `ColumnType` constants, which `DefaultTypeResolver` resolves to
  `Float`, `Double`, `byte[]`, and `LocalTime`; the generator imports
  `java.time.LocalTime` and reads each with `getObject(position, Type.class)`.
  No runtime or analyzer change. Schema-parser tests cover every spelling in
  upper and lower case plus `TIME(3)` and `TIME(3) WITHOUT TIME ZONE`, and
  record `FLOAT`, `float(24)`, `TIMETZ`, `time with time zone`, and `real[]` as
  unsupported. A generated repository with all four types as parameters and
  result components, including a query whose only parameter is `byte[]`,
  compiles without Docker. `PostgresIntegrationTest` writes a non-null and a
  null row over every in-scope spelling through generated code and reads both
  back with the expected component types. `docs/postgresql.md` documents the
  mappings and the unmapped spellings. `mvn spotless:check` and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (674 CLI and 25 runtime
  tests, 0 skipped, including 21 PostgreSQL tests).
- **M20-W3 — JSON text and typed binding — Completed.** Implements
  requirement 2 for `JSON`/`JSONB` as a `String` holding the JSON text, and the
  part of requirement 5 it needs: the runtime binds a JSON value for PostgreSQL
  to type from its context, and generated code reads the text through an API the
  driver supports for these types. The runtime API change is coordinated with
  the generator and proven by compiled generated source; no JSON library is
  added, and sqlcj neither parses nor normalizes the text. `JSON` reads back the
  text as written and `JSONB` as PostgreSQL normalizes it. The equality
  predicate is proven on `JSONB` only, because PostgreSQL defines no `json =
  json` operator. Arrays of either type stay recorded as unsupported, and a
  placeholder index repeated across a JSON and a non-JSON column is not
  specially handled. Acceptance evidence is runtime and generator tests,
  schema-parser tests per spelling, a PostgreSQL round trip of `null` and
  non-null values as an `INSERT` value and a result component for both types
  and as an equality predicate for `JSONB`, the type and runtime
  documentation, `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` maps `JSON` and `JSONB` in any case to
  new `ColumnType` constants through the one type mapping shared by
  `CREATE TABLE`, `ADD COLUMN`, and `ALTER COLUMN TYPE`, and
  `DefaultTypeResolver` resolves both to `String`; `json[]` and `jsonb[]` stay
  recorded as `JSON[]` and `JSONB[]`. The new runtime record
  `dev.sqlcj.runtime.UntypedText(String value)` carries any text, and
  `JdbcQueryExecutor` binds it with `setObject(position, value, Types.OTHER)`
  and every other argument with `setObject(position, value)` as before. The
  generator renders `new dev.sqlcj.runtime.UntypedText(<name>)` at every
  binding position of a JSON placeholder index, keeps `VARCHAR` and `TEXT`
  arguments bare, reads JSON columns with `resultSet.getString(position)`, and
  adds no import; output for other types is unchanged. Runtime tests record
  `setObject` calls for a null and a non-null `UntypedText` beside an ordinary
  argument; generator tests cover a repeated JSON index and compile the
  generated source against the runtime.
  `PostgresIntegrationTest.shouldRoundTripJsonValues` writes and reads null and
  non-null `JSON` and `JSONB` through generated code, reads `JSON` back as
  written and `JSONB` as PostgreSQL normalizes it, finds a row by `JSONB`
  equality for reordered and respaced text, and returns no row for a null
  argument. `docs/postgresql.md` and `docs/queries.md` document the mapping,
  reads, and binding.
  `mvn spotless:check` (on a copy without the Spotless cache: 7 runtime and 68
  CLI files checked) and `mvn -Dsqlcj.test.requireDocker=true test` passed (27
  runtime and 687 CLI tests, 0 skipped, including 22 PostgreSQL tests).
- **M20-W4 — PostgreSQL enums — Completed.** Implements requirement 3 and
  its part of requirement 5. `CREATE TYPE ... AS ENUM` and `ALTER TYPE ...
  ADD VALUE` with its `IF NOT EXISTS`, `BEFORE`, and `AFTER` forms are applied
  in order to the schema; a column of a declared enum type maps to one
  top-level Java enum per enum used by any query of the package, whose
  constants follow documented naming rules and keep their exact labels, and
  which binds through M20-W3's mechanism. Two entries defining one used enum
  differently, and an enum name colliding with another generated type, fail
  before output is written. Every other `ALTER TYPE` action and `DROP TYPE`
  stay rejected, an array of an enum stays recorded as unsupported until
  M20-W5, and the sample and `booktest` fixtures belong to M20-W6. The runtime
  is unchanged, because `UntypedText` already binds a label for PostgreSQL to
  type from its context. The boundary is the schema model and parser,
  analysis, Java generation, the compiler's generation step, and the type,
  DDL, generated-API, and naming documentation. Acceptance evidence is schema,
  analyzer, generator, and compiler tests, a PostgreSQL round trip of `null`
  and non-null enum values as parameters and result components and of the
  label order `BEFORE` and `AFTER` leave, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: `DefaultSchemaParser` adds the new `EnumType` to `Schema`
  on `CREATE TYPE ... AS ENUM` and inserts an `ADD VALUE` label at the end or
  directly before or after its neighbour. `IF NOT EXISTS` with an existing
  label is a no-op checked before the neighbour, and the four type and label
  diagnostics are reported. A non-array column whose type names a declared
  enum, matched case-insensitively, becomes `ColumnType.ENUM` with the enum's
  declared name through the shared column path of `CREATE TABLE`,
  `ADD COLUMN`, and `ALTER COLUMN TYPE`, and a rename or nullability change
  keeps it. `DROP TYPE`, every other `ALTER TYPE` action, composite
  `CREATE TYPE`, enum arrays, and undeclared types stay rejected or recorded as
  unsupported. `QueryAnalyzer` carries the enum name into columns and
  parameters and rejects a repeated `$N` across an enum and another type, or
  across two enums, naming each enum by its PostgreSQL name. `JavaCodeGenerator`
  generates one top-level enum per enum a query uses, with `label()` and
  `fromLabel`, binds `UntypedText` around the constant's label, and reads the
  label with `getString` and `fromLabel`. Two entries defining one used enum
  differently, enums equal ignoring case, an enum colliding with another
  generated type, and a label that cannot name a constant all fail before
  output is written; enum files are listed in the manifest and deleted once
  unused. Compiler tests compile two entries over one migration directory
  whose repositories use one `StageSetting`.
  `PostgresIntegrationTest.shouldRoundTripEnumValues` matches the generated
  labels to `pg_enum` order after `BEFORE`, `AFTER`, and `IF NOT EXISTS`, and
  round trips null and non-null values as `INSERT` values, as an equality
  predicate (a null argument matches no row), and as result components.
  `mvn spotless:check` (also on a copy without the Spotless cache: 7 runtime
  and 69 CLI files checked) and `mvn -Dsqlcj.test.requireDocker=true test`
  passed (27 runtime and 729 CLI tests, 0 skipped, including 23 PostgreSQL
  tests).
- **M20-W5 — One-dimensional arrays — Completed.** Implements requirement 4,
  as D1 decides, and its part of requirement 5: a column declared as a
  one-dimensional array, `type[]` or `type[n]` (PostgreSQL ignores the size),
  of an enum or of a mapped scalar type other than `BYTEA`, `JSON`, and `JSONB`
  maps to `java.util.List<T>` of the element's Java type for parameters and
  result components, and elements may be `null`. The runtime binds a list as a
  server array of the element type through `Connection.createArrayOf` and
  reads each element as a scalar of the element type is read, without
  depending on the driver. Declared multidimensional arrays and arrays of
  `BYTEA`, `JSON`, `JSONB`, or an unmapped element type stay recorded as
  unsupported, and no array operator, including Milestone 21's `= ANY`, is
  added. The boundary is the schema model and parser, analysis, Java
  generation, the runtime's argument and read API, and the type, generated-API,
  and runtime documentation; the sample and `booktest` fixtures belong to
  M20-W6. Acceptance evidence is schema, analyzer, generator, and runtime
  tests, generated-source compilation, a PostgreSQL round trip of `null`,
  empty, and non-empty lists, including a `null` element, for every allowed
  element type, `mvn spotless:check`, and `mvn test`.
  Review evidence: `DefaultSchemaParser` models a column of exactly one array
  dimension of a mapped type other than `BYTEA`, `JSON`, and `JSONB`, or of a
  declared enum, as `Column.array` of its element type through `CREATE TABLE`,
  `ADD COLUMN`, and `ALTER COLUMN TYPE`, keeps it through a rename and a
  nullability change, and records multidimensional, `BYTEA`, `JSON`, `JSONB`,
  and unmapped arrays as before. A `CHAR`/`CHARACTER` column also carries
  `Column.blankPadded`, which the analyzer passes into its parameters.
  `QueryAnalyzer` carries the array shape into selected, wildcard, and
  returning columns and into parameters, rejects a repeated `$N` across an
  array and its element and a `LIKE` pattern on an array column.
  `JavaCodeGenerator` renders `List<T>`, binds
  `new dev.sqlcj.runtime.SqlArray("<element>", p)` with PostgreSQL's element
  name, `bpchar` for a `CHAR`/`CHARACTER` element as the owner decided on
  2026-09-29 and `varchar` for the varying spellings, binds an enum array as
  `SqlArray.of("<enum>", p, E::label)`, reads with `SqlArray.getList`, and
  imports `java.util.List` into a row record with an array component; scalar
  `CHAR` generation is unchanged. The new runtime record
  `dev.sqlcj.runtime.SqlArray` is bound by `JdbcQueryExecutor` through
  `Connection.createArrayOf` and `setArray`, a null list through
  `setNull(position, Types.ARRAY)`, and read through the JDBC `Array` API
  alone. `PostgresIntegrationTest.shouldRoundTripArrayValues` round trips a
  `null`, an empty, and a non-empty list with a `null` element for all 14
  scalar element types, an enum, and `CHAR(3)`, reads `CHAR(3)` elements blank
  padded, and finds the row by a placeholder equality on the `CHAR(3)` array;
  with the `bpchar` branch disabled that query failed with
  `operator does not exist: character[] = character varying[]`.
  `docs/postgresql.md` and `docs/queries.md` document the mapping, binding,
  reads, and tests. `mvn spotless:check` (also on a copy without the Spotless
  cache: 9 runtime and 69 CLI files checked) and
  `mvn -Dsqlcj.test.requireDocker=true test` passed (32 runtime and 744 CLI
  tests, 0 skipped, including 24 PostgreSQL tests).
- **M20-W6 — Sample and `booktest` evidence — Completed.** Implements
  requirement 6 and proves the milestone acceptance: the external sample adds
  an enum, a `JSONB`, and an array column used by its application and checked
  by `scripts/verify-release.sh`, and sqlcj-owned fixtures reproducing
  `booktest`'s schema constructs and, as D2 decides, its queries `GetBook`,
  `CreateBook`, `UpdateBook`, and `UpdateBookISBN` generate typed Java and run
  against PostgreSQL. Per Milestone 19's D3, the one-off probe of the published
  `booktest` files is the one recorded in D2. The boundary is the sample, the
  release script, and test fixtures; there is no production, runtime, or
  public documentation change. Acceptance evidence is the sample and
  `scripts/verify-release.sh`, the fixtures in a PostgreSQL test, the probe
  record, `mvn spotless:check`, and `mvn test`.
  Review evidence: the sample's new migration
  `V3__add_book_format_tags_and_details.sql` declares the enum `book_format`
  and adds the nullable columns `books.format book_format`, `books.tags
  TEXT[]`, and `books.details JSONB`. Its new queries `CreateCatalogedBook`
  and `ListBooksByFormat` generate `BooksRow` with `BookFormat format`,
  `List<String> tags`, and `String details`, and the top-level enum
  `BookFormat`; the application inserts a cataloged book and reads it back by
  a `BookFormat` parameter, checking the enum, the tag list, and the `JSONB`
  text as PostgreSQL normalizes it. `scripts/verify-release.sh` drops
  `book_format` in its reset, applies `V3` after `V2`, and expects one shared
  `BookFormat.java` for the entries `Author` and `Library`, four generated
  sources and a four-file manifest after the `Writer` rename, four sources
  from the sample, and both new methods. It passed against a throwaway
  `postgres:16-alpine` container, listing `AuthorRepository.java`,
  `AuthorsRow.java`, `BookFormat.java`, and `BooksRow.java`, and the sample
  printed `cataloged book: 2 HARDCOVER [compilers, history] {"pages": 320}`;
  the reset and three migrations also reapplied cleanly over the populated
  database. `PostgresIntegrationTest` reproduces `booktest`'s constructs with
  original names (`SERIAL` keys, an inline reference, a unique defaulted
  `text`, an enum column with a label default, an integer `year`, a
  `timestamptz`, a `varchar[]` defaulting to `'{}'`, single- and two-column
  indexes, and a dollar-quoted `plpgsql` function followed by an index) and
  the four D2 query shapes.
  `shouldExecuteGeneratedCatalogQueriesOverBooktestSchemaConstructs`
  generates the enum `AlbumKind` with labels in `pg_enum` order and a full row
  of `Integer`, `Integer`, `String`, `AlbumKind`, `String`, `Integer`,
  `OffsetDateTime`, and `List` components, binds the seven-column insert,
  exposes `UpdateAlbumCatalogNo`'s parameters in placeholder order around the
  `$3` key, and round trips the enum, the tag list, and the timestamp through
  both updates. No production, runtime, or documentation file changed.
  `mvn spotless:check` and `mvn -Dsqlcj.test.requireDocker=true test` passed
  (32 runtime and 777 CLI tests, 0 skipped, including 25 PostgreSQL tests).

#### Required

1. Generate each `<TableName>Row` record as a top-level type in `java.package`,
   once per table, shared by every repository that returns that full row.
   Query-specific `<QueryName>Result` records stay nested in their repository.
   Two entries whose schemas define the same table differently, or whose model
   names collide, fail before output is written. Shared models are listed in
   the output manifest.
2. Map `REAL` to `Float`, `DOUBLE PRECISION` to `Double`, `BYTEA` to `byte[]`,
   `TIME` to `java.time.LocalTime`, and `JSON`/`JSONB` to `String` holding the
   JSON text. sqlcj adds no JSON library dependency.
3. Model `CREATE TYPE ... AS ENUM` and `ALTER TYPE ... ADD VALUE`, and generate
   one top-level Java enum per enum type used by a query, whose constants follow
   the documented naming rules and keep their exact PostgreSQL labels. A column
   of that type maps to the enum.
4. Map one-dimensional arrays of supported scalar or enum element types, other
   than `BYTEA`, `JSON`, and `JSONB` (D1), to `java.util.List<T>` for
   parameters and result components.
5. The runtime binds and reads enums, `JSON`/`JSONB`, and arrays correctly
   against PostgreSQL. Neither generated code nor the runtime depends on the
   PostgreSQL JDBC driver; any runtime API change is coordinated with the
   generator and verified with compiled generated source.
6. The external sample adds an enum, a `JSONB`, and an array column.

#### Out of scope

- type overrides, custom codecs, or mapping `JSONB` to a JSON library type,
- domains, composites, ranges, `INTERVAL`, `MONEY`, network, geometric, or
  `XML` types, and multidimensional arrays,
- primitive Java types, nullness annotations, or `Optional` components,
- enum renames or removals, and array operators other than Milestone 21's
  `= ANY`,
- structural deduplication of query-specific result records.

#### Acceptance

- Two repositories over one migration directory return the same top-level
  `AuthorsRow` and the same generated enum.
- Every new type round-trips `null` and non-null values as a parameter and as
  a result component against PostgreSQL through generated code.
- sqlc's `booktest` schema generates its enum and array columns as typed Java,
  proven as Milestone 19's D3 decides.
- Focused schema, analyzer, generator, runtime, and PostgreSQL tests,
  generated-source compilation, the external sample, `mvn spotless:check`, and
  `mvn test` pass.

### Milestone 21 — Everyday SQL Expressions

#### Goal

Let the common write and filter idioms of a CRUD service compile as written,
with one principle for types sqlcj cannot infer: the SQL states them with a
cast.

Carried from Milestone 20's D2 for reassessment on activation: sqlc's
`booktest` query `BooksByTitleYear` is recorded as unsupported, because
JSqlParser 5.4 parses its unqualified column `year` as a date unit rather than
a column.

#### Work items

Reassessed on activation on 2026-09-29 against the implementation at
`9ade6ca`, its tests, the external sample, `docs/queries.md`, JSqlParser 5.4
(the version in `pom.xml`) run on every form below, and sqlc's `authors`,
`booktest`, `jets`, and `ondeck` PostgreSQL query files at commit
`bdbe55db3ee08745827090d4aad1781bea2ee4ee`, as Milestone 19's D3 directs. All
six requirements are unimplemented as assumed, and 5.4 parses every form they
name. Facts that correct or shape the plan:

- Requirement 1: `QueryAnalyzer` rejects every named parameter as
  `Named parameter ':x' is not supported`, and `SqlParameterCompiler` compiles
  only `$N` tokens. 5.4 lexes `:name` as a `:` token followed by a name token
  whose kind depends on the word (`:limit`, `:user`, `:from`, `:type`, and
  `:year` are keyword tokens), and it also parses `:a.b`, `:"quoted"`, and
  `&name` as named parameters, so the accepted name form must be stated rather
  than taken from the parser. A bare named placeholder carries a parse-tree
  node; inside a cast only the cast does.
- Requirement 2: an `INSERT` value must be a placeholder (`INSERT values must be
  indexed placeholders.`) and an `UPDATE` assignment a single placeholder. 5.4
  parses `DEFAULT`, literals, `now()`, and `version + 1` in both.
- Requirement 3: `$1::text`, `:name::text`, `CAST($1 AS bigint)`, and
  `$1::bigint[]` each parse as one cast around the placeholder, which analysis
  never visits, so the query fails the placeholder accounting. "Wherever it
  appears" is read as anywhere inside an expression of a clause whose
  placeholders are analyzed: `WHERE`, `INSERT` values, `UPDATE` assignments, and
  requirement 5's `DO UPDATE` assignments. A placeholder in a projection,
  `ORDER BY`, `GROUP BY`, `HAVING`, or a join condition stays rejected. "A
  supported type" is read as any type a column may be declared with and mapped,
  including declared enums and one-dimensional arrays, so `booktest`'s
  `BooksByTags` (`tags && $1::varchar[]`) compiles through this requirement
  without an array-operator item.
- Requirement 4: `COUNT(*)` must be the only `SELECT` item and aliased, and every
  other function or cast projection fails as `Unsupported SELECT expression`.
  `GROUP BY` and `HAVING` are not analyzed and pass as written, which stays so.
- Requirement 5: `ON CONFLICT` is rejected only on a returning insert. On an
  `:exec` insert the clause is not analyzed, so `ON CONFLICT ON CONSTRAINT`, a
  conflict-target predicate, and a `DO UPDATE` that binds no placeholder compile
  today and reach the database unchecked; `docs/queries.md` lists this as not
  analyzed rather than supported. The requirement therefore also rejects the
  out-of-scope forms on `:exec`. 5.4 parses every in-scope form, with
  `EXCLUDED.name` as a column qualified by `EXCLUDED`. The requirement names a
  conflict target, so a target-less `ON CONFLICT DO NOTHING` is rejected.
- Requirement 6: 5.4 parses `id = ANY($1)` as an equality whose right side is a
  function named `ANY` with one argument, not as an `ANY` comparison
  expression, and the placeholder is unaccounted today. M20-W5 supplies the list
  binding, so the dependency on Milestone 20 is met, with the element types
  Milestone 20's D1 allows.
- Order: requirement 1 comes first because it establishes the placeholder form
  every later item accepts; landed later, it would reopen each of their
  locations. Requirement 3's optional filter needs requirement 1, and its
  `COALESCE(:bio::text, bio)` assignment needs requirement 2. Requirement 4's
  cast projection reuses requirement 3's cast-type rule, requirement 5 needs
  requirement 2 for `EXCLUDED` and non-binding assignments, and requirement 6
  depends only on requirement 1.
- sqlc examples: the published query files, run one query at a time through the
  sqlcj CLI packaged from `9ade6ca`, with `ondeck`'s schema loaded without its
  `COMMENT ON TYPE` statement as Milestone 19's D2 decides, compile 21 of 27
  queries: all four of `authors`, seven of `booktest`, `jets`' `ListPilots` and
  `DeletePilot`, and eight of `ondeck`. `ondeck`'s `CreateVenue` fails on
  `NOW()` in `VALUES` (requirement 2) and `booktest`'s `BooksByTags` on its cast
  placeholder (requirement 3). Four remain outside the milestone and are to be
  recorded as unsupported: `BooksByTitleYear`, carried from Milestone 20's D2,
  because 5.4 parses its unqualified `year` as a date unit (`books.year` and
  `"year"` compile); `SayHello`, which reads a set-returning function; and
  `jets`' `CountPilots` and `ondeck`'s `VenueCountByCity`, whose `COUNT(*)` has
  no alias, which Milestone 17 requires and requirement 4 keeps. Resolving a
  column named like a date unit is not required by this milestone and is not
  planned.

- **M21-W1 — Named parameters — Completed.** Implements requirement 1. A
  query may write `:name` placeholders, where the name directly follows the
  colon and is an unquoted identifier of ASCII letters, digits, and underscores
  that does not start with a digit; names are compared as written. Each
  distinct name is one parameter, ordered by its first textual occurrence and
  typed as a `$N` placeholder in the same location is, whose method parameter
  is named after the placeholder by the existing naming rules, including a
  `LIMIT` or `OFFSET` placeholder; every occurrence becomes a JDBC `?` bound to
  that parameter. A named placeholder is accepted wherever `$N` is accepted
  today. A query mixing `$N` and `:name`, a qualified, quoted, or `&name`
  placeholder, one name with conflicting types, and a named placeholder in an
  unanalyzed location are rejected with diagnostics that write the placeholder
  as `:name`. `$N` queries and their output are unchanged. The boundary is SQL
  parameter compilation, analysis, and the query documentation; the analyzed
  model keeps logical indexes, so generation and the runtime do not change.
  Acceptance evidence is parameter-compiler and analyzer tests, the existing
  named-placeholder rejection tests turned into acceptance or updated
  rejections, a compiled generated repository, PostgreSQL execution of named
  queries that repeat a name and order parameters by first occurrence,
  `mvn spotless:check`, and `mvn test`.
  Review evidence: `SqlParameterCompiler` replaces a `:` token directly
  followed by a name matching `[A-Za-z_][A-Za-z0-9_]*` with `?`, numbers each
  distinct name by first occurrence, leaves `: x` and placeholder text in
  literals and comments uncompiled, and reports every parse-tree `:a.b`,
  `:"x"`, or `&x` it did not compile. `QueryAnalyzer` resolves named
  placeholders at every `$N` location (comparison on either side, `IN`,
  `LIKE`/`ILIKE` pattern, `BETWEEN` bounds, `INSERT` values, `UPDATE`
  assignments, `LIMIT`, `OFFSET` in either order), names the parameter after
  the placeholder, and rejects mixed forms, conflicting types, unanalyzed
  locations, and `:a.b`, `:"x"`, and `&x` anywhere in the statement, including
  `ORDER BY` and function arguments, writing the placeholder as spelled. A
  standalone analyzer probe confirmed every acceptance query, and 21 `$N`-only
  queries produced models and messages identical to the pre-change analyzer.
  `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 805 tests with 0 failures,
  0 errors, and 0 skipped, including 26 PostgreSQL integration tests.
- **M21-W2 — Non-binding write expressions — Completed.** Implements
  requirement 2. An `INSERT` value or `UPDATE` assignment that contains no
  placeholder, such as `DEFAULT`, a literal, `now()`, or `version + 1`, is
  accepted, contributes no parameter, and reaches the database as written. Its
  target column must exist; nothing binds it, so its type need not be mapped.
  Placeholders beside it keep their `$N` or `:name` numbering and textual
  binding order. A value that contains a placeholder without being one stays
  rejected until M21-W3, as a placeholder in an unanalyzed location. A
  multi-column `SET (a, b) = ...` assignment, multi-row `VALUES`, and
  `INSERT ... SELECT` stay rejected. The boundary is write analysis and the
  query documentation; SQL parameter compilation, generation, and the runtime
  do not change. Acceptance evidence is analyzer tests, PostgreSQL execution of
  each listed form, the write documentation, `mvn spotless:check`, and
  `mvn test`.
  Review evidence: `QueryAnalyzer` accepts an `INSERT` value or single-column
  `UPDATE` assignment that is not a placeholder after resolving only its target
  column, which must exist in the written table and need not have a mapped
  type; placeholders still resolve through the existing parameter path, so
  `VALUES ($2, now(), $1)` keeps parameters `[id, version]` with binding
  `[2, 1]`, named placeholders number by first occurrence, and assignment
  placeholders bind before `WHERE` placeholders. `$2 || 'x'` and
  `:n || name` fail the placeholder accounting, `SET (a, b) = ...` fails as
  `UPDATE assignments must set one column at a time.`, and multi-row `VALUES`
  stays rejected. `docs/queries.md` documents the accepted values and the
  updated rejections. `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 820 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 27 PostgreSQL integration
  tests, one of which executes `DEFAULT`, a literal, `NULL`, `now()`, and
  `code + 1` in an `:exec` insert, a returning named insert, and an `:exec`
  update.
- **M21-W3 — Cast-typed placeholders — Completed.** Implements requirement 3
  as read above; depends on M21-W1 and M21-W2. A placeholder that is the direct
  operand of `::type` or `CAST(... AS type)` anywhere inside an analyzed clause
  takes the cast's type, resolved by the schema's column type mapping; an
  unmapped cast type fails naming the placeholder and type, and a repeated
  placeholder whose occurrences differ in type fails as today. A named
  placeholder keeps its own name. A cast `$N` that is the direct value of a
  location that names an uncast `$N` today — a compared column, the tested
  column of `IN`, `BETWEEN`, or `LIKE`, or an inserted or assigned column —
  keeps that column's name, so `name = $1::text` names `name` as `name = $1`
  does; every other cast `$N` is named `param<N>`, and colliding names take the
  existing numeric suffix. Decided by the owner on 2026-10-01. Reassessed on
  2026-10-01: 5.4 gives a `::` operand no parse-tree node, so `:"x"::int` and
  `&x::int` are accepted today with the placeholder left in the executable SQL;
  this item rejects a qualified, quoted, or `&name` cast operand anywhere in the
  statement, as M21-W1 rejects the bare forms. The boundary is SQL parameter
  compilation, analysis, a column type mapping shared with the schema parser,
  and the query documentation; generation and the runtime do not change.
  Acceptance evidence is analyzer tests of `(:name::text IS NULL OR name = :name)`,
  `'%' || :term::text || '%'`, `COALESCE(:bio::text, bio)`, the `CAST` form, an
  enum and an array cast, the naming rule, and the rejections, PostgreSQL
  execution of the optional filter with null and non-null arguments, the
  documentation, `mvn spotless:check`, and `mvn test`.
  Review evidence: `QueryAnalyzer` types a placeholder that is the direct
  operand of `::type` or `CAST(... AS type)` anywhere inside `WHERE`, `INSERT`
  values, and `UPDATE` assignments through `ColumnTypeMapping`, the mapping now
  shared with `DefaultSchemaParser`, including declared enums, `char(n)`, and
  one-dimensional arrays; an unmapped cast fails as
  `Placeholder $1 has unsupported cast type INTERVAL`. A cast `$N` keeps a
  column's name only where an uncast `$N` would be named after it, so
  `name = $1::text`, `name IN ($1::text, 'a')`, `name BETWEEN $1::text AND 'z'`,
  `name LIKE $1::text`, and `name ILIKE $1::text` name `name`, while
  `name SIMILAR TO $1::text`, `name NOT LIKE $1::text`,
  `name LIKE BINARY $1::text`, `name LIKE $1::text ESCAPE '!'`, and
  `tags && $1::varchar[]` name `param1` with the cast's type and binding `[1]`;
  `name NOT LIKE :p::text` names `p`. The uncast `LIKE` restrictions keep their
  exact messages. A cast in an unanalyzed location such as
  `ORDER BY $2::int` and the `&x`, `:a.b`, and `:"x"` cast operands are
  rejected, and `SqlParameterCompiler` reports uncompiled cast operands. `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 854 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 28 PostgreSQL integration
  tests, one of which executes `(:name::text IS NULL OR name = :name)` with
  null and non-null arguments.
- **M21-W4 — Grouped and computed projections — Completed.** Implements
  requirement 4; depends on M21-W3. An aliased `COUNT(*)` beside direct columns
  is a non-null `BIGINT` component in its position, and
  `<expression>::type AS alias` is a nullable component of the cast type. An
  unaliased count or cast, any other projected expression, and a placeholder in
  a projection stay rejected. Acceptance evidence is analyzer and generator
  tests, PostgreSQL execution of a grouped count and a cast `SUM`, the
  documentation, `mvn spotless:check`, and `mvn test`.
  Reassessed on 2026-10-03 against `e110322`: `QueryAnalyzer.resolveColumns`
  rejects a count beside any other item as
  `COUNT(*) must be the only SELECT item.` and every cast projection as
  `Unsupported SELECT expression: CastExpression`. 5.4 parses
  `SUM(amount)::numeric AS total` and `CAST(SUM(amount) AS numeric) AS total`
  as one `CastExpression` with the alias on the item, so, as M21-W3 reads
  requirement 3, both spellings are the cast and take the type
  `ColumnTypeMapping` maps, including declared enums and one-dimensional
  arrays; an unmapped cast type fails naming the alias and the type. The cast
  operand is not analyzed and reaches the database as written, as an M21-W2
  write value does, so PostgreSQL rather than sqlcj checks its column
  references; a placeholder inside it, including a cast placeholder such as
  `$1::int AS x`, stays rejected by the placeholder accounting. Removing the
  sole-item rule also lets a count stand beside a wildcard or a cast. `GROUP BY`
  and `HAVING` stay unanalyzed, and `RETURNING` is unchanged. The generator
  already reads every result column by position as its mapped type,
  including enums and arrays, so the boundary is `SELECT` projection analysis
  and the query documentation; generation and the runtime do not change, and
  the generator evidence is compiled generated records.
  Review evidence: `QueryAnalyzer.resolveColumns` no longer applies the
  sole-item rule, so `SELECT name, COUNT(*) AS n FROM users GROUP BY name`
  resolves `[name VARCHAR nullable, n BIGINT non-null]` with no row table, a
  count written first keeps its position, and a grouped count keeps its
  `WHERE` parameter and binding. `SUM(id)::numeric AS total`,
  `CAST(SUM(id) AS numeric) AS total`, and `SUM(id)::numeric total` each
  resolve a nullable `DECIMAL` column `total`; an enum cast resolves `ENUM`
  with its enum type and `::text[]` an array column. An unaliased cast fails as
  `A cast projection requires a result alias, such as SUM(amount)::numeric AS total.`,
  `id::interval AS i` as `Result column 'i' has unsupported cast type INTERVAL`,
  `$1::int AS x` and `:n::int AS x` by the placeholder accounting, and an
  unaliased count and `lower(name) AS n` with their unchanged messages.
  PostgreSQL executes a generated `:many` grouped count with `Boolean` and
  `Long` components and a `:one` `SUM(balance)::numeric AS total` whose
  `BigDecimal` component is the sum and `null` when no row matches.
  `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 868 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 30 PostgreSQL integration
  tests.
- **M21-W5 — Upsert — Completed.** Implements requirement 5; depends on
  M21-W2.
  `ON CONFLICT (columns) DO NOTHING` and `ON CONFLICT (columns) DO UPDATE SET`
  assignments of `EXCLUDED.column`, a placeholder, or a non-binding expression
  are accepted on `:exec` and returning inserts. Target, assigned, and
  `EXCLUDED` columns resolve against the inserted table, and a `DO UPDATE`
  placeholder is typed from its assigned column, or its cast, and bound after
  the `VALUES` placeholders in textual order. Every other conflict form is
  rejected on every insert, as read above. Acceptance evidence is analyzer
  tests, PostgreSQL execution of both actions with and without `RETURNING`
  over new and conflicting rows, the documentation, `mvn spotless:check`, and
  `mvn test`.
  Reassessed on 2026-10-03 against `36d4028`: a returning insert rejects every
  conflict clause as `INSERT ... ON CONFLICT is not supported.`, while an
  `:exec` insert never reads it, so a target-less, `ON CONSTRAINT`, or
  predicated conflict, an unknown target column, and an unknown `EXCLUDED`
  column compile, and a `DO UPDATE` placeholder fails the placeholder
  accounting. 5.4 represents the target as index elements, each a possibly
  quoted column name or an expression with an optional collation or operator
  class, plus a target predicate or a constraint name, and the action as
  `DO NOTHING` or `DO UPDATE` with `SET` assignments and an optional `WHERE`;
  `EXCLUDED.name` is a column qualified by `EXCLUDED`. Every other conflict
  form therefore also covers an expression, collated, or operator-class target
  element and `DO UPDATE ... WHERE`. A `DO UPDATE` assignment is analyzed as an
  `UPDATE` assignment is: one column at a time, a placeholder typed and named
  after its column, a cast placeholder anywhere inside it typed by its cast,
  and any other value reaching the database as written. Only a value that is
  exactly `EXCLUDED.column` has that column resolved, as M21-W2 leaves the
  column references of a non-binding expression to PostgreSQL, which also
  checks that the target matches a unique index. The boundary is insert
  analysis and the query documentation; SQL parameter compilation,
  generation, and the runtime do not change.
  Review evidence: a returning insert no longer rejects every conflict clause,
  and `QueryAnalyzer.analyzeInsert` resolves the conflict clause after the
  `VALUES` row on `:exec` and returning inserts alike. On the write schema,
  `ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, active = $3,
  version = users.version + 1, updated_at = now()` resolves
  `[id BIGINT, name VARCHAR, active BOOLEAN]` with binding `[1, 2, 3]` and the
  clause kept as written, on `:exec` and with `RETURNING id` on `:one`; the
  named form with `name = :name, bio = COALESCE(:bio::text, bio)` resolves
  `[id, name, bio]` with binding `[1, 2, 2, 3]`; `DO NOTHING` keeps the
  `VALUES` parameters, a quoted target column resolves, and `RETURNING *` on
  `:optional` resolves row table `users`. An absent target, `ON CONSTRAINT`, and
  an expression, collated, or operator-class element fail as
  `ON CONFLICT requires a target of column names, such as ON CONFLICT (id).`, a
  target predicate and `DO UPDATE ... WHERE` with their own messages, and
  `SET (name, active) = ...` as an `UPDATE` assignment, each on `:exec` and on a
  returning insert; `version = version + $3` fails the placeholder accounting;
  and an unknown target, assigned, or `EXCLUDED` column fails as
  `Column not found in table users: missing`. PostgreSQL executes generated
  `DO NOTHING` and `DO UPDATE` upserts, each with and without `RETURNING`, over
  a new and then a conflicting row, including an empty `Optional` from
  `DO NOTHING ... RETURNING` on conflict. `mvn -o -q spotless:check` passed and
  `mvn -o -Dsqlcj.test.requireDocker=true test` ran 886 `sqlcj-cli` tests with
  0 failures, 0 errors, and 0 skipped, including 31 PostgreSQL integration
  tests, and 32 `sqlcj-runtime` tests with 0 failures, 0 errors, and 0
  skipped.
- **M21-W6 — Id lists — Completed.** Implements requirement 6.
  `column = ANY(p)`, where `p` is one placeholder, binds a `List<T>` of the
  column's element type through M20-W5's array binding, for a non-array column
  of an enum or a mapped scalar type other than `BYTEA`, `JSON`, and `JSONB`.
  `ANY` over an array column or any other operand, and other operators with
  `ANY`, stay rejected. Acceptance evidence is analyzer and generator tests,
  PostgreSQL execution with empty, one-element, and several-element lists, the
  documentation, `mvn spotless:check`, and `mvn test`.
  Reassessed on 2026-10-03 against `ce5416f`: 5.4 parses `id = ANY($1)`,
  `id = any($1)`, and `id = ANY(:ids)` as an `EqualsTo` whose right side is a
  function with the single-part name `ANY`, as written, and one placeholder
  argument, which no clause accounts, so each fails as
  `SQL placeholders [1] are not the analyzed parameters []`. A cast argument,
  `ANY($1::bigint[])`, `ANY(CAST($1 AS bigint[]))`, or `ANY(:ids::bigint[])`,
  already compiles through M21-W3 as a function-argument cast typed by its cast
  and named `param1` or after its name; so does a scalar cast such as
  `ANY($1::bigint)`, which PostgreSQL rejects when executed. `<> ANY($1)`,
  `= SOME($1)`, `= ALL($1)`, `"ANY"($1)`, `pg_catalog.any($1)`,
  `ANY(ARRAY[$1, $2])`, and `ANY($1) = id`, which PostgreSQL does not accept,
  fail the same accounting; `= ANY('{1,2}')` and `= ANY(SELECT ...)` bind no
  placeholder and pass unanalyzed. M20-W5 already generates `List<T>` and an
  `SqlArray` binding, `bpchar` for a `CHAR` element and `SqlArray.of` for an
  enum, for any parameter marked as an array of the column's type, enum, and
  blank padding, and binds a null list as SQL `NULL`; the generator has no
  element name for `BYTEA`, `JSON`, or `JSONB`, which the array rule of
  `ColumnTypeMapping` already excludes. The boundary is therefore `WHERE`
  analysis of `SELECT`, `UPDATE`, and `DELETE` and the query documentation;
  SQL parameter compilation, generation, and the runtime do not change. The
  uncast placeholder is named after the compared column, as `id = $1` names
  `id`, and a named placeholder keeps its name. By M21-W3's naming rule, a cast
  that is the direct argument of `ANY` in this shape now keeps the column's
  name too, so `id = ANY($1::bigint[])` names `id` rather than `param1`, while
  its cast still states its type; decided by the owner on 2026-10-03. A null
  and an empty list match no row.
  Review evidence: `QueryAnalyzer.resolveParameterComparison` binds the single
  placeholder argument of an unquoted, unqualified `ANY` on the right of `=`
  against a direct column as one list parameter of that column's type, enum
  type, and blank padding, and `ColumnTypeMapping.isArrayElementType` is shared
  rather than duplicated. `id = ANY($1)`, `id = any($1)`, and
  `u.id = ANY($1)` resolve `[id BIGINT array]` with binding `[1]` and
  `id = ANY(?)` as executable SQL; `id = ANY(:ids) OR parent_id = ANY(:ids)`
  resolves one `ids` with binding `[1, 1]`; an enum column carries its enum
  type and a blank-padded column `blankPadded`; `DELETE ... WHERE id = ANY($1)`
  and `UPDATE users SET name = $1 WHERE id = ANY($2)` resolve `[id]` and
  `[name, id]` with bindings `[1]` and `[1, 2]`. `ANY($1::bigint[])` and its
  `CAST` form name `id`, `ANY(:ids::bigint[])` names `ids`, and
  `id <> ANY($1::bigint[])` and `tags && $1::varchar[]` still name `param1`.
  Array, `BYTEA`, `JSON`, and `JSONB` columns fail with the `= ANY` message,
  and an unmapped column first with its unsupported-type message;
  `id <> ANY($1)`, `= SOME($1)`, `= ALL($1)`, `ANY($1) = id`, `"ANY"($1)`,
  `pg_catalog.any($1)`, `ANY($1, $2)`, `ANY(ARRAY[$1, $2])`,
  `ANY(DISTINCT $1)`, `ANY($1 ORDER BY id)`, and `NOT (id = ANY($1))` still fail
  the placeholder accounting; `= ANY('{1,2}')` and `= ANY(SELECT ...)` still
  pass unanalyzed; and `id = ANY(:ids) OR id = :ids` fails as
  `Placeholder :ids has conflicting types: List<Long> and Long`. A generated
  repository compiles with `List<Long> id` bound as
  `new dev.sqlcj.runtime.SqlArray("int8", id)`, and PostgreSQL returns no row
  for a null and an empty id list, one row for one id, the matching rows for
  several, and the matching rows of an enum-column list. `mvn spotless:check`
  passed and `mvn -o -Dsqlcj.test.requireDocker=true test` ran 912
  `sqlcj-cli` tests with 0 failures, 0 errors, and 0 skipped, including 33
  PostgreSQL integration tests, and 32 `sqlcj-runtime` tests with 0 failures,
  0 errors, and 0 skipped.
- **M21-W7 — Sample and sqlcj-owned fixture evidence — Completed.** Proves
  the milestone acceptance as amended on 2026-10-04; depends on M21-W1 through
  M21-W6. The external sample adds an `updated_at` column and queries for a
  named-parameter optional filter, an `updated_at = now()` update, a grouped
  count, an upsert, and an id-list read, used by its application and checked
  by `scripts/verify-release.sh`. Per Milestone 19's D3 as amended on
  2026-10-04, sqlcj-owned PostgreSQL fixtures of original SQL reproduce the two
  constructs of sqlc's example queries that this milestone makes compile, as
  recorded in the activation reassessment above: `now()` among `VALUES`
  placeholders of a returning insert, and a cast array placeholder compared
  with `&&` in a query over a `LEFT JOIN`. Nothing is fetched, cloned, copied,
  or run from the sqlc repository. The constructs that stay outside the
  milestone are recorded as unsupported in that reassessment with their
  reasons and need no new test. There is no production change. Acceptance
  evidence is the sample and `scripts/verify-release.sh`, the fixtures in a
  PostgreSQL test, `mvn spotless:check`, and `mvn test`.
  Reassessed on 2026-10-04 against `7087c48`: M21-W1 through M21-W6 are merged
  (#55 through #60) with their analyzer, compiler, and PostgreSQL tests and
  query documentation, and the sample's migrations stop at `V3`, with no
  `updated_at` column and none of the five query forms. The column is added to
  `authors` by a new `V4` migration, as a nullable `TIMESTAMP` like
  `created_at`, so the shared `AuthorsRow` gains it and each new query result
  is a nested record or that row, and `scripts/verify-release.sh` keeps its
  four generated sources and manifest. The sample's upsert targets
  `authors (id)`, the only unique column, and is exercised over an existing
  author; its insert path is already proven by M21-W5's PostgreSQL test. The
  two fixture constructs extend the existing migration-directory test, with an
  insert into `stage` that writes `now()` between placeholders and binds an
  enum, an enum array, and a `text[]` value before `RETURNING id`, and the
  existing enum-and-array catalog test, with a read of unqualified columns
  over `albums LEFT JOIN studios` filtered by `tags && $1::varchar[]`. The
  unsupported constructs are an unqualified column named like a date unit, a
  function as the `FROM` source, and an unaliased `COUNT(*)`; the last already
  has analyzer tests of its diagnostic, and a test pinning either of the other
  two failures would freeze a limitation rather than prove a requirement.
  Review evidence: a `V4` migration adds the nullable `updated_at TIMESTAMP`
  to `authors`, so the shared `AuthorsRow` gains `LocalDateTime updatedAt`, and
  the sample adds `FilterAuthors`, `RenameAuthor`, `CountBooksByAuthor`,
  `UpsertAuthor`, and `ListAuthorsByIds`. The generated repository binds
  `filterAuthors(String name)` as `Arrays.asList(name, name)` for
  `(?::text IS NULL OR name = ?)`, `renameAuthor(String name, Long id)` and
  `upsertAuthor(Long id, String name, String bio)` with `now()` left in the
  SQL, `listAuthorsByIds(List<Long> ids)` as one `SqlArray("int8", ids)`, and
  `countBooksByAuthor()` into `CountBooksByAuthorResult(Long authorId,
  Long books)`. The application checks a null and a named filter, two ids and
  an empty list, the grouped count, a rename that sets `updated_at`, and an
  upsert over the existing author, and `scripts/verify-release.sh` applies
  `V4`, checks the five methods and `LocalDateTime updatedAt`, and keeps its
  four-source and manifest checks. In `PostgresIntegrationTest`,
  `ListAlbumsByTags` reads unqualified columns over `albums LEFT JOIN studios`
  with one `List` parameter for `tags && $1::varchar[]`, returning the album
  with its studio name for an overlapping list and nothing otherwise, and
  `CreateStage` writes `NOW()` among `VALUES` placeholders, binding
  `String, String, String, StageSetting, List, List` and returning the id, with
  the stored enum, enum array, `text[]`, and timestamp read back.
  `scripts/verify-release.sh` against a throwaway `postgres:16-alpine` exited 0
  with `sqlcj sample verification passed`; the two extended PostgreSQL tests
  passed; `mvn spotless:check` passed; and
  `mvn -Dsqlcj.test.requireDocker=true test` ran 912 `sqlcj-cli` tests and 32
  `sqlcj-runtime` tests with 0 failures, 0 errors, and 0 skipped.

#### Required

Implement these as separate reviewed work items so one does not silently widen
another:

1. Named parameters: a query may use `:name` placeholders instead of `$N`, but
   not both. Each distinct name is one method parameter named after it, in
   order of first textual occurrence, bound at every occurrence. Existing `$N`
   behavior is unchanged.
2. Non-binding write expressions: an `INSERT` value or `UPDATE` assignment that
   binds no placeholder, such as `DEFAULT`, a literal, `now()`, or
   `version + 1`, is accepted and reaches the database as written.
3. Cast-typed placeholders: a placeholder written as `$1::type`, `:name::type`,
   or `CAST(... AS type)` for a supported type is typed by the cast wherever it
   appears. This makes `(:name::text IS NULL OR name = :name)`,
   `'%' || :term::text || '%'`, and `COALESCE(:bio::text, bio)` compile. A
   placeholder without a cast in an unanalyzed location stays rejected.
4. Grouped and computed projections: an aliased `COUNT(*)` may appear beside
   direct columns, and a projection written as `<expression>::type AS alias`
   produces a nullable component of the cast type, so
   `SELECT city, COUNT(*) AS venues ... GROUP BY city` and
   `SUM(amount)::numeric AS total` compile. Other expressions stay rejected.
5. Upsert: `INSERT ... ON CONFLICT (columns) DO NOTHING` and
   `ON CONFLICT (columns) DO UPDATE SET column = EXCLUDED.column`, a
   placeholder, or a non-binding expression, with or without `RETURNING`.
6. Id lists: `column = ANY($N)` binds a `List<T>` of the column's type, the
   PostgreSQL alternative to dynamic `IN` expansion.

Every item must fail clearly for a nearby unsupported form rather than passing
an untyped placeholder or inventing a generic `Object` type.

#### Out of scope

- named-parameter macros, nullable-parameter markers, or `@name` syntax,
- type inference for expressions without a cast, other than the listed
  `COUNT(*)`,
- subqueries, `EXISTS`, CTEs, window functions, and set operations,
- `ON CONFLICT ON CONSTRAINT`, conflict-target predicates, `MERGE`, multi-row
  or `SELECT`-sourced inserts,
- dynamic SQL, dynamic sorting, or generated query variants.

#### Acceptance

- The external sample adds a named-parameter optional filter, an
  `updated_at = now()` update, a grouped count, an upsert, and an id-list read.
- The constructs of sqlc's `authors`, `booktest`, `ondeck`, and `jets`
  PostgreSQL example queries that this milestone makes compile run against
  PostgreSQL in sqlcj-owned fixtures, and the constructs that stay outside it
  are recorded in the milestone's work items as unsupported with their
  reasons, as Milestone 19's D3, amended by the owner on 2026-10-04, decides.
  Nothing is fetched from or run against the sqlc repository.
- All new parameters have one known Java type, and every accepted placeholder
  is bound exactly once per textual occurrence.
- Focused analyzer and generator tests, generated-source compilation,
  PostgreSQL execution, `mvn spotless:check`, and `mvn test` pass.
