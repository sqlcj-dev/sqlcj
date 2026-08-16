package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        int result = compilerApi.run(
                null,
                null,
                null,
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
}
