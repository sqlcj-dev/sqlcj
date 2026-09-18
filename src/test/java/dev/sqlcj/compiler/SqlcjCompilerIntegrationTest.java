package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryExecutor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Constructor;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

        Path getUserFile = generatedDirectory.resolve("generated/GetUser.java");

        Path listUsersFile = generatedDirectory.resolve("generated/ListUsers.java");

        Path findUsersFile = generatedDirectory.resolve("generated/FindUsers.java");

        assertTrue(Files.exists(getUserFile));
        assertTrue(Files.exists(listUsersFile));

        String getUser = Files.readString(getUserFile);
        assertTrue(getUser.contains("public final class GetUser"));
        assertTrue(getUser.contains("import java.time.LocalDate;"));
        assertTrue(getUser.contains("import java.time.LocalDateTime;"));
        assertTrue(getUser.contains("import java.math.BigDecimal;"));
        assertTrue(getUser.contains("public GetUserResult getUser(LocalDateTime created_at)"));
        assertTrue(getUser.contains("Long id"));
        assertTrue(getUser.contains("String name"));
        assertTrue(getUser.contains("LocalDate birth_date"));
        assertTrue(getUser.contains("LocalDateTime created_at"));
        assertTrue(getUser.contains("BigDecimal balance"));
        assertTrue(getUser.contains("private static final RowMapper<GetUserResult> ROW_MAPPER"));
        assertTrue(getUser.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(getUser.contains("resultSet.getObject(2, String.class)"));
        assertTrue(getUser.contains("resultSet.getObject(3, Boolean.class)"));
        assertTrue(getUser.contains("resultSet.getObject(4, LocalDate.class)"));
        assertTrue(getUser.contains("resultSet.getObject(5, LocalDateTime.class)"));
        assertTrue(getUser.contains("resultSet.getObject(6, BigDecimal.class)"));

        String listUsers = Files.readString(listUsersFile);
        assertTrue(listUsers.contains("public final class ListUsers"));
        assertTrue(listUsers.contains("import java.util.List;"));
        assertTrue(listUsers.contains("import java.time.LocalDate;"));
        assertTrue(listUsers.contains("import java.time.LocalDateTime;"));
        assertTrue(listUsers.contains("import java.math.BigDecimal;"));
        assertTrue(listUsers.contains("public List<ListUsersResult> listUsers(LocalDateTime created_at)"));

        String findUsers = Files.readString(findUsersFile);
        assertTrue(findUsers.contains("public final class FindUsers"));
        assertTrue(findUsers.contains("import java.util.List;"));
        assertTrue(
            findUsers
                .contains("public List<FindUsersResult> findUsers(Long id1, Long id2, Boolean active, String name)")
        );
        assertTrue(findUsers.contains("public record FindUsersResult("));
        assertTrue(findUsers.contains("Long id"));
        assertTrue(findUsers.contains("String name"));

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
            getUserFile.toString(),
            listUsersFile.toString(),
            findUsersFile.toString()
        );

        assertEquals(0, result);

        assertTrue(
            Files.exists(
                classesDirectory.resolve("generated/GetUser.class")
            )
        );

        assertTrue(
            Files.exists(
                classesDirectory.resolve("generated/ListUsers.class")
            )
        );

        assertTrue(
            Files.exists(
                classesDirectory.resolve("generated/FindUsers.class")
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

        Path getUserFile = generatedDirectory.resolve("generated/GetUser.java");

        assertTrue(Files.exists(getUserFile));

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
            getUserFile.toString()
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
                "generated.GetUser",
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

        Path listActiveUsersFile = generatedDirectory.resolve("generated/ListActiveUsers.java");

        assertTrue(Files.exists(listActiveUsersFile));

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
            listActiveUsersFile.toString()
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
                "generated.ListActiveUsers",
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
                """,
            "FindUser"
        );

        String source = Files.readString(tempDir.resolve("generated/generated/FindUser.java"));

        assertTrue(
            source.contains(
                "public FindUserResult findUser(Long id, Boolean active)"
            )
        );

        assertTrue(source.contains("List.of(active, id)"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.FindUser",
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
    void shouldExecuteGeneratedQueryWithoutParameters() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: GetFirstUser :one
                SELECT id, name
                FROM users
                ORDER BY id;
                """,
            "GetFirstUser"
        );

        String source = Files.readString(tempDir.resolve("generated/generated/GetFirstUser.java"));

        assertTrue(
            source.contains(
                "public GetFirstUserResult getFirstUser()"
            )
        );

        assertTrue(source.contains("List.of()"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.GetFirstUser",
                true,
                classLoader
            );

            Object generatedQuery = generatedClass
                .getConstructor(QueryExecutor.class)
                .newInstance(executor);

            Object result = generatedClass
                .getMethod("getFirstUser")
                .invoke(generatedQuery);

            assertNotNull(result);

            assertEquals(1L, getRecordComponent(result, "id"));
            assertEquals("Alice", getRecordComponent(result, "name"));
        }
    }

    @Test
    void shouldExecuteGeneratedInsert() throws Exception {
        Path classesDirectory = generateAndCompile(
            """
                -- name: InsertUser :exec
                INSERT INTO users (id, name, active)
                VALUES ($1, $2, $3);
                """,
            "InsertUser"
        );

        String source = Files.readString(tempDir.resolve("generated/generated/InsertUser.java"));

        assertTrue(
            source.contains(
                "public int insertUser(Long id, String name, Boolean active)"
            )
        );

        assertTrue(source.contains("return executor.execute("));
        assertTrue(source.contains("List.of(id, name, active)"));
        assertFalse(source.contains("public record InsertUserResult("));
        assertFalse(source.contains("RowMapper"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.InsertUser",
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
                executor.query(
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
                """,
            "UpdateUserName"
        );

        String source = Files.readString(tempDir.resolve("generated/generated/UpdateUserName.java"));

        assertTrue(
            source.contains(
                "public int updateUserName(Long id, String name)"
            )
        );

        assertTrue(source.contains("List.of(name, id)"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.UpdateUserName",
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
                executor.query(
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
                """,
            "DeleteUser"
        );

        String source = Files.readString(tempDir.resolve("generated/generated/DeleteUser.java"));

        assertTrue(source.contains("public int deleteUser(Long id)"));
        assertTrue(source.contains("List.of(id)"));

        QueryExecutor executor = new JdbcQueryExecutor(usersDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.DeleteUser",
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

            assertNull(
                executor.query(
                    "SELECT name FROM users WHERE id = ?",
                    List.of(2L),
                    resultSet -> resultSet.getString("name")
                )
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
                """,
            "GetUser"
        );

        String source = Files.readString(tempDir.resolve("generated/generated/GetUser.java"));

        assertTrue(source.contains("public GetUserResult getUser(Long id)"));
        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(2, String.class)"));

        QueryExecutor executor = new JdbcQueryExecutor(joinDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName("generated.GetUser", true, classLoader);

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
                """,
            "ListUserProfiles"
        );

        String source = Files.readString(
            tempDir.resolve("generated/generated/ListUserProfiles.java")
        );

        assertTrue(
            source.contains(
                "public List<ListUserProfilesResult> listUserProfiles(Long id, String nickname)"
            )
        );

        assertTrue(source.contains("List.of(nickname, id)"));
        assertTrue(source.contains("Long id1"));
        assertTrue(source.contains("Long id2"));

        QueryExecutor executor = new JdbcQueryExecutor(joinDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.ListUserProfiles",
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
                """,
            "GetUserOrder"
        );

        QueryExecutor executor = new JdbcQueryExecutor(joinDataSource());

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> generatedClass = Class.forName(
                "generated.GetUserOrder",
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
                    usersSchema.toString(),
                    usersQueries.toString()
                ),
                new SqlConfig(
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

        Path getUserFile = generatedDirectory.resolve("dev/example/generated/GetUser.java");

        Path listOrdersFile = generatedDirectory.resolve("dev/example/generated/ListOrders.java");

        assertTrue(Files.exists(getUserFile));
        assertTrue(Files.exists(listOrdersFile));

        String getUser = Files.readString(getUserFile);

        assertTrue(getUser.startsWith("package dev.example.generated;"));
        assertTrue(getUser.contains("public GetUserResult getUser(Long id)"));

        String listOrders = Files.readString(listOrdersFile);

        assertTrue(listOrders.startsWith("package dev.example.generated;"));
        assertTrue(listOrders.contains("public List<ListOrdersResult> listOrders(LocalDateTime created_at)"));
        assertTrue(listOrders.contains("BigDecimal total"));

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
            getUserFile.toString(),
            listOrdersFile.toString()
        );

        assertEquals(0, compilationResult);

        assertTrue(
            Files.exists(
                classesDirectory.resolve(
                    "dev/example/generated/GetUser.class"
                )
            )
        );

        assertTrue(
            Files.exists(
                classesDirectory.resolve(
                    "dev/example/generated/ListOrders.class"
                )
            )
        );
    }

    @Test
    void shouldRejectDuplicateQueryNameAcrossEntriesBeforeWriting() throws IOException {
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
                    usersSchema.toString(),
                    usersQueries.toString()
                ),
                new SqlConfig(
                    ordersSchema.toString(),
                    ordersQueries.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "dev.example.generated"
            )
        );

        SqlcjCompiler compiler = new SqlcjCompiler();

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> compiler.compile(config)
        );

        assertEquals(
            "Duplicate query name 'GetRecord' in query source: "
                + ordersQueries,
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    @Test
    void shouldRejectNormalizedGeneratedPathCollisionBeforeOverwriting() throws IOException {
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
            "Duplicate generated file for queries 'Get.User' and 'Get-User': "
                + Path.of("generated", "Get_User.java"),
            exception.getMessage()
        );

        String generated = Files.readString(
            generatedDirectory.resolve("generated").resolve("Get_User.java")
        );

        assertTrue(generated.contains("resultSet.getObject(1, Long.class)"));
        assertFalse(generated.contains("resultSet.getObject(1, String.class)"));
    }

    @Test
    void shouldRejectGeneratedPathsThatDifferOnlyByCaseBeforeOverwriting() {
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
            "Generated file paths for queries 'GetUser' and 'getuser' differ only by case: "
                + Path.of("generated", "GetUser.java")
                + " and "
                + Path.of("generated", "getuser.java"),
            exception.getMessage()
        );

        assertTrue(
            Files.exists(
                generatedDirectory.resolve("generated").resolve("GetUser.java")
            )
        );

        assertFalse(
            Files.exists(
                generatedDirectory.resolve("generated").resolve("getuser.java")
            )
        );
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
            .resolve("ListUserData.java");

        String source = Files.readString(generatedFile);

        assertTrue(source.contains("Long user_id"));
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

    private Path generateAndCompile(String queries, String queryName) throws IOException {
        return generateAndCompile(
            """
                CREATE TABLE users
                (
                    id     BIGINT NOT NULL,
                    name   VARCHAR(255),
                    active BOOLEAN
                );
                """,
            queries,
            queryName
        );
    }

    private Path generateAndCompile(String schema, String queries, String queryName) throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(schemaFile, schema);

        Files.writeString(queriesFile, queries);

        Config config = new Config(
            List.of(
                new SqlConfig(
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

        Path generatedFile = generatedDirectory.resolve("generated").resolve(queryName + ".java");

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

    private Object getRecordComponent(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }
}
