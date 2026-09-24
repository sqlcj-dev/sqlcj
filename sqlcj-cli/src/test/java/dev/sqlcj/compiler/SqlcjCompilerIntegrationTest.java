package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryCardinalityException;
import dev.sqlcj.runtime.QueryExecutor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlcjCompilerIntegrationTest {

    private static final String JOIN_SCHEMA = """
        CREATE TABLE users
        (
            id   BIGINT NOT NULL,
            name VARCHAR(255)
        );

        CREATE TABLE profiles
        (
            id       BIGINT NOT NULL,
            user_id  BIGINT NOT NULL,
            nickname VARCHAR(255)
        );

        CREATE TABLE orders
        (
            id      BIGINT NOT NULL,
            user_id BIGINT NOT NULL,
            total   DECIMAL(10, 2)
        );
        """;

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateCompilableJavaFiles() throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(
            schemaFile,
            """
                CREATE TABLE users
                (
                    id         BIGINT NOT NULL,
                    name       VARCHAR(255),
                    active     BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP,
                    balance    DECIMAL(10, 2)
                );
                """
        );

        Files.writeString(
            queriesFile,
            """
                -- name: GetUser :one
                SELECT *
                FROM users
                WHERE created_at = $1;

                -- name: ListUsers :many
                SELECT id, birth_date, created_at, balance
                FROM users
                WHERE created_at = $1;

                -- name: FindUsers :many
                SELECT id, name
                FROM users
                WHERE id IN ($1, $2)
                  AND (active = $3 OR name = $4);
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        SqlcjCompiler compiler = new SqlcjCompiler();

        compiler.compile(config);

        Path repositoryFile = generatedDirectory.resolve("generated/UsersRepository.java");

        assertTrue(Files.exists(repositoryFile));

        assertFalse(Files.exists(generatedDirectory.resolve("generated/GetUser.java")));
        assertFalse(Files.exists(generatedDirectory.resolve("generated/ListUsers.java")));
        assertFalse(Files.exists(generatedDirectory.resolve("generated/FindUsers.java")));

        String repository = Files.readString(repositoryFile);

        assertTrue(repository.contains("public final class UsersRepository"));
        assertTrue(repository.contains("import java.time.LocalDate;"));
        assertTrue(repository.contains("import java.time.LocalDateTime;"));
        assertTrue(repository.contains("import java.math.BigDecimal;"));
        assertTrue(repository.contains("import java.util.List;"));

        assertEquals(
            1,
            repository.lines()
                .filter(line -> line.equals("    private final QueryExecutor executor;"))
                .count()
        );

        assertEquals(
            1,
            repository.lines()
                .filter(line -> line.contains("public UsersRepository(QueryExecutor executor)"))
                .count()
        );

        assertTrue(repository.contains("public UsersRow getUser(LocalDateTime createdAt)"));
        assertFalse(repository.contains("GetUserResult"));
        assertTrue(repository.contains("Long id"));
        assertTrue(repository.contains("String name"));
        assertTrue(repository.contains("LocalDate birthDate"));
        assertTrue(repository.contains("LocalDateTime createdAt"));
        assertTrue(repository.contains("BigDecimal balance"));

        assertEquals(
            List.of(
                "Long id",
                "String name",
                "Boolean active",
                "LocalDate birthDate",
                "LocalDateTime createdAt",
                "BigDecimal balance"
            ),
            recordComponents(repository, "UsersRow")
        );

        assertTrue(repository.contains("private static final RowMapper<UsersRow> usersRowMapper"));
        assertTrue(repository.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(repository.contains("resultSet.getObject(2, String.class)"));
        assertTrue(repository.contains("resultSet.getObject(3, Boolean.class)"));
        assertTrue(repository.contains("resultSet.getObject(4, LocalDate.class)"));
        assertTrue(repository.contains("resultSet.getObject(5, LocalDateTime.class)"));
        assertTrue(repository.contains("resultSet.getObject(6, BigDecimal.class)"));

        assertTrue(repository.contains("public List<ListUsersResult> listUsers(LocalDateTime createdAt)"));
        assertTrue(repository.contains("private static final RowMapper<ListUsersResult> listUsersRowMapper"));

        assertEquals(
            List.of(
                "Long id",
                "LocalDate birthDate",
                "LocalDateTime createdAt",
                "BigDecimal balance"
            ),
            recordComponents(repository, "ListUsersResult")
        );

        assertTrue(
            repository
                .contains("public List<FindUsersResult> findUsers(Long id1, Long id2, Boolean active, String name)")
        );
        assertTrue(repository.contains("public record FindUsersResult("));
        assertTrue(repository.contains("private static final RowMapper<FindUsersResult> findUsersRowMapper"));

        assertTrue(
            repository.indexOf("public UsersRow getUser(") < repository
                .indexOf("public List<ListUsersResult> listUsers(")
        );

        assertTrue(
            repository.indexOf("public List<ListUsersResult> listUsers(") < repository
                .indexOf("public List<FindUsersResult> findUsers(")
        );

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        String classpath = System.getProperty("java.class.path");

        int result = compilerApi.run(
            null,
            null,
            null,
            "-classpath",
            classpath,
            "-d",
            classesDirectory.toString(),
            repositoryFile.toString()
        );

        assertEquals(0, result);

        assertTrue(
            Files.exists(
                classesDirectory.resolve("generated/UsersRepository.class")
            )
        );
    }

    @Test
    void shouldExecuteGeneratedQuery() throws Exception {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(
            schemaFile,
            """
                CREATE TABLE users
                (
                    id         BIGINT NOT NULL,
                    name       VARCHAR(255),
                    active     BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP,
                    balance    DECIMAL(10, 2)
                );
                """
        );

        Files.writeString(
            queriesFile,
            """
                -- name: GetUser :one
                SELECT *
                FROM users
                WHERE created_at = $1;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        SqlcjCompiler compiler = new SqlcjCompiler();

        compiler.compile(config);

        Path repositoryFile = generatedDirectory.resolve("generated/UsersRepository.java");

        assertTrue(Files.exists(repositoryFile));

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        String classpath = System.getProperty("java.class.path");

        int compilationResult = compilerApi.run(
            null,
            null,
            null,
            "-classpath",
            classpath,
            "-d",
            classesDirectory.toString(),
            repositoryFile.toString()
        );

        assertEquals(0, compilationResult);

        JdbcDataSource dataSource = new JdbcDataSource();

        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255),
                    active BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP,
                    balance DECIMAL(10, 2)
                )
                """);

            statement.execute("""
                INSERT INTO users
                    (id, name, active, birth_date, created_at, balance)
                VALUES
                    (1, 'Alice', TRUE, '1990-01-15',
                     '2026-01-01 10:00:00', 100.50)
                """);
        }

        QueryExecutor executor = new JdbcQueryExecutor(dataSource);

        URL[] classpathUrls = {
            classesDirectory.toUri().toURL()
        };

        try (URLClassLoader classLoader = new URLClassLoader(classpathUrls, getClass().getClassLoader())) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Constructor<?> constructor = generatedClass.getConstructor(
                QueryExecutor.class
            );

            Object generatedQuery = constructor.newInstance(executor);

            Method method = generatedClass.getMethod(
                "getUser",
                LocalDateTime.class
            );

            Object result = method.invoke(
                generatedQuery,
                LocalDateTime.of(
                    2026,
                    1,
                    1,
                    10,
                    0
                )
            );

            assertNotNull(result);

            assertEquals(
                1L,
                getRecordComponent(result, "id")
            );

            assertEquals(
                "Alice",
                getRecordComponent(result, "name")
            );

            assertEquals(
                true,
                getRecordComponent(result, "active")
            );
        }
    }

    @Test
    void shouldExecuteGeneratedManyQuery() throws Exception {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(
            schemaFile,
            """
                CREATE TABLE users
                (
                    id         BIGINT NOT NULL,
                    name       VARCHAR(255),
                    active     BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP,
                    balance    DECIMAL(10, 2)
                );
                """
        );

        Files.writeString(
            queriesFile,
            """
                -- name: ListActiveUsers :many
                SELECT id, name, active
                FROM users
                WHERE active = $1
                ORDER BY id;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        SqlcjCompiler compiler = new SqlcjCompiler();

        compiler.compile(config);

        Path repositoryFile = generatedDirectory.resolve("generated/UsersRepository.java");

        assertTrue(Files.exists(repositoryFile));

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        String classpath = System.getProperty("java.class.path");

        int compilationResult = compilerApi.run(
            null,
            null,
            null,
            "-classpath",
            classpath,
            "-d",
            classesDirectory.toString(),
            repositoryFile.toString()
        );

        assertEquals(0, compilationResult);

        JdbcDataSource dataSource = new JdbcDataSource();

        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255),
                    active BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP,
                    balance DECIMAL(10, 2)
                )
                """);

            statement.execute("""
                INSERT INTO users
                    (id, name, active, birth_date, created_at, balance)
                VALUES
                    (1, 'Alice', TRUE, '1990-01-15',
                     '2026-01-01 10:00:00', 100.50),
                    (2, 'Bob', FALSE, '1985-05-20',
                     '2026-02-01 11:30:00', 200.75),
                    (3, 'Charlie', TRUE, '1995-10-10',
                     '2026-03-01 12:45:00', 300.25)
                """);
        }

        QueryExecutor executor = new JdbcQueryExecutor(dataSource);

        URL[] classpathUrls = {
            classesDirectory.toUri().toURL()
        };

        try (
            URLClassLoader classLoader = new URLClassLoader(
                classpathUrls,
                getClass().getClassLoader()
            )
        ) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Constructor<?> constructor = generatedClass.getConstructor(
                QueryExecutor.class
            );

            Object generatedQuery = constructor.newInstance(executor);

            Method method = generatedClass.getMethod(
                "listActiveUsers",
                Boolean.class
            );

            Object result = method.invoke(
                generatedQuery,
                true
            );

            assertNotNull(result);
            assertInstanceOf(List.class, result);

            List<?> users = (List<?>) result;

            assertEquals(2, users.size());

            Object first = users.get(0);
            Object second = users.get(1);

            assertEquals(
                1L,
                getRecordComponent(first, "id")
            );

            assertEquals(
                "Alice",
                getRecordComponent(first, "name")
            );

            assertEquals(
                true,
                getRecordComponent(first, "active")
            );

            assertEquals(
                3L,
                getRecordComponent(second, "id")
            );

            assertEquals(
                "Charlie",
                getRecordComponent(second, "name")
            );

            assertEquals(
                true,
                getRecordComponent(second, "active")
            );
        }
    }

    @Test
    void shouldExecuteGeneratedQueryWithOutOfOrderPlaceholders() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: FindUser :one
                SELECT id, name, active
                FROM users
                WHERE active = $2
                  AND id = $1;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(
            source.contains(
                "public FindUserResult findUser(Long id, Boolean active)"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList(active, id)"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Method method = generatedClass.getMethod(
                "findUser",
                Long.class,
                Boolean.class
            );

            Object result = method.invoke(generatedQuery, 1L, true);

            assertNotNull(result);

            assertEquals(1L, getRecordComponent(result, "id"));
            assertEquals("Alice", getRecordComponent(result, "name"));
            assertEquals(true, getRecordComponent(result, "active"));
        }
    }

    @Test
    void shouldExecuteGeneratedQueryWithRepeatedPlaceholder() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: FindUser :one
                SELECT id, name, active
                FROM users
                WHERE name = $2
                  AND (id = $1 OR id = $1);
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(
            source.contains(
                "public FindUserResult findUser(Long id, String name)"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList(name, id, id)"));
        assertTrue(source.contains("AND (id = ? OR id = ?)"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Method method = generatedClass.getMethod(
                "findUser",
                Long.class,
                String.class
            );

            Object result = method.invoke(generatedQuery, 1L, "Alice");

            assertNotNull(result);

            assertEquals(1L, getRecordComponent(result, "id"));
            assertEquals("Alice", getRecordComponent(result, "name"));

            InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> method.invoke(generatedQuery, 2L, "Alice")
            );

            QueryCardinalityException cardinality = assertInstanceOf(
                QueryCardinalityException.class,
                failure.getCause()
            );

            assertEquals(
                "Query 'FindUser' in UsersRepository returned no row; expected exactly one",
                cardinality.getMessage()
            );
        }
    }

    @Test
    void shouldExecuteGeneratedQueryWithProtectedPlaceholderText() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: FindUser :one
                -- Keeps $9 in a comment.
                SELECT id, name
                FROM users
                WHERE name <> '$1 literal' /* keeps $8 */
                  AND id = $1;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(source.contains("-- Keeps $9 in a comment."));
        assertTrue(source.contains("WHERE name <> '$1 literal' /* keeps $8 */"));
        assertTrue(source.contains("AND id = ?"));

        assertTrue(
            source.contains(
                "public FindUserResult findUser(Long id)"
            )
        );

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Object result = generatedClass
                .getMethod("findUser", Long.class)
                .invoke(generatedQuery, 1L);

            assertNotNull(result);

            assertEquals("Alice", getRecordComponent(result, "name"));
        }
    }

    @Test
    void shouldExecuteGeneratedQueryWithoutParameters() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: ListAllUsers :many
                SELECT id, name
                FROM users
                ORDER BY id;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(
            source.contains(
                "public List<ListAllUsersResult> listAllUsers()"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList()"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            List<?> results = assertInstanceOf(
                List.class,
                generatedClass
                    .getMethod("listAllUsers")
                    .invoke(generatedQuery)
            );

            assertEquals(2, results.size());

            assertEquals(1L, getRecordComponent(results.get(0), "id"));
            assertEquals("Alice", getRecordComponent(results.get(0), "name"));
            assertEquals(2L, getRecordComponent(results.get(1), "id"));
            assertEquals("Bob", getRecordComponent(results.get(1), "name"));
        }
    }

    @Test
    void shouldExecuteGeneratedInsert() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: InsertUser :exec
                INSERT INTO users (id, name, active)
                VALUES ($1, $2, $3);
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(
            source.contains(
                "public int insertUser(Long id, String name, Boolean active)"
            )
        );

        assertTrue(source.contains("return executor.execute("));
        assertTrue(source.contains("java.util.Arrays.asList(id, name, active)"));
        assertFalse(source.contains("public record InsertUserResult("));
        assertFalse(source.contains("RowMapper"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Object affected = generatedClass
                .getMethod("insertUser", Long.class, String.class, Boolean.class)
                .invoke(generatedQuery, 3L, "Carol", true);

            assertEquals(1, affected);

            assertEquals(
                "Carol",
                executor.queryOne(
                    "UsersRepository",
                    "GetUserName",
                    "SELECT name FROM users WHERE id = ?",
                    List.of(3L),
                    resultSet -> resultSet.getString("name")
                )
            );
        }
    }

    @Test
    void shouldExecuteGeneratedUpdateWithOutOfOrderPlaceholders() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: UpdateUserName :exec
                UPDATE users
                SET name = $2
                WHERE id = $1;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(
            source.contains(
                "public int updateUserName(Long id, String name)"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList(name, id)"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Object affected = generatedClass
                .getMethod("updateUserName", Long.class, String.class)
                .invoke(generatedQuery, 1L, "Alicia");

            assertEquals(1, affected);

            assertEquals(
                "Alicia",
                executor.queryOne(
                    "UsersRepository",
                    "GetUserName",
                    "SELECT name FROM users WHERE id = ?",
                    List.of(1L),
                    resultSet -> resultSet.getString("name")
                )
            );
        }
    }

    @Test
    void shouldExecuteGeneratedDelete() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: DeleteUser :exec
                DELETE FROM users
                WHERE id = $1;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(source.contains("public int deleteUser(Long id)"));
        assertTrue(source.contains("java.util.Arrays.asList(id)"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Object affected = generatedClass
                .getMethod("deleteUser", Long.class)
                .invoke(generatedQuery, 2L);

            assertEquals(1, affected);

            assertTrue(
                executor.queryOptional(
                    "UsersRepository",
                    "FindUserName",
                    "SELECT name FROM users WHERE id = ?",
                    List.of(2L),
                    resultSet -> resultSet.getString("name")
                ).isEmpty()
            );
        }
    }

    @Test
    void shouldExecuteGeneratedAliasedQualifiedQuery() throws Exception {
        Path classesDirectory = generateAndCompile(
            JOIN_SCHEMA,
            """
                -- name: GetUser :one
                SELECT u.id, u.name
                FROM users u
                WHERE u.id = $1;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(source.contains("public GetUserResult getUser(Long id)"));
        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(2, String.class)"));

        QueryExecutor executor = new JdbcQueryExecutor(joinDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName("generated.UsersRepository", true, classLoader);

            Object result = generatedClass
                .getMethod("getUser", Long.class)
                .invoke(
                    generatedClass
                        .getConstructor(QueryExecutor.class)
                        .newInstance(executor),
                    1L
                );

            assertNotNull(result);
            assertEquals(1L, getRecordComponent(result, "id"));
            assertEquals("Alice", getRecordComponent(result, "name"));
        }
    }

    @Test
    void shouldExecuteGeneratedJoinQueryWithDuplicateColumnNames() throws Exception {
        Path classesDirectory = generateAndCompile(
            JOIN_SCHEMA,
            """
                -- name: ListUserProfiles :many
                SELECT u.id, p.id, p.nickname
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                WHERE p.nickname = $2
                  AND u.id = $1;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(
            source.contains(
                "public List<ListUserProfilesResult> listUserProfiles(Long id, String nickname)"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList(nickname, id)"));
        assertTrue(source.contains("Long id1"));
        assertTrue(source.contains("Long id2"));

        QueryExecutor executor = new JdbcQueryExecutor(joinDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object results = generatedClass
                .getMethod("listUserProfiles", Long.class, String.class)
                .invoke(
                    generatedClass
                        .getConstructor(QueryExecutor.class)
                        .newInstance(executor),
                    1L,
                    "ali"
                );

            List<?> rows = assertInstanceOf(List.class, results);

            assertEquals(1, rows.size());

            Object row = rows.getFirst();

            assertEquals(1L, getRecordComponent(row, "id1"));
            assertEquals(10L, getRecordComponent(row, "id2"));
            assertEquals("ali", getRecordComponent(row, "nickname"));
        }
    }

    /**
     * An explicit projection alias names the generated record component, while
     * the row mapper keeps reading each column by its projection position.
     */
    @Test
    void shouldExecuteGeneratedJoinQueryWithProjectionAliases() throws Exception {
        Path classesDirectory = generateAndCompile(
            JOIN_SCHEMA,
            """
                -- name: ListUserProfiles :many
                SELECT u.id AS user_id, p.id AS profile_id, p.nickname
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                WHERE u.id = $1;
                """
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UsersRepository.java"));

        assertTrue(
            source.contains(
                "public List<ListUserProfilesResult> listUserProfiles(Long id)"
            )
        );

        assertEquals(
            List.of("Long userId", "Long profileId", "String nickname"),
            recordComponents(source, "ListUserProfilesResult")
        );

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(2, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(3, String.class)"));

        QueryExecutor executor = new JdbcQueryExecutor(joinDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object results = generatedClass
                .getMethod("listUserProfiles", Long.class)
                .invoke(
                    generatedClass
                        .getConstructor(QueryExecutor.class)
                        .newInstance(executor),
                    1L
                );

            List<?> rows = assertInstanceOf(List.class, results);

            assertEquals(1, rows.size());

            Object row = rows.getFirst();

            assertEquals(1L, getRecordComponent(row, "userId"));
            assertEquals(10L, getRecordComponent(row, "profileId"));
            assertEquals("ali", getRecordComponent(row, "nickname"));
        }
    }

    @Test
    void shouldExecuteGeneratedMultipleJoinQuery() throws Exception {
        Path classesDirectory = generateAndCompile(
            JOIN_SCHEMA,
            """
                -- name: GetUserOrder :one
                SELECT u.id, p.nickname, o.id, o.total
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                JOIN orders o ON o.user_id = u.id
                WHERE u.id = $1;
                """
        );

        QueryExecutor executor = new JdbcQueryExecutor(joinDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UsersRepository",
                true,
                classLoader
            );

            Object result = generatedClass
                .getMethod("getUserOrder", Long.class)
                .invoke(
                    generatedClass
                        .getConstructor(QueryExecutor.class)
                        .newInstance(executor),
                    2L
                );

            assertNotNull(result);
            assertEquals(2L, getRecordComponent(result, "id1"));
            assertEquals("bob", getRecordComponent(result, "nickname"));
            assertEquals(200L, getRecordComponent(result, "id2"));
            assertEquals(new BigDecimal("20.00"), getRecordComponent(result, "total"));
        }
    }

    @Test
    void shouldCompileConfiguredEntriesWithConfiguredPackage() throws IOException {
        Path usersSchema = tempDir.resolve("users-schema.sql");
        Path usersQueries = tempDir.resolve("users-queries.sql");
        Path ordersSchema = tempDir.resolve("orders-schema.sql");
        Path ordersQueries = tempDir.resolve("orders-queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(
            usersSchema,
            """
                CREATE TABLE users
                (
                    id   BIGINT NOT NULL,
                    name VARCHAR(255)
                );
                """
        );

        Files.writeString(
            usersQueries,
            """
                -- name: GetUser :one
                SELECT id, name
                FROM users
                WHERE id = $1;
                """
        );

        Files.writeString(
            ordersSchema,
            """
                CREATE TABLE orders
                (
                    id         BIGINT NOT NULL,
                    total      DECIMAL(10, 2),
                    created_at TIMESTAMP
                );
                """
        );

        Files.writeString(
            ordersQueries,
            """
                -- name: ListOrders :many
                SELECT id, total, created_at
                FROM orders
                WHERE created_at = $1;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    usersSchema.toString(),
                    usersQueries.toString()
                ),
                new SqlConfig(
                    "Orders",
                    ordersSchema.toString(),
                    ordersQueries.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "dev.example.generated"
            )
        );

        new SqlcjCompiler().compile(config);

        Path usersFile = generatedDirectory.resolve("dev/example/generated/UsersRepository.java");

        Path ordersFile = generatedDirectory.resolve("dev/example/generated/OrdersRepository.java");

        assertTrue(Files.exists(usersFile));
        assertTrue(Files.exists(ordersFile));

        String users = Files.readString(usersFile);

        assertTrue(users.startsWith("package dev.example.generated;"));
        assertTrue(users.contains("public final class UsersRepository {"));
        assertTrue(users.contains("public GetUserResult getUser(Long id)"));

        String orders = Files.readString(ordersFile);

        assertTrue(orders.startsWith("package dev.example.generated;"));
        assertTrue(orders.contains("public final class OrdersRepository {"));
        assertTrue(orders.contains("public List<ListOrdersResult> listOrders(LocalDateTime createdAt)"));
        assertTrue(orders.contains("BigDecimal total"));

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        int compilationResult = compilerApi.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classesDirectory.toString(),
            usersFile.toString(),
            ordersFile.toString()
        );

        assertEquals(0, compilationResult);

        assertTrue(
            Files.exists(
                classesDirectory.resolve(
                    "dev/example/generated/UsersRepository.class"
                )
            )
        );

        assertTrue(
            Files.exists(
                classesDirectory.resolve(
                    "dev/example/generated/OrdersRepository.class"
                )
            )
        );
    }

    @Test
    void shouldGenerateTheSameQueryNameInTwoRepositories() throws IOException {
        Path usersSchema = tempDir.resolve("users-schema.sql");
        Path usersQueries = tempDir.resolve("users-queries.sql");
        Path ordersSchema = tempDir.resolve("orders-schema.sql");
        Path ordersQueries = tempDir.resolve("orders-queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");

        Files.writeString(
            usersSchema,
            """
                CREATE TABLE users
                (
                    id BIGINT NOT NULL
                );
                """
        );

        Files.writeString(
            usersQueries,
            """
                -- name: GetRecord :one
                SELECT id
                FROM users
                WHERE id = $1;
                """
        );

        Files.writeString(
            ordersSchema,
            """
                CREATE TABLE orders
                (
                    id BIGINT NOT NULL
                );
                """
        );

        Files.writeString(
            ordersQueries,
            """
                -- name: GetRecord :one
                SELECT id
                FROM orders
                WHERE id = $1;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    usersSchema.toString(),
                    usersQueries.toString()
                ),
                new SqlConfig(
                    "Orders",
                    ordersSchema.toString(),
                    ordersQueries.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "dev.example.generated"
            )
        );

        new SqlcjCompiler().compile(config);

        Path usersRepository = generatedDirectory
            .resolve("dev/example/generated")
            .resolve("UsersRepository.java");

        Path ordersRepository = generatedDirectory
            .resolve("dev/example/generated")
            .resolve("OrdersRepository.java");

        assertTrue(Files.exists(usersRepository));
        assertTrue(Files.exists(ordersRepository));

        assertTrue(Files.readString(usersRepository).contains("public GetRecordResult getRecord(Long id)"));
        assertTrue(Files.readString(ordersRepository).contains("public GetRecordResult getRecord(Long id)"));
    }

    @Test
    void shouldReportLateFailureAndLeavePreviousOutputIntact() throws IOException {
        Path usersSchema = tempDir.resolve("users-schema.sql");
        Path usersQueries = tempDir.resolve("users-queries.sql");
        Path ordersSchema = tempDir.resolve("orders-schema.sql");
        Path ordersQueries = tempDir.resolve("orders-queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");

        Files.writeString(
            usersSchema,
            """
                CREATE TABLE users
                (
                    id   BIGINT NOT NULL,
                    name VARCHAR(255)
                );
                """
        );

        Files.writeString(
            usersQueries,
            """
                -- name: GetUser :one
                SELECT id
                FROM users
                WHERE id = $1;
                """
        );

        new SqlcjCompiler().compile(
            new Config(
                List.of(new SqlConfig("Users", usersSchema.toString(), usersQueries.toString())),
                new JavaConfig(generatedDirectory.toString(), "dev.example.generated")
            )
        );

        Path generatedFile = generatedDirectory
            .resolve("dev/example/generated")
            .resolve("UsersRepository.java");

        String previous = Files.readString(generatedFile);

        assertTrue(previous.contains("resultSet.getObject(1, Long.class)"));

        Files.writeString(
            usersQueries,
            """
                -- name: GetUser :one
                SELECT name
                FROM users
                WHERE id = $1;
                """
        );

        Files.writeString(
            ordersSchema,
            """
                CREATE TABLE orders
                (
                    id BIGINT NOT NULL
                );
                """
        );

        Files.writeString(
            ordersQueries,
            """
                -- name: ListOrders :many
                SELECT id
                FROM orders;

                -- name: GetOrder :one
                SELECT id
                FROM orders
                WHERE id = $2;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig("Users", usersSchema.toString(), usersQueries.toString()),
                new SqlConfig("Orders", ordersSchema.toString(), ordersQueries.toString())
            ),
            new JavaConfig(generatedDirectory.toString(), "dev.example.generated")
        );

        SqlcjCompiler compiler = new SqlcjCompiler();

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compiler.compile(config)
        );

        assertEquals(
            "Invalid query 'GetOrder' in %s at line 5: "
                .formatted(ordersQueries)
                + "Placeholder indexes must start at $1 without gaps, but were [2]",
            exception.getMessage()
        );

        assertEquals(previous, Files.readString(generatedFile));

        assertFalse(
            Files.exists(
                generatedDirectory
                    .resolve("dev/example/generated")
                    .resolve("OrdersRepository.java")
            )
        );
    }

    @Test
    void shouldRejectAnonymousPlaceholderBeforeWriting() {
        Path generatedDirectory = tempDir.resolve("generated");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compileUsersQueries(
                """
                    -- name: ListUsers :many
                    SELECT id
                    FROM users
                    WHERE id = $1
                    LIMIT ?;
                    """,
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid query 'ListUsers' in %s at line 1: "
                .formatted(tempDir.resolve("queries.sql"))
                + "Anonymous '?' parameters are not supported; "
                + "use an indexed placeholder such as $1",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    @Test
    void shouldRejectRepeatedRepositoryMethodBeforeWriting() throws IOException {
        Path generatedDirectory = tempDir.resolve("generated");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compileUsersQueries(
                """
                    -- name: Get.User :one
                    SELECT id
                    FROM users
                    WHERE id = $1;

                    -- name: Get-User :one
                    SELECT name
                    FROM users
                    WHERE id = $1;
                    """,
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid query group 'Users' in %s: ".formatted(tempDir.resolve("queries.sql"))
                + "Queries 'Get.User' and 'Get-User' generate the same repository method 'getUser'",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /**
     * Nested result types that differ only by case compile to class files that
     * share one path on a case-insensitive filesystem.
     */
    @Test
    void shouldRejectRepositoryResultTypesThatDifferOnlyByCaseBeforeWriting() throws IOException {
        Path generatedDirectory = tempDir.resolve("generated");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compileUsersQueries(
                """
                    -- name: GetUser :one
                    SELECT id
                    FROM users
                    WHERE id = $1;

                    -- name: getuser :one
                    SELECT name
                    FROM users
                    WHERE id = $1;
                    """,
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid query group 'Users' in %s: ".formatted(tempDir.resolve("queries.sql"))
                + "Queries 'GetUser' and 'getuser' generate result types that differ only by case: "
                + "GetUserResult and GetuserResult",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    @Test
    void shouldRejectDuplicateRepositoryBeforeWriting() throws IOException {
        Path generatedDirectory = tempDir.resolve("generated");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compileUsersGroups("Users", "Users", generatedDirectory)
        );

        assertEquals(
            "Duplicate generated file for repositories 'Users' and 'Users': "
                + Path.of("generated", "UsersRepository.java"),
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /**
     * Two group names that differ only by the case of their first character
     * generate one repository name, so they collide as a duplicate path.
     */
    @Test
    void shouldRejectRepositoryNamesThatDifferOnlyByTheirFirstCharacterCase() throws IOException {
        Path generatedDirectory = tempDir.resolve("generated");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compileUsersGroups("Users", "users", generatedDirectory)
        );

        assertEquals(
            "Duplicate generated file for repositories 'Users' and 'users': "
                + Path.of("generated", "UsersRepository.java"),
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    @Test
    void shouldRejectRepositoryPathsThatDifferOnlyByCaseBeforeWriting() throws IOException {
        Path generatedDirectory = tempDir.resolve("generated");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compileUsersGroups("UserData", "Userdata", generatedDirectory)
        );

        assertEquals(
            "Generated file paths for repositories 'UserData' and 'Userdata' differ only by case: "
                + Path.of("generated", "UserDataRepository.java")
                + " and "
                + Path.of("generated", "UserdataRepository.java"),
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    @Test
    void shouldGenerateCompilableJavaForQuotedSqlIdentifiers() throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(
            schemaFile,
            """
                CREATE TABLE "user data"
                (
                    "user id" BIGINT NOT NULL,
                    "class"   VARCHAR(255)
                );
                """
        );

        Files.writeString(
            queriesFile,
            """
                -- name: ListUserData :many
                SELECT "user id", "class"
                FROM "user data"
                WHERE "class" = $1;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);

        Path generatedFile = generatedDirectory
            .resolve("generated")
            .resolve("UsersRepository.java");

        String source = Files.readString(generatedFile);

        assertTrue(source.contains("Long userId"));
        assertTrue(source.contains("String class_"));
        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(2, String.class)"));
        assertTrue(source.contains("listUserData(String class_)"));
        assertTrue(source.contains("WHERE \"class\" = ?"));

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        assertEquals(
            0,
            compilerApi.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString(),
                generatedFile.toString()
            )
        );
    }

    /**
     * A returning write reuses the typed result record, positional row mapper,
     * and result-producing executor call of a read, so the generated Java for
     * every returning write kind is verified by compiling it.
     */
    @Test
    void shouldGenerateCompilableJavaForReturningWrites() throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(
            schemaFile,
            """
                CREATE TABLE users
                (
                    id     BIGINT NOT NULL,
                    name   VARCHAR(255),
                    active BOOLEAN
                );
                """
        );

        Files.writeString(
            queriesFile,
            """
                -- name: InsertUser :one
                INSERT INTO users (id, name)
                VALUES ($1, $2)
                RETURNING *;

                -- name: UpdateUser :one
                UPDATE users
                SET name = $2
                WHERE id = $1
                RETURNING name, id;

                -- name: DeleteUsers :many
                DELETE FROM users
                WHERE active = $1
                RETURNING id, name;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);

        Path repositoryFile = generatedDirectory.resolve("generated/UsersRepository.java");

        String repository = Files.readString(repositoryFile);

        assertTrue(repository.contains("public record UsersRow("));
        assertFalse(repository.contains("InsertUserResult"));
        assertTrue(repository.contains("Long id"));
        assertTrue(repository.contains("String name"));
        assertTrue(repository.contains("Boolean active"));
        assertTrue(repository.contains("public UsersRow insertUser(Long id, String name)"));
        assertTrue(repository.contains("private static final RowMapper<UsersRow> usersRowMapper"));
        assertTrue(repository.contains("return executor.queryOne("));
        assertTrue(repository.contains("VALUES (?, ?)"));
        assertTrue(repository.contains("RETURNING *"));

        assertTrue(repository.contains("public UpdateUserResult updateUser(Long id, String name)"));
        assertTrue(repository.contains("resultSet.getObject(1, String.class)"));
        assertTrue(repository.contains("resultSet.getObject(2, Long.class)"));
        assertTrue(repository.contains("java.util.Arrays.asList(name, id)"));
        assertTrue(repository.contains("RETURNING name, id"));

        assertTrue(repository.contains("public List<DeleteUsersResult> deleteUsers(Boolean active)"));
        assertTrue(repository.contains("return executor.queryMany("));
        assertTrue(repository.contains("RETURNING id, name"));

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        assertEquals(
            0,
            compilerApi.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString(),
                repositoryFile.toString()
            )
        );

        assertTrue(Files.exists(classesDirectory.resolve("generated/UsersRepository.class")));
    }

    private void compileUsersQueries(String queries, Path generatedDirectory) throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");

        Files.writeString(
            schemaFile,
            """
                CREATE TABLE users
                (
                    id   BIGINT NOT NULL,
                    name VARCHAR(255)
                );
                """
        );

        Files.writeString(queriesFile, queries);

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    schemaFile.toString(),
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

    /** Compiles two configured groups that share one schema and query source. */
    private void compileUsersGroups(
        String firstGroup,
        String secondGroup,
        Path generatedDirectory
    ) throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");

        Files.writeString(
            schemaFile,
            """
                CREATE TABLE users
                (
                    id   BIGINT NOT NULL,
                    name VARCHAR(255)
                );
                """
        );

        Files.writeString(
            queriesFile,
            """
                -- name: GetUser :one
                SELECT id, name
                FROM users
                WHERE id = $1;
                """
        );

        Config config = new Config(
            List.of(
                new SqlConfig(firstGroup, schemaFile.toString(), queriesFile.toString()),
                new SqlConfig(secondGroup, schemaFile.toString(), queriesFile.toString())
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);
    }

    private Path generateAndCompile(String queries) throws IOException {
        return generateAndCompile(
            """
                CREATE TABLE users
                (
                    id     BIGINT NOT NULL,
                    name   VARCHAR(255),
                    active BOOLEAN
                );
                """,
            queries
        );
    }

    /** Generates and compiles the one repository of the {@code Users} group. */
    private Path generateAndCompile(String schema, String queries) throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(schemaFile, schema);

        Files.writeString(queriesFile, queries);

        Config config = new Config(
            List.of(
                new SqlConfig(
                    "Users",
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);

        Path generatedFile = generatedDirectory.resolve("generated").resolve("UsersRepository.java");

        assertTrue(Files.exists(generatedFile));

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        int compilationResult = compilerApi.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classesDirectory.toString(),
            generatedFile.toString()
        );

        assertEquals(0, compilationResult);

        return classesDirectory;
    }

    private URLClassLoader classLoader(Path classesDirectory) throws IOException {
        return new URLClassLoader(
            new URL[] { classesDirectory.toUri().toURL() },
            getClass().getClassLoader()
        );
    }

    private JdbcDataSource usersDataSource() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();

        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255),
                    active BOOLEAN
                )
                """);

            statement.execute("""
                INSERT INTO users (id, name, active)
                VALUES
                    (1, 'Alice', TRUE),
                    (2, 'Bob', FALSE)
                """);
        }

        return dataSource;
    }

    private JdbcDataSource joinDataSource() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();

        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute("""
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255)
                )
                """);

            statement.execute("""
                CREATE TABLE profiles (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    nickname VARCHAR(255)
                )
                """);

            statement.execute("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    total DECIMAL(10, 2)
                )
                """);

            statement.execute("""
                INSERT INTO users (id, name)
                VALUES (1, 'Alice'), (2, 'Bob')
                """);

            statement.execute("""
                INSERT INTO profiles (id, user_id, nickname)
                VALUES (10, 1, 'ali'), (20, 2, 'bob')
                """);

            statement.execute("""
                INSERT INTO orders (id, user_id, total)
                VALUES (100, 1, 15.50), (200, 2, 20.00)
                """);
        }

        return dataSource;
    }

    /** The declared components of one generated record, in declared order. */
    private List<String> recordComponents(String source, String recordName) {
        int start = source.indexOf("public record " + recordName + "(");

        assertTrue(start >= 0);

        return source.substring(start, source.indexOf(") {", start))
            .lines()
            .skip(1)
            .map(String::strip)
            .filter(component -> !component.isEmpty())
            .map(
                component -> component.endsWith(",")
                    ? component.substring(0, component.length() - 1)
                    : component
            )
            .toList();
    }

    private Object getRecordComponent(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }
}
