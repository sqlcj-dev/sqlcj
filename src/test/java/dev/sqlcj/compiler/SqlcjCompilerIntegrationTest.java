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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlcjCompilerIntegrationTest {

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

        Path getUserFile =
                generatedDirectory.resolve("GetUser.java");

        Path listUsersFile =
                generatedDirectory.resolve("ListUsers.java");

        Path findUsersFile =
                generatedDirectory.resolve("FindUsers.java");

        assertTrue(Files.exists(getUserFile));
        assertTrue(Files.exists(listUsersFile));

        String getUser = Files.readString(getUserFile);
        assertTrue(getUser.contains("public final class GetUser"));
        assertTrue(getUser.contains("import java.time.LocalDate;"));
        assertTrue(getUser.contains("import java.time.LocalDateTime;"));
        assertTrue(getUser.contains("import java.math.BigDecimal;"));
        assertTrue(getUser.contains(
                "public GetUserResult getUser(LocalDateTime created_at)"
        ));
        assertTrue(getUser.contains("Long id"));
        assertTrue(getUser.contains("String name"));
        assertTrue(getUser.contains("LocalDate birth_date"));
        assertTrue(getUser.contains("LocalDateTime created_at"));
        assertTrue(getUser.contains("BigDecimal balance"));
        assertTrue(getUser.contains(
                "private static final RowMapper<GetUserResult> ROW_MAPPER"
        ));
        assertTrue(getUser.contains(
                "resultSet.getObject(\"id\", Long.class)"
        ));
        assertTrue(getUser.contains(
                "resultSet.getObject(\"name\", String.class)"
        ));
        assertTrue(getUser.contains(
                "resultSet.getObject(\"active\", Boolean.class)"
        ));
        assertTrue(getUser.contains(
                "resultSet.getObject(\"birth_date\", LocalDate.class)"
        ));
        assertTrue(getUser.contains(
                "resultSet.getObject(\"created_at\", LocalDateTime.class)"
        ));
        assertTrue(getUser.contains(
                "resultSet.getObject(\"balance\", BigDecimal.class)"
        ));

        String listUsers = Files.readString(listUsersFile);
        assertTrue(listUsers.contains("public final class ListUsers"));
        assertTrue(listUsers.contains("import java.util.List;"));
        assertTrue(listUsers.contains("import java.time.LocalDate;"));
        assertTrue(listUsers.contains("import java.time.LocalDateTime;"));
        assertTrue(listUsers.contains("import java.math.BigDecimal;"));
        assertTrue(listUsers.contains(
                "public List<ListUsersResult> listUsers(LocalDateTime created_at)"
        ));

        String findUsers = Files.readString(findUsersFile);
        assertTrue(findUsers.contains("public final class FindUsers"));
        assertTrue(findUsers.contains("import java.util.List;"));

        assertTrue(findUsers.contains(
                "public List<FindUsersResult> findUsers(Long id1, Long id2, Boolean active, String name)"
        ));

        assertTrue(findUsers.contains(
                "public record FindUsersResult("
        ));

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

        Path getUserFile = generatedDirectory.resolve("GetUser.java");

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
            Class<?> generatedClass =
                    Class.forName(
                            "generated.GetUser",
                            true,
                            classLoader
                    );

            Constructor<?> constructor =
                    generatedClass.getConstructor(
                            QueryExecutor.class
                    );

            Object generatedQuery =
                    constructor.newInstance(executor);

            Method method =
                    generatedClass.getMethod(
                            "getUser",
                            LocalDateTime.class
                    );

            Object result =
                    method.invoke(
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

        Path listActiveUsersFile =
                generatedDirectory.resolve("ListActiveUsers.java");

        assertTrue(Files.exists(listActiveUsersFile));

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi =
                ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        String classpath =
                System.getProperty("java.class.path");

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

        QueryExecutor executor =
                new JdbcQueryExecutor(dataSource);

        URL[] classpathUrls = {
                classesDirectory.toUri().toURL()
        };

        try (URLClassLoader classLoader =
                     new URLClassLoader(
                             classpathUrls,
                             getClass().getClassLoader()
                     )) {

            Class<?> generatedClass =
                    Class.forName(
                            "generated.ListActiveUsers",
                            true,
                            classLoader
                    );

            Constructor<?> constructor =
                    generatedClass.getConstructor(
                            QueryExecutor.class
                    );

            Object generatedQuery =
                    constructor.newInstance(executor);

            Method method =
                    generatedClass.getMethod(
                            "listActiveUsers",
                            Boolean.class
                    );

            Object result =
                    method.invoke(
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

    private Object getRecordComponent(
            Object record,
            String componentName
    ) throws Exception {
        return record
                .getClass()
                .getMethod(componentName)
                .invoke(record);
    }
}
