package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryGroupModel;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.runtime.QueryExecutor;
import dev.sqlcj.runtime.RowMapper;
import dev.sqlcj.schema.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeGeneratorNamingTest {

    private static final String SQL = """
        SELECT id
        FROM users
        """;

    private static final String GROUP = "Users";

    private final CodeGenerator codeGenerator = new JavaCodeGenerator();

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateKeywordSafeMethodForKeywordQueryName() throws IOException {
        GeneratedFile file = generate(
            query(
                "Class",
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                List.of()
            )
        );

        String source = file.content();

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());
        assertTrue(source.contains("public final class UsersRepository {"));
        assertTrue(source.contains("public UsersRepository(QueryExecutor executor)"));
        assertTrue(source.contains("public record ClassResult("));
        assertTrue(source.contains("public List<ClassResult> class_()"));

        assertCompiles(file);
    }

    @Test
    void shouldNormalizeUnsafeQueryNameAndEscapeJavadoc() throws IOException {
        GeneratedFile file = generate(
            query(
                "Get*/User",
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                List.of()
            )
        );

        String source = file.content();

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());
        assertTrue(source.contains("public final class UsersRepository {"));
        assertTrue(source.contains("public record Get_UserResult("));
        assertTrue(source.contains("public List<Get_UserResult> get_User()"));
        assertTrue(source.contains("Query: Get*&#47;User"));
        assertFalse(source.contains("Query: Get*/User"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateResultTypeForQueryNamedLikeAnImportedType() throws IOException {
        GeneratedFile file = generate(
            query(
                "List",
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                List.of()
            )
        );

        String source = file.content();

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());
        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("public final class UsersRepository {"));
        assertTrue(source.contains("public record ListResult("));
        assertTrue(source.contains("private static final RowMapper<ListResult> listRowMapper ="));
        assertTrue(source.contains("public List<ListResult> list()"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateResultTypeForQueryNamedLikeAnImportedUuidType() throws IOException {
        GeneratedFile file = generate(
            query(
                "UUID",
                List.of(new QueryColumn("external_id", ColumnType.UUID, true)),
                List.of()
            )
        );

        String source = file.content();

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());
        assertTrue(source.contains("import java.util.UUID;"));
        assertTrue(source.contains("public final class UsersRepository {"));
        assertTrue(source.contains("public record UUIDResult("));
        assertTrue(source.contains("UUID external_id"));
        assertTrue(source.contains("resultSet.getObject(1, UUID.class)"));

        assertCompiles(file);
    }

    @Test
    void shouldReadRenamedResultComponentsByProjectionPosition() throws IOException {
        GeneratedFile file = generate(
            query(
                "ListUsers",
                List.of(
                    new QueryColumn("class", ColumnType.BIGINT, false),
                    new QueryColumn("hashCode", ColumnType.VARCHAR, true),
                    new QueryColumn("user id", ColumnType.VARCHAR, true),
                    new QueryColumn("user-id", ColumnType.VARCHAR, true)
                ),
                List.of()
            )
        );

        String source = file.content();

        assertTrue(source.contains("Long class_"));
        assertTrue(source.contains("String hashCode1"));
        assertTrue(source.contains("String user_id1"));
        assertTrue(source.contains("String user_id2"));

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(2, String.class)"));
        assertTrue(source.contains("resultSet.getObject(3, String.class)"));
        assertTrue(source.contains("resultSet.getObject(4, String.class)"));

        assertCompiles(file);
    }

    @Test
    void shouldReadQuotedSqlColumnByProjectionPosition() throws IOException {
        GeneratedFile file = generate(
            query(
                "ListUsers",
                List.of(new QueryColumn("user\"id", ColumnType.VARCHAR, true)),
                List.of()
            )
        );

        String source = file.content();

        assertTrue(source.contains("String user_id"));
        assertTrue(source.contains("resultSet.getObject(1, String.class)"));

        assertCompiles(file);
    }

    @Test
    void shouldUseResolvedParameterNamesInSignatureAndArguments() throws IOException {
        GeneratedFile file = generate(
            query(
                "FindUsers",
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                List.of(
                    new QueryParameter(1, "executor", ColumnType.BIGINT),
                    new QueryParameter(2, "user id", ColumnType.VARCHAR),
                    new QueryParameter(3, "user-id", ColumnType.VARCHAR)
                )
            )
        );

        String source = file.content();

        assertTrue(
            source.contains(
                "public List<FindUsersResult> findUsers(Long executor1, String user_id1, String user_id2)"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList(executor1, user_id1, user_id2)"));

        assertCompiles(file);
    }

    @Test
    void shouldExecuteExactSqlForQuotedIdentifierWithBackslash() throws Exception {
        String sql = """
            SELECT "a\\q"
            FROM users
            WHERE "a\\q" = ?""";

        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            sql,
            List.of(1),
            List.of(new QueryColumn("a\\q", ColumnType.VARCHAR, true)),
            List.of(new QueryParameter(1, "a\\q", ColumnType.VARCHAR))
        );

        GeneratedFile file = generate(query);

        assertTrue(file.content().contains("SELECT \"a\\\\q\""));
        assertTrue(file.content().contains("String a_q"));

        assertCompiles(file);

        assertEquals(sql, executedSql(file, "listUsers", "value"));
    }

    @Test
    void shouldExecuteExactSqlForTextBlockDelimiterAndTrailingWhitespace() throws Exception {
        String sql = "SELECT '\"\"\"' \nFROM users";

        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            sql,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of()
        );

        GeneratedFile file = generate(query);

        assertCompiles(file);

        assertEquals(sql, executedSql(file, "listUsers"));
    }

    /**
     * Compiles the generated class, runs its query method against a recording
     * executor, and returns the SQL the generated code passed to the runtime.
     */
    private String executedSql(GeneratedFile file, String methodName, Object... arguments) throws Exception {
        Path classesDirectory = tempDir.resolve("classes");

        try (
            URLClassLoader classLoader = new URLClassLoader(
                new URL[] { classesDirectory.toUri().toURL() },
                getClass().getClassLoader()
            )
        ) {
            Class<?> generatedClass = classLoader.loadClass(
                "generated." + file.path().getFileName().toString().replace(".java", "")
            );

            RecordingQueryExecutor executor = new RecordingQueryExecutor();

            Object instance = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Method method = Stream.of(generatedClass.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();

            method.invoke(instance, arguments);

            return executor.sql;
        }
    }

    private static final class RecordingQueryExecutor implements QueryExecutor {

        private String sql;

        @Override
        public <T> T query(String sql, List<?> parameters, RowMapper<T> mapper) {
            this.sql = sql;

            return null;
        }

        @Override
        public <T> List<T> queryMany(String sql, List<?> parameters, RowMapper<T> mapper) {
            this.sql = sql;

            return List.of();
        }

        @Override
        public int execute(String sql, List<?> parameters) {
            this.sql = sql;

            return 0;
        }
    }

    /** Generates the repository of a single-query group. */
    private GeneratedFile generate(QueryModel query) {
        return codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(query)
            )
        );
    }

    private QueryModel query(String name, List<QueryColumn> columns, List<QueryParameter> parameters) {
        return new QueryModel(
            name,
            QueryType.MANY,
            "users",
            SQL,
            parameters.stream()
                .map(QueryParameter::index)
                .toList(),
            columns,
            parameters
        );
    }

    private void assertCompiles(GeneratedFile file) throws IOException {
        Path sourceDirectory = tempDir.resolve("generated");
        Path outputDirectory = tempDir.resolve("classes");

        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputDirectory);

        Path sourceFile = sourceDirectory.resolve(file.path());

        Files.createDirectories(sourceFile.getParent());

        Files.writeString(sourceFile, file.content());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler);

        int result = compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            outputDirectory.toString(),
            sourceFile.toString()
        );

        assertEquals(0, result);
    }
}
