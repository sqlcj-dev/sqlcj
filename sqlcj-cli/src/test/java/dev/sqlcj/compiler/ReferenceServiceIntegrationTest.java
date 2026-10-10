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
        "V10__seed_and_view_refresh.sql"
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

    private List<String> recordComponentNames(Class<?> recordClass) {
        assertTrue(recordClass.isRecord());

        return Arrays.stream(recordClass.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }
}
