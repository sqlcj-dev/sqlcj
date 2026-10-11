package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the milestone acceptance on the reference service: its own Flyway
 * migration history is applied to a real PostgreSQL 16 server, that server's
 * {@code pg_dump --schema-only} snapshot is taken, and the history, the
 * hand-written snapshot, and the dump generate one byte-identical repository
 * tree that compiles and runs against the migrated database.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class ReferenceServiceIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates {@code TrackerRepository}. */
    private static final String GROUP = "Tracker";

    /** The migration directory inside the database container. */
    private static final String CONTAINER_MIGRATIONS = "/tmp/reference-service-migrations";

    /** The reference service's migrations, in Flyway version order. */
    private static final List<String> MIGRATIONS = List.of(
        "V1__organizations_and_users.sql",
        "V2__projects_and_memberships.sql",
        "V3__tasks.sql",
        "V4__task_constraints.sql",
        "V5__comments_time_entries_and_audit_log.sql",
        "V6__views_and_reporting.sql",
        "V7__roles_and_row_level_security.sql",
        "V8__enum_and_column_changes.sql",
        "V9__status_index.sql",
        "V10__seed_and_view_refresh.sql",
        "V11__assignees_and_soft_deleted_users.sql"
    );

    /**
     * The unsupported query catalogue: one query file per shape the supported
     * subset excludes, with the query name and the reason sqlcj reports for it.
     */
    private static final List<UnsupportedQuery> UNSUPPORTED_QUERIES = List.of(
        new UnsupportedQuery(
            "union.sql",
            "ListProjectAndTaskTitles",
            "UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT."
        ),
        new UnsupportedQuery(
            "union_all.sql",
            "ListEveryProjectAndTaskTitle",
            "UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT."
        ),
        new UnsupportedQuery(
            "right_join.sql",
            "ListProjectsWithTasks",
            "RIGHT JOIN is not supported; write a LEFT JOIN with the two sources swapped."
        ),
        new UnsupportedQuery(
            "full_join.sql",
            "ListProjectsAndTasks",
            "FULL JOIN is not supported."
        ),
        new UnsupportedQuery(
            "using_join.sql",
            "ListTaskComments",
            "JOIN ... USING is not supported; write the join condition with ON."
        ),
        new UnsupportedQuery(
            "natural_join.sql",
            "ListNaturallyJoinedTasks",
            "NATURAL JOIN is not supported; write the join condition with ON."
        ),
        new UnsupportedQuery(
            "cross_join.sql",
            "ListOrganizationProjectPairs",
            "CROSS JOIN is not supported; write JOIN ... ON TRUE."
        ),
        new UnsupportedQuery(
            "comma_join.sql",
            "ListCommaJoinedTasks",
            "A comma-separated FROM list is not supported; "
                + "write JOIN ... ON with the join condition."
        ),
        new UnsupportedQuery(
            "lateral_join.sql",
            "ListProjectsWithLatestTask",
            "LATERAL is not supported."
        ),
        new UnsupportedQuery(
            "cte_source.sql",
            "ListOpenTaskTitles",
            "A common table expression used as a source is not supported: open_tasks"
        ),
        new UnsupportedQuery(
            "from_subquery.sql",
            "ListRecentTaskTitles",
            "A subquery in FROM is not supported."
        ),
        new UnsupportedQuery(
            "multi_row_values.sql",
            "CreateTwoProjects",
            "INSERT requires a single VALUES row."
        ),
        new UnsupportedQuery(
            "insert_select.sql",
            "ArchiveAuditLog",
            "INSERT ... SELECT is not supported."
        ),
        new UnsupportedQuery(
            "update_from.sql",
            "RaiseProjectTaskPriority",
            "UPDATE ... FROM and joined updates are not supported."
        ),
        new UnsupportedQuery(
            "delete_using.sql",
            "DeleteProjectComments",
            "DELETE ... USING and joined deletes are not supported."
        ),
        new UnsupportedQuery(
            "uncast_lower.sql",
            "FindUserByEmail",
            "Cannot infer the type of :email in lower(:email); "
                + "state it with a cast, such as :email::text"
        ),
        new UnsupportedQuery(
            "subscripted_column.sql",
            "ListTasksByFirstLabel",
            "Cannot infer the type of :label in labels[1] = :label; "
                + "state it with a cast, such as :label::text"
        )
    );

    /** The files the three schema inputs generate, relative to {@code java.out}. */
    private static final Set<String> GENERATED_FILES = Set.of(
        "sqlcj-manifest.txt",
        "generated/TrackerRepository.java",
        "generated/TasksRow.java",
        "generated/ProjectsRow.java",
        "generated/CommentsRow.java",
        "generated/AuditLog2026Row.java",
        "generated/AuditLogArchiveRow.java",
        "generated/TaskStatus.java",
        "generated/MembershipRole.java"
    );

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

    /** The directory that receives the {@code pg_dump --schema-only} snapshot. */
    @TempDir
    static Path dumpDirectory;

    /** The {@code pg_dump --schema-only} snapshot of the migrated database. */
    private static Path dumpFile;

    @TempDir
    Path tempDir;

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();

        if (!available && Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY)) {
            throw new IllegalStateException(
                "Docker is unavailable, but " + REQUIRE_DOCKER_PROPERTY + " requires these tests to run."
            );
        }

        return available;
    }

    /**
     * Applies the reference service's migrations in Flyway version order with
     * {@code psql}, then dumps the schema of the migrated database.
     */
    @BeforeAll
    static void migrateAndDump() throws Exception {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
        );

        postgres.start();

        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();

        postgresDataSource.setUrl(postgres.getJdbcUrl());
        postgresDataSource.setUser(postgres.getUsername());
        postgresDataSource.setPassword(postgres.getPassword());

        dataSource = postgresDataSource;

        assertEquals(
            MIGRATIONS,
            migrationFileNames(),
            "the migration directory holds exactly the applied migrations"
        );

        run("mkdir", "-p", CONTAINER_MIGRATIONS);

        for (String migration : MIGRATIONS) {
            postgres.copyFileToContainer(
                MountableFile.forHostPath(migrations().resolve(migration)),
                CONTAINER_MIGRATIONS + "/" + migration
            );

            run(
                "psql",
                "-X",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-f",
                CONTAINER_MIGRATIONS + "/" + migration
            );
        }

        Container.ExecResult dump = postgres.execInContainer(
            "pg_dump",
            "--schema-only",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName()
        );

        assertEquals(0, dump.getExitCode(), dump.getStderr());

        dumpFile = dumpDirectory.resolve("dump.sql");

        Files.writeString(dumpFile, dump.getStdout());
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * The migration history, the hand-written snapshot, and the dump of the
     * migrated database are three spellings of one schema, so each generates
     * the same files with the same bytes, and that output compiles.
     */
    @Test
    void shouldGenerateIdenticalRepositoriesFromHistorySnapshotAndDump() throws Exception {
        Path fromHistory = tempDir.resolve("from-history");
        Path fromSnapshot = tempDir.resolve("from-snapshot");
        Path fromDump = tempDir.resolve("from-dump");

        generate(List.of(migrations().toString()), fromHistory);
        generate(List.of(snapshot().toString()), fromSnapshot);
        generate(List.of(dumpFile.toString()), fromDump);

        Map<String, byte[]> historyFiles = generatedFiles(fromHistory);

        assertEquals(GENERATED_FILES, historyFiles.keySet());

        assertIdentical(historyFiles, generatedFiles(fromSnapshot));
        assertIdentical(historyFiles, generatedFiles(fromDump));

        compile(fromHistory, tempDir.resolve("classes-history"));
    }

    /**
     * The generated repository executes the reference service's queries
     * against the migrated database, including the replaced membership role,
     * the renamed task status, the optimistic update, and the audit partition
     * the {@code LIKE} copy does not follow.
     */
    @Test
    void shouldExecuteTheGeneratedReferenceServiceQueries() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-runtime");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-runtime"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Object organization = invoke(
                repository,
                "createOrganization",
                "Tracker Test",
                "{\"theme\": \"dark\"}"
            );

            Long organizationId = (Long) component(organization, "id");

            Object user = invoke(
                repository,
                "createUser",
                organizationId,
                "grace@example.com",
                "Grace Hopper"
            );

            Long userId = (Long) component(user, "id");

            Object maintainer = enumConstant(classLoader, "MembershipRole", "MAINTAINER");

            assertEquals(1, invoke(repository, "addMember", organizationId, userId, maintainer));

            List<?> members = (List<?>) invoke(repository, "listMembers", organizationId);

            assertEquals(1, members.size());

            assertEquals("Grace Hopper", component(members.get(0), "displayName"));

            assertEquals(maintainer, component(members.get(0), "role"));

            Object project = invoke(
                repository,
                "createProject",
                organizationId,
                "TRACK",
                "Tracker"
            );

            Long projectId = (Long) component(project, "id");

            assertEquals(
                "ProjectsRow",
                project.getClass().getSimpleName(),
                "a RETURNING * write returns the shared row record"
            );

            assertEquals(
                Optional.of(project),
                invoke(repository, "getProject", projectId)
            );

            assertEquals(
                Optional.empty(),
                invoke(repository, "getProject", projectId + 1_000L),
                "an optional read of a missing project is empty"
            );

            Object open = enumConstant(classLoader, "TaskStatus", "OPEN");
            Object doing = enumConstant(classLoader, "TaskStatus", "DOING");
            Object blocked = enumConstant(classLoader, "TaskStatus", "BLOCKED");

            Object task = invoke(
                repository,
                "createTask",
                projectId,
                "Write the acceptance test",
                open,
                (short) 1,
                List.of("test", "sqlcj"),
                "{\"source\": \"fixture\"}"
            );

            Long taskId = (Long) component(task, "id");

            assertEquals(open, component(task, "status"));

            assertEquals(1, invoke(repository, "moveTask", doing, taskId, 1));

            List<?> doingTasks = (List<?>) invoke(
                repository,
                "listProjectTasks",
                projectId,
                List.of(doing),
                10
            );

            assertEquals(1, doingTasks.size());

            assertEquals(taskId, component(doingTasks.get(0), "id"));

            assertEquals(doing, component(doingTasks.get(0), "status"));

            assertEquals(
                List.of(),
                invoke(repository, "listProjectTasks", projectId, List.of(blocked), 10),
                "the renamed status filters the list"
            );

            List<?> counts = (List<?>) invoke(repository, "countTasksByStatus", projectId);

            assertEquals(1, counts.size());

            assertEquals(doing, component(counts.get(0), "status"));

            assertEquals(1L, component(counts.get(0), "total"));

            Object comment = invoke(repository, "addComment", taskId, userId, "Looks right.");

            assertEquals("Looks right.", component(comment, "body"));

            UUID requestId = UUID.randomUUID();

            OffsetDateTime occurredAt = OffsetDateTime.of(
                2026,
                6,
                1,
                9,
                30,
                0,
                0,
                ZoneOffset.UTC
            );

            assertEquals(
                1,
                invoke(
                    repository,
                    "recordAudit",
                    occurredAt,
                    userId,
                    "task.moved",
                    "{\"to\": \"doing\"}",
                    requestId
                )
            );

            List<?> audit = (List<?>) invoke(repository, "listAudit2026");

            assertEquals(1, audit.size());

            assertEquals("task.moved", component(audit.get(0), "action"));

            assertEquals(
                requestId,
                component(audit.get(0), "requestId"),
                "the partition follows the column its partitioned table gained"
            );

            assertEquals(
                List.of("id", "occurredAt", "actorId", "action", "detail"),
                recordComponentNames(
                    Class.forName("generated.AuditLogArchiveRow", true, classLoader)
                ),
                "the LIKE copy does not follow the column the audit log gained"
            );

            assertEquals(List.of(), invoke(repository, "listArchivedAudit"));
        }
    }

    /**
     * The generated repository executes the query shapes this milestone adds
     * against the migrated database: the dynamic sort and the cast page, the
     * {@code EXISTS}, {@code IN (SELECT ...)}, and scalar subqueries, the
     * composite-key join, the filtered left join, the conflict clauses that
     * bind nothing, and the aliased {@code RETURNING} of an optimistic update.
     *
     * <p>The database is shared with the other tests of this class, so this one
     * works inside its own organization, project, and users, and writes no
     * audit entry.
     */
    @Test
    void shouldExecuteTheGeneratedMilestoneQueryShapes() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-shapes");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-shapes"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Long organizationId = (Long) component(
                invoke(repository, "createOrganization", "Shape Test", "{}"),
                "id"
            );

            Long assigneeId = (Long) component(
                invoke(repository, "createUser", organizationId, "lin@example.com", "Lin Shu"),
                "id"
            );

            Long retiredId = (Long) component(
                invoke(repository, "createUser", organizationId, "kurt@example.com", "Kurt Godel"),
                "id"
            );

            Object project = invoke(repository, "createProject", organizationId, "SHAPE", "Shapes");

            Long projectId = (Long) component(project, "id");

            Object open = enumConstant(classLoader, "TaskStatus", "OPEN");
            Object maintainer = enumConstant(classLoader, "MembershipRole", "MAINTAINER");

            Long betaId = (Long) component(
                createShapeTask(repository, projectId, "Beta", (short) 3, open),
                "id"
            );

            Long alphaId = (Long) component(
                createShapeTask(repository, projectId, "Alpha", (short) 1, open),
                "id"
            );

            Long gammaId = (Long) component(
                createShapeTask(repository, projectId, "Gamma", (short) 2, open),
                "id"
            );

            assertEquals(
                List.of("Alpha", "Beta", "Gamma"),
                componentsOf(
                    (List<?>) invoke(repository, "listTasksPage", projectId, "title", 3, 0),
                    "title"
                ),
                "the dynamic sort orders by title"
            );

            assertEquals(
                List.of("Alpha", "Gamma", "Beta"),
                componentsOf(
                    (List<?>) invoke(repository, "listTasksPage", projectId, "priority", 3, 0),
                    "title"
                ),
                "the same query orders by priority"
            );

            assertEquals(
                List.of("Beta", "Gamma"),
                componentsOf(
                    (List<?>) invoke(repository, "listTasksPage", projectId, "title", 2, 1),
                    "title"
                ),
                "the cast page skips its offset"
            );

            assertEquals(
                1,
                invoke(repository, "joinOrganization", organizationId, assigneeId),
                "the untargeted ON CONFLICT DO NOTHING inserts the first membership"
            );

            assertEquals(
                0,
                invoke(repository, "joinOrganization", organizationId, assigneeId),
                "the untargeted ON CONFLICT DO NOTHING keeps the existing membership"
            );

            assertEquals(
                1,
                invoke(repository, "changeMemberRole", organizationId, assigneeId, maintainer),
                "ON CONFLICT ON CONSTRAINT updates the changed role"
            );

            assertEquals(
                0,
                invoke(repository, "changeMemberRole", organizationId, assigneeId, maintainer),
                "DO UPDATE ... WHERE skips the unchanged role"
            );

            List<?> visibleProjects = (List<?>) invoke(
                repository,
                "listVisibleProjects",
                assigneeId
            );

            assertEquals(1, visibleProjects.size(), "EXISTS keeps only the member's projects");

            assertEquals(projectId, component(visibleProjects.get(0), "id"));

            assertEquals("SHAPE", component(visibleProjects.get(0), "code"));

            assertEquals(
                List.of("Beta", "Alpha", "Gamma"),
                componentsOf(
                    (List<?>) invoke(repository, "listOrganizationTasks", organizationId),
                    "title"
                ),
                "IN (SELECT ...) keeps the tasks of the organization's projects"
            );

            assertNotNull(invoke(repository, "addComment", alphaId, assigneeId, "First."));

            assertNotNull(invoke(repository, "addComment", alphaId, assigneeId, "Second."));

            OffsetDateTime beforeTheComments = OffsetDateTime.of(
                2026,
                1,
                1,
                0,
                0,
                0,
                0,
                ZoneOffset.UTC
            );

            List<?> recentComments = (List<?>) invoke(
                repository,
                "listTasksWithRecentComments",
                beforeTheComments,
                projectId
            );

            assertEquals(
                List.of(betaId, alphaId, gammaId),
                componentsOf(recentComments, "id")
            );

            assertEquals(
                List.of(0L, 2L, 0L),
                componentsOf(recentComments, "recentComments"),
                "the scalar subquery counts each task's recent comments"
            );

            assertEquals(
                List.of(0L, 0L, 0L),
                componentsOf(
                    (List<?>) invoke(
                        repository,
                        "listTasksWithRecentComments",
                        beforeTheComments.plusYears(100),
                        projectId
                    ),
                    "recentComments"
                ),
                "the scalar subquery binds its own cast placeholder"
            );

            Object assignment = ((Optional<?>) invoke(
                repository,
                "assignTask",
                assigneeId,
                alphaId,
                1
            )).orElseThrow();

            assertEquals(
                alphaId,
                component(assignment, "taskId"),
                "the aliased RETURNING names its own components"
            );

            assertEquals(2, component(assignment, "newVersion"));

            assertEquals(
                Optional.empty(),
                invoke(repository, "assignTask", assigneeId, alphaId, 1),
                "an optimistic update of a stale version returns no row"
            );

            assertEquals(
                2,
                component(
                    ((Optional<?>) invoke(repository, "assignTask", retiredId, betaId, 1))
                        .orElseThrow(),
                    "newVersion"
                )
            );

            assertEquals(
                1,
                invoke(repository, "softDeleteUser", retiredId),
                "the soft delete marks the assignee deleted"
            );

            List<?> assignedTasks = (List<?>) invoke(repository, "listAssignedTasks", projectId);

            assertEquals(
                1,
                assignedTasks.size(),
                "the composite-key join keeps only tasks assigned to a member"
            );

            assertEquals(alphaId, component(assignedTasks.get(0), "id"));

            assertEquals(
                maintainer,
                component(assignedTasks.get(0), "role"),
                "the composite-key join reads the membership role"
            );

            List<?> withAssignee = (List<?>) invoke(repository, "listTasksWithAssignee", projectId);

            assertEquals(
                List.of(betaId, alphaId, gammaId),
                componentsOf(withAssignee, "id")
            );

            assertEquals(
                Arrays.asList(null, "Lin Shu", null),
                componentsOf(withAssignee, "assigneeName"),
                "the filtered left join names the live assignee and no other"
            );

            Object claimed = ((Optional<?>) invoke(
                repository,
                "claimHandle",
                organizationId,
                "ida@example.com",
                "ida",
                "Ida Rhodes"
            )).orElseThrow();

            assertNotNull(component(claimed, "id"));

            assertEquals(
                "Ida Rhodes",
                component(claimed, "displayName"),
                "the conflict-target predicate claims a free handle"
            );

            assertEquals(
                Optional.empty(),
                invoke(
                    repository,
                    "claimHandle",
                    organizationId,
                    "ida.rhodes@example.com",
                    "ida",
                    "Ida Rhodes"
                ),
                "the conflict-target predicate leaves a live handle to its owner"
            );
        }
    }

    /**
     * Every entry of the unsupported query catalogue is rejected with its whole
     * located diagnostic line against the migration history, and writes no
     * file.
     */
    @ParameterizedTest
    @MethodSource("unsupportedQueries")
    void shouldRejectEachUnsupportedCatalogueQuery(UnsupportedQuery entry) throws Exception {
        Path queriesFile = resource("unsupported/queries/" + entry.fileName());

        Path generatedDirectory = tempDir.resolve("generated-unsupported");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> generate(
                List.of(migrations().toString()),
                queriesFile,
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid query '%s' in %s at line 2: %s".formatted(
                entry.queryName(),
                queriesFile,
                entry.reason()
            ),
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /** The catalogue directory holds exactly the catalogued query files. */
    @Test
    void shouldCatalogueExactlyTheUnsupportedQueryFiles() throws Exception {
        try (Stream<Path> files = Files.list(resource("unsupported/queries"))) {
            assertEquals(
                UNSUPPORTED_QUERIES.stream()
                    .map(UnsupportedQuery::fileName)
                    .sorted()
                    .toList(),
                files
                    .map(file -> file.getFileName().toString())
                    .sorted()
                    .toList()
            );
        }
    }

    static List<UnsupportedQuery> unsupportedQueries() {
        return UNSUPPORTED_QUERIES;
    }

    /**
     * The unsupported catalogue's {@code SET DATA TYPE}, applied after the
     * history, is rejected with its file, line, and column, and nothing is
     * written.
     */
    @Test
    void shouldRejectTheUnsupportedSetDataTypeMigration() throws Exception {
        Path unsupported = resource("unsupported/set_data_type.sql");

        Path generatedDirectory = tempDir.resolve("generated-rejected");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> generate(
                List.of(migrations().toString(), unsupported.toString()),
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid schema source %s: ".formatted(unsupported)
                + "Encountered unexpected token: \"DATA\" at line 2, column 51",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /**
     * The reporting schema of the history is invisible, so a query that names
     * its table is rejected as an unknown table rather than resolving to the
     * {@code projects} table of the one modeled namespace.
     */
    @Test
    void shouldRejectAQueryOnASecondSchemaTable() throws Exception {
        Path queriesFile = tempDir.resolve("reporting-queries.sql");

        Files.writeString(
            queriesFile,
            """
                -- name: ListExportedProjects :many
                SELECT *
                FROM reporting.projects;
                """
        );

        Path generatedDirectory = tempDir.resolve("generated-reporting");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> generate(
                List.of(migrations().toString()),
                queriesFile,
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid query 'ListExportedProjects' in %s at line 1: ".formatted(queriesFile)
                + "Table not found in schema: reporting.projects",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /** Runs one command inside the database container and fails on its stderr. */
    private static void run(String... command) throws Exception {
        Container.ExecResult result = postgres.execInContainer(command);

        assertEquals(
            0,
            result.getExitCode(),
            String.join(" ", command) + System.lineSeparator() + result.getStderr()
        );
    }

    /** The {@code .sql} file names of the migration directory, in version order. */
    private static List<String> migrationFileNames() throws Exception {
        try (Stream<Path> files = Files.list(migrations())) {
            return files
                .map(file -> file.getFileName().toString())
                .sorted(
                    (first, second) -> Integer.compare(
                        migrationVersion(first),
                        migrationVersion(second)
                    )
                )
                .toList();
        }
    }

    private static int migrationVersion(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }

    private static Path migrations() throws Exception {
        return resource("migrations");
    }

    private static Path snapshot() throws Exception {
        return resource("schema.sql");
    }

    private static Path queries() throws Exception {
        return resource("queries.sql");
    }

    /** One reference service fixture, resolved from the test classpath. */
    private static Path resource(String name) throws Exception {
        URL url = ReferenceServiceIntegrationTest.class.getResource(
            "/reference-service/" + name
        );

        assertNotNull(url, "missing reference service fixture: " + name);

        return Paths.get(url.toURI());
    }

    private void generate(List<String> schema, Path generatedDirectory) throws Exception {
        generate(schema, queries(), generatedDirectory);
    }

    /** Generates the one repository of the {@code Tracker} group. */
    private void generate(List<String> schema, Path queriesFile, Path generatedDirectory) {
        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    schema,
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);
    }

    /** Every generated file of one output directory, by its relative path. */
    private Map<String, byte[]> generatedFiles(Path generatedDirectory) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(generatedDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(
                    generatedDirectory.relativize(path).toString().replace('\\', '/'),
                    Files.readAllBytes(path)
                );
            }
        }

        return files;
    }

    private void assertIdentical(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());

        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            assertArrayEquals(
                file.getValue(),
                actual.get(file.getKey()),
                file.getKey() + " differs"
            );
        }
    }

    /** Compiles every generated source of one output directory together. */
    private Path compile(Path generatedDirectory, Path classesDirectory) throws Exception {
        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString()
            )
        );

        try (Stream<Path> generatedFiles = Files.walk(generatedDirectory)) {
            generatedFiles
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
                .sorted()
                .forEach(arguments::add);
        }

        assertEquals(
            0,
            compilerApi.run(
                null,
                null,
                null,
                arguments.toArray(new String[0])
            )
        );

        return classesDirectory;
    }

    private URLClassLoader classLoader(Path classesDirectory) throws Exception {
        return new URLClassLoader(
            new URL[] { classesDirectory.toUri().toURL() },
            getClass().getClassLoader()
        );
    }

    private Object newRepository(URLClassLoader classLoader) throws Exception {
        Class<?> generatedClass = Class.forName(
            "generated." + GROUP + "Repository",
            true,
            classLoader
        );

        Constructor<?> constructor = generatedClass.getConstructors()[0];

        return constructor.newInstance(new JdbcQueryExecutor(dataSource));
    }

    /** One constant of a generated enum, loaded from the compiled output. */
    private Object enumConstant(
        URLClassLoader classLoader,
        String enumName,
        String constant
    ) throws Exception {
        return Class.forName("generated." + enumName, true, classLoader)
            .getField(constant)
            .get(null);
    }

    /** Calls one generated repository method, which has one name of its own. */
    private Object invoke(Object repository, String methodName, Object... arguments)
        throws Exception {
        for (Method method : repository.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method.invoke(repository, arguments);
            }
        }

        return fail("missing generated method: " + methodName);
    }

    private Object component(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }

    /** One component of every row of a result list, in the list's order. */
    private List<Object> componentsOf(List<?> rows, String componentName) throws Exception {
        List<Object> values = new ArrayList<>();

        for (Object row : rows) {
            values.add(component(row, componentName));
        }

        return values;
    }

    /** One task of the milestone shapes, with the labels and metadata it needs. */
    private Object createShapeTask(
        Object repository,
        Long projectId,
        String title,
        Short priority,
        Object status
    ) throws Exception {
        return invoke(
            repository,
            "createTask",
            projectId,
            title,
            status,
            priority,
            List.of("shape"),
            "{\"source\": \"fixture\"}"
        );
    }

    private List<String> recordComponentNames(Class<?> recordClass) {
        assertTrue(recordClass.isRecord());

        return Arrays.stream(recordClass.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }

    /**
     * One entry of the unsupported query catalogue: the query file, the name of
     * the single query it holds, and the reason sqlcj reports for it.
     */
    record UnsupportedQuery(String fileName, String queryName, String reason) {

        @Override
        public String toString() {
            return fileName;
        }
    }
}
