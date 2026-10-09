package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryGroupModel;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.EnumType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeGeneratorTest {

    private static final String SQL = "SELECT 1";

    private static final String GROUP = "Users";

    private final CodeGenerator codeGenerator = new JavaCodeGenerator();

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateFilePath() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertEquals(
            Path.of("generated", "UsersRepository.java"),
            file.path()
        );
    }

    @Test
    void shouldGenerateClassName() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(file.content().contains("public final class UsersRepository"));
    }

    @Test
    void shouldGenerateMethodName() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(file.content().contains("getUser("));
    }

    @Test
    void shouldGenerateSingleParameter() {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        assertTrue(
            file.content().contains("Long id")
        );
    }

    @Test
    void shouldGenerateMultipleParameters() {
        GeneratedFile file = generate(
            query(
                "ListUsersByIdAndName",
                QueryType.MANY,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "name", ColumnType.VARCHAR)
                )
            )
        );

        assertTrue(
            file.content().contains(
                "Long id, String name"
            )
        );
    }

    @Test
    void shouldGenerateMethodWithoutParameters() {
        GeneratedFile file = generate(
            query(
                "ListUsers",
                QueryType.MANY,
                List.of()
            )
        );

        assertTrue(
            file.content().contains(
                "public List<ListUsersResult> listUsers()"
            )
        );
    }

    @Test
    void shouldGenerateJavaDoc() {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        String source = file.content();

        assertTrue(source.contains("Query: GetUser"));
        assertTrue(source.contains("Table: users"));
        assertTrue(source.contains("Type: ONE"));
    }

    @Test
    void shouldGeneratePackageDeclaration() {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        assertTrue(
            file.content().startsWith(
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage generated;"
            )
        );
    }

    @Test
    void shouldGenerateDoNotEditNoticeBeforePackageDeclaration() {
        CodeGenerator generator = new JavaCodeGenerator("dev.example.generated");

        GeneratedFile file = generate(
            generator,
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT)
                )
            )
        );

        String source = file.content();

        assertTrue(
            source.startsWith(
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage dev.example.generated;\n"
            )
        );

        assertEquals(
            1,
            source.split("// Code generated by sqlcj\\. DO NOT EDIT\\.", -1).length - 1
        );

        assertTrue(source.contains(" * Generated by sqlcj."));
    }

    @Test
    void shouldGenerateConfiguredPackageDeclarationAndPath() throws IOException {
        CodeGenerator generator = new JavaCodeGenerator("dev.example.generated");

        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            null
        );

        GeneratedFile file = generate(generator, query);

        assertEquals(
            Path.of("dev", "example", "generated", "UsersRepository.java"),
            file.path()
        );

        assertTrue(
            file.content().startsWith(
                "// Code generated by sqlcj. DO NOT EDIT.\n\npackage dev.example.generated;"
            )
        );

        assertCompiles(file);
    }

    @Test
    void shouldGenerateResultRecord() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("active", ColumnType.BOOLEAN, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("public record GetUserResult("));
        assertTrue(source.contains("Long id,"));
        assertTrue(source.contains("String name,"));
        assertTrue(source.contains("Boolean active"));
        assertTrue(source.contains(") {"));
    }

    @Test
    void shouldGenerateSingleResultReturnType() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public GetUserResult getUser()"
            )
        );
    }

    @Test
    void shouldGenerateListResultReturnType() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(file.content().contains("import java.util.List;"));

        assertTrue(
            file.content().contains(
                "public List<ListUsersResult> listUsers()"
            )
        );
    }

    @Test
    void shouldGenerateTypedMethodParameters() {
        QueryModel query = new QueryModel(
            "ListUsersByIdAndName",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1, 2),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public List<ListUsersByIdAndNameResult> listUsersByIdAndName(Long id, String name)"
            )
        );
    }

    @Test
    void shouldGenerateSingleResultWithTypedParameter() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public GetUserResult getUser(Long id)"
            )
        );
    }

    @Test
    void shouldGenerateListImportForSingleResultWithoutParameters() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("java.util.Arrays.asList()"));
    }

    @Test
    void shouldGenerateParameterNamesFromQueryParameters() {
        QueryModel query = query(
            "GetUser",
            QueryType.ONE,
            List.of(
                new QueryParameter(1, "userId", ColumnType.BIGINT),
                new QueryParameter(2, "enabled", ColumnType.BOOLEAN)
            )
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "Long userId, Boolean enabled"
            )
        );
    }

    @Test
    void shouldGenerateDistinctComponentsAndPositionalReadsForDuplicateColumns() throws IOException {
        QueryModel query = new QueryModel(
            "ListUserProfiles",
            QueryType.MANY,
            "users",
            """
                SELECT u.id, p.id, p.nickname
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                """,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("Long id1"));
        assertTrue(source.contains("Long id2"));
        assertTrue(source.contains("String nickname"));

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(2, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(3, String.class)"));

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER, Integer",
            "BIGINT, Long",
            "SMALLINT, Short",
            "BOOLEAN, Boolean",
            "VARCHAR, String",
            "TEXT, String",
            "DATE, LocalDate",
            "TIMESTAMP, LocalDateTime",
            "TIMESTAMP_WITH_TIME_ZONE, OffsetDateTime",
            "DECIMAL, BigDecimal",
            "UUID, UUID",
            "REAL, Float",
            "DOUBLE_PRECISION, Double",
            "BYTEA, byte[]",
            "TIME, LocalTime",
            "JSON, String",
            "JSONB, String"
        }
    )
    void shouldGenerateJavaTypeForQueryParameter(
        ColumnType columnType,
        String expectedJavaType
    ) {
        GeneratedFile file = generate(
            query(
                "GetUser",
                QueryType.ONE,
                List.of(
                    new QueryParameter(1, "value", columnType)
                )
            )
        );

        assertTrue(
            file.content().contains(
                expectedJavaType + " value"
            )
        );
    }

    @Test
    void shouldGenerateQuerySpecificResultRecord() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public record ListUsersResult("
            )
        );
    }

    @Test
    void shouldGenerateImportForLocalDate() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "birthDate",
                    ColumnType.DATE,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.time.LocalDate;"
            )
        );
    }

    @Test
    void shouldGenerateImportForLocalTime() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "openedAt",
                    ColumnType.TIME,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.time.LocalTime;"
            )
        );
    }

    @Test
    void shouldGenerateImportForBigDecimal() {
        QueryModel query = new QueryModel(
            "GetAccount",
            QueryType.ONE,
            "accounts",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "balance",
                    ColumnType.DECIMAL,
                    false
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.math.BigDecimal;"
            )
        );
    }

    @Test
    void shouldGenerateImportsForUuidAndTimestampWithTimeZone() throws IOException {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1, 2),
            List.of(
                new QueryColumn(
                    "external_id",
                    ColumnType.UUID,
                    true
                ),
                new QueryColumn(
                    "created_at",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "externalId",
                    ColumnType.UUID
                ),
                new QueryParameter(
                    2,
                    "createdAt",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.UUID;"));
        assertTrue(source.contains("import java.time.OffsetDateTime;"));

        assertTrue(
            source.contains(
                "public GetUserResult getUser(UUID externalId, OffsetDateTime createdAt)"
            )
        );

        assertTrue(source.contains("UUID externalId"));
        assertTrue(source.contains("OffsetDateTime createdAt"));

        assertTrue(source.contains("resultSet.getObject(1, UUID.class)"));
        assertTrue(source.contains("resultSet.getObject(2, OffsetDateTime.class)"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateImportForQueryParameterType() {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "createdAt",
                    ColumnType.TIMESTAMP
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "import java.time.LocalDateTime;"
            )
        );
    }

    @Test
    void shouldGenerateEachImportOnlyOnce() {
        QueryModel query = new QueryModel(
            "FindUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "createdAt",
                    ColumnType.TIMESTAMP,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "updatedAt",
                    ColumnType.TIMESTAMP
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.equals("import java.time.LocalDateTime;"))
                .count()
        );
    }

    @Test
    void shouldGenerateMultipleRequiredImports() {
        QueryModel query = new QueryModel(
            "ListAccounts",
            QueryType.MANY,
            "accounts",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "createdAt",
                    ColumnType.TIMESTAMP,
                    false
                ),
                new QueryColumn(
                    "balance",
                    ColumnType.DECIMAL,
                    false
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("import java.time.LocalDateTime;"));
        assertTrue(source.contains("import java.math.BigDecimal;"));
    }

    @Test
    void shouldGenerateQueryExecutionMethodBody() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "id",
                    ColumnType.BIGINT
                )
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(source.contains("return executor.queryOne("));
        assertTrue(source.contains("java.util.Arrays.asList(id)"));
        assertTrue(source.contains("getUserRowMapper"));
        assertFalse(source.contains("UnsupportedOperationException"));
    }

    @Test
    void shouldGenerateCompilableJavaSource() throws IOException {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                ),
                new QueryColumn(
                    "birth_date",
                    ColumnType.DATE,
                    true
                ),
                new QueryColumn(
                    "created_at",
                    ColumnType.TIMESTAMP,
                    true
                ),
                new QueryColumn(
                    "balance",
                    ColumnType.DECIMAL,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "created_at",
                    ColumnType.TIMESTAMP
                )
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    /**
     * Compiles a repository that binds and reads the floating-point, binary,
     * and time types, including a query whose only binding parameter is the
     * array type {@code byte[]}.
     */
    @Test
    void shouldGenerateCompilableJavaSourceForFloatingPointBinaryAndTimeTypes()
        throws IOException {
        QueryModel listMeasurements = new QueryModel(
            "ListMeasurements",
            QueryType.MANY,
            "measurements",
            SQL,
            List.of(1, 2, 3, 4),
            List.of(
                new QueryColumn(
                    "amount",
                    ColumnType.REAL,
                    true
                ),
                new QueryColumn(
                    "ratio",
                    ColumnType.DOUBLE_PRECISION,
                    true
                ),
                new QueryColumn(
                    "payload",
                    ColumnType.BYTEA,
                    true
                ),
                new QueryColumn(
                    "opened_at",
                    ColumnType.TIME,
                    true
                )
            ),
            List.of(
                new QueryParameter(1, "amount", ColumnType.REAL),
                new QueryParameter(2, "ratio", ColumnType.DOUBLE_PRECISION),
                new QueryParameter(3, "payload", ColumnType.BYTEA),
                new QueryParameter(4, "opened_at", ColumnType.TIME)
            ),
            null
        );

        QueryModel getMeasurementByPayload = new QueryModel(
            "GetMeasurementByPayload",
            QueryType.ONE,
            "measurements",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "payload",
                    ColumnType.BYTEA,
                    false
                )
            ),
            List.of(
                new QueryParameter(1, "payload", ColumnType.BYTEA)
            ),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(listMeasurements, getMeasurementByPayload)
            )
        );

        String source = file.content();

        assertTrue(source.contains("import java.time.LocalTime;"));
        assertTrue(source.contains("Float amount"));
        assertTrue(source.contains("Double ratio"));
        assertTrue(source.contains("byte[] payload"));
        assertTrue(source.contains("LocalTime openedAt"));
        assertTrue(source.contains("resultSet.getObject(3, byte[].class)"));

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    /**
     * A JSON parameter is bound through {@code dev.sqlcj.runtime.UntypedText}
     * at every position its placeholder index is bound at, and a JSON result
     * column is read as text. The other {@code String} types are bound and read
     * exactly as before, and the wrapper is written out in full, so the
     * generated imports are unchanged.
     */
    @Test
    void shouldWrapJsonArgumentsAndReadJsonColumnsAsText() throws IOException {
        QueryModel query = new QueryModel(
            "FindDocument",
            QueryType.ONE,
            "documents",
            """
                SELECT id, metadata, profile, name
                FROM documents
                WHERE name = ?
                  AND (metadata = ? OR metadata = ?)
                  AND bio = ?
                  AND profile = ?
                """,
            List.of(3, 1, 1, 4, 2),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("metadata", ColumnType.JSONB, true),
                new QueryColumn("profile", ColumnType.JSON, true),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "metadata", ColumnType.JSONB),
                new QueryParameter(2, "profile", ColumnType.JSON),
                new QueryParameter(3, "name", ColumnType.VARCHAR),
                new QueryParameter(4, "bio", ColumnType.TEXT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public FindDocumentResult findDocument("
                    + "String metadata, String profile, String name, String bio)"
            )
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "name, "
                    + "new dev.sqlcj.runtime.UntypedText(metadata), "
                    + "new dev.sqlcj.runtime.UntypedText(metadata), "
                    + "bio, "
                    + "new dev.sqlcj.runtime.UntypedText(profile))"
            )
        );

        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getString(2)"));
        assertTrue(source.contains("resultSet.getString(3)"));
        assertTrue(source.contains("resultSet.getObject(4, String.class)"));

        assertFalse(source.contains("import dev.sqlcj.runtime.UntypedText;"));

        assertCompiles(file);
    }

    /**
     * Compiles a repository that binds JSON text in a write and binds and reads
     * it in a query.
     */
    @Test
    void shouldGenerateCompilableJavaSourceForJsonTypes() throws IOException {
        QueryModel insertDocument = new QueryModel(
            "InsertDocument",
            QueryType.EXEC,
            "documents",
            SQL,
            List.of(1, 2, 3),
            List.of(),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "metadata", ColumnType.JSONB),
                new QueryParameter(3, "profile", ColumnType.JSON)
            ),
            null
        );

        QueryModel getDocument = new QueryModel(
            "GetDocument",
            QueryType.ONE,
            "documents",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn("metadata", ColumnType.JSONB, true),
                new QueryColumn("profile", ColumnType.JSON, true)
            ),
            List.of(
                new QueryParameter(1, "metadata", ColumnType.JSONB)
            ),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(insertDocument, getDocument)
            )
        );

        String source = file.content();

        assertTrue(source.contains("String metadata, String profile"));
        assertTrue(source.contains("resultSet.getString(1)"));

        assertEquals(0, compile(file, "UsersRepository.java"));
    }

    /** Compiles one generated source file in an isolated temporary location. */
    private int compile(GeneratedFile file, String fileName) throws IOException {
        Path sourceDirectory = tempDir.resolve("generated");
        Path sourceFile = sourceDirectory.resolve(fileName);
        Path outputDirectory = tempDir.resolve("classes");

        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputDirectory);

        Files.writeString(sourceFile, file.content());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler);

        return compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            outputDirectory.toString(),
            sourceFile.toString()
        );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER, Integer",
            "BIGINT, Long",
            "SMALLINT, Short",
            "BOOLEAN, Boolean",
            "VARCHAR, String",
            "TEXT, String",
            "DATE, LocalDate",
            "TIMESTAMP, LocalDateTime",
            "TIMESTAMP_WITH_TIME_ZONE, OffsetDateTime",
            "DECIMAL, BigDecimal",
            "UUID, UUID",
            "REAL, Float",
            "DOUBLE_PRECISION, Double",
            "BYTEA, byte[]",
            "TIME, LocalTime",
            "JSON, String",
            "JSONB, String"
        }
    )
    void shouldGenerateJavaTypeForResultColumn(ColumnType columnType, String expectedJavaType) {
        QueryModel query = new QueryModel(
            "GetValue",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "value",
                    columnType,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                expectedJavaType + " value"
            )
        );
    }

    @Test
    void shouldGenerateWrapperTypeForNullableColumn() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    true
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains("Long id")
        );
    }

    @Test
    void shouldGenerateWrapperTypeForNonNullableColumn() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                )
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains("Long id")
        );
    }

    @Test
    void shouldGenerateUniqueParameterNamesForDuplicateColumns() {
        QueryModel query = new QueryModel(
            "FindUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1, 2),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            null
        );

        GeneratedFile file = generate(query);

        assertTrue(
            file.content().contains(
                "public List<FindUsersResult> findUsers(Long id1, Long id2)"
            )
        );
    }

    @Test
    void shouldGenerateRowMapperForOneQuery() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("birth_date", ColumnType.DATE, true),
                new QueryColumn("created_at", ColumnType.TIMESTAMP, true),
                new QueryColumn("balance", ColumnType.DECIMAL, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "import dev.sqlcj.runtime.RowMapper;"
            )
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<GetUserResult> getUserRowMapper"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(1, Long.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(2, String.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(3, LocalDate.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(4, LocalDateTime.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(5, BigDecimal.class)"
            )
        );
    }

    @Test
    void shouldPreserveResultColumnOrder() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            List.of(),
            null
        );

        String source = generate(query).content();

        int nameIndex = source.indexOf("resultSet.getObject(1, String.class)");

        int idIndex = source.indexOf("resultSet.getObject(2, Long.class)");

        assertTrue(nameIndex < idIndex);
    }

    @Test
    void shouldGenerateRowMapperForManyQuery() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "import dev.sqlcj.runtime.RowMapper;"
            )
        );

        assertTrue(
            source.contains(
                "private static final RowMapper<ListUsersResult> listUsersRowMapper"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(1, Long.class)"
            )
        );

        assertTrue(
            source.contains(
                "resultSet.getObject(2, String.class)"
            )
        );
    }

    @Test
    void shouldGenerateQueryExecutorImport() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "import dev.sqlcj.runtime.QueryExecutor;"
            )
        );
    }

    @Test
    void shouldGenerateQueryExecutorField() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "private final QueryExecutor executor;"
            )
        );
    }

    @Test
    void shouldGenerateQueryExecutorConstructor() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "public UsersRepository(QueryExecutor executor)"
            )
        );

        assertTrue(
            source.contains(
                "this.executor = executor;"
            )
        );
    }

    @Test
    void shouldGenerateExecutionForOneQuery() {
        QueryModel query = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                ),
                new QueryColumn(
                    "name",
                    ColumnType.VARCHAR,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "id",
                    ColumnType.BIGINT
                )
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains("""
                return executor.queryOne(
                """)
        );

        assertTrue(
            source.contains("""
                WHERE id = ?
                """)
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList(id)"
            )
        );

        assertTrue(
            source.contains(
                "getUserRowMapper"
            )
        );

        assertFalse(
            source.contains("UnsupportedOperationException")
        );

        assertTrue(
            source.contains("""
                return executor.queryOne(
                """)
        );

        assertFalse(
            source.contains(
                "return executor.queryMany("
            )
        );
    }

    @Test
    void shouldGenerateQueryManyExecutionForManyQuery() {
        QueryModel query = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(1),
            List.of(
                new QueryColumn(
                    "id",
                    ColumnType.BIGINT,
                    false
                ),
                new QueryColumn(
                    "name",
                    ColumnType.VARCHAR,
                    true
                )
            ),
            List.of(
                new QueryParameter(
                    1,
                    "active",
                    ColumnType.BOOLEAN
                )
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains("""
                return executor.queryMany(
                """)
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList(active)"
            )
        );

        assertTrue(
            source.contains(
                "listUsersRowMapper"
            )
        );

        assertFalse(
            source.contains(
                "return executor.queryOne("
            )
        );
    }

    @Test
    void shouldPreserveParameterOrderWhenGeneratingExecution() {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                  AND id = ?
                  AND name = ?
                """,
            List.of(1, 2, 3),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "active", ColumnType.BOOLEAN),
                new QueryParameter(2, "id", ColumnType.BIGINT),
                new QueryParameter(3, "name", ColumnType.VARCHAR)
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(
            source.contains(
                "java.util.Arrays.asList(active, id, name)"
            )
        );
    }

    @Test
    void shouldGenerateLogicalParametersWithTextualBindingOrder() throws IOException {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                  AND id = ?
                """,
            List.of(2, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "active", ColumnType.BOOLEAN)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public FindUserResult findUser(Long id, Boolean active)"
            )
        );

        assertTrue(source.contains("WHERE active = ?"));
        assertTrue(source.contains("AND id = ?"));
        assertTrue(source.contains("java.util.Arrays.asList(active, id)"));

        assertCompiles(file);
    }

    @Test
    void shouldRepeatArgumentForRepeatedPlaceholderIndex() throws IOException {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE name = ?
                  AND (id = ? OR id = ?)
                """,
            List.of(2, 1, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public FindUserResult findUser(Long id, String name)"
            )
        );

        assertTrue(source.contains("java.util.Arrays.asList(name, id, id)"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateEmptyArgumentListForQueryWithoutParameters() throws IOException {
        QueryModel query = new QueryModel(
            "GetFirstUser",
            QueryType.ONE,
            "users",
            """
                SELECT id, name
                FROM users
                """,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public GetFirstUserResult getFirstUser()"
            )
        );

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("java.util.Arrays.asList()"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateExecMethodForWriteQuery() throws IOException {
        QueryModel query = new QueryModel(
            "InsertUser",
            QueryType.EXEC,
            "users",
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                """,
            List.of(1, 2),
            List.of(),
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(
            source.contains(
                "public int insertUser(Long id, String name)"
            )
        );

        assertTrue(source.contains("return executor.execute("));
        assertTrue(source.contains("INSERT INTO users (id, name)"));
        assertTrue(source.contains("java.util.Arrays.asList(id, name)"));

        assertFalse(source.contains("public record InsertUserResult("));
        assertFalse(source.contains("RowMapper"));
        assertFalse(source.contains("UnsupportedOperationException"));

        assertCompiles(file);
    }

    /** Compiles the generated files of one package together. */
    private void assertCompiles(GeneratedFile... files) throws IOException {
        Path sourceDirectory = tempDir.resolve("generated");
        Path outputDirectory = tempDir.resolve("classes");

        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputDirectory);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                outputDirectory.toString()
            )
        );

        for (GeneratedFile file : files) {
            Path sourceFile = sourceDirectory.resolve(file.path());

            Files.createDirectories(sourceFile.getParent());

            Files.writeString(sourceFile, file.content());

            arguments.add(sourceFile.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler);

        int result = compiler.run(
            null,
            null,
            null,
            arguments.toArray(new String[0])
        );

        assertEquals(0, result);
    }

    /** Compiles one repository together with the row records it returns. */
    private void assertCompilesWithRows(GeneratedFile repository) throws IOException {
        List<GeneratedFile> files = new ArrayList<>();

        files.add(repository);
        files.addAll(codeGenerator.generateRows());

        assertCompiles(files.toArray(new GeneratedFile[0]));
    }

    /**
     * One group holding every supported query kind becomes one repository with
     * one executor field, one constructor, and one method per query.
     */
    @Test
    void shouldGenerateOneRepositoryForEveryQueryOfTheGroup() throws IOException {
        QueryModel createUser = new QueryModel(
            "CreateUser",
            QueryType.ONE,
            "users",
            "INSERT INTO users (name) VALUES (?) RETURNING id, name",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            null
        );

        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT id FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT id, birth_date FROM users",
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("birth_date", ColumnType.DATE, true)
            ),
            List.of(),
            null
        );

        QueryModel deleteUser = new QueryModel(
            "DeleteUser",
            QueryType.EXEC,
            "users",
            "DELETE FROM users WHERE id = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(createUser, getUser, listUsers, deleteUser)
            )
        );

        String source = file.content();

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.equals("    private final QueryExecutor executor;"))
                .count()
        );

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.contains("public UsersRepository(QueryExecutor executor)"))
                .count()
        );

        assertTrue(source.contains("public CreateUserResult createUser(String name)"));
        assertTrue(source.contains("public GetUserResult getUser(Long id)"));
        assertTrue(source.contains("public List<ListUsersResult> listUsers()"));
        assertTrue(source.contains("public int deleteUser(Long id)"));

        assertTrue(source.contains("public record CreateUserResult("));
        assertTrue(source.contains("public record GetUserResult("));
        assertTrue(source.contains("public record ListUsersResult("));
        assertFalse(source.contains("public record DeleteUserResult("));

        assertTrue(source.contains("private static final RowMapper<CreateUserResult> createUserRowMapper"));
        assertTrue(source.contains("private static final RowMapper<GetUserResult> getUserRowMapper"));
        assertTrue(source.contains("private static final RowMapper<ListUsersResult> listUsersRowMapper"));

        assertTrue(source.contains("import java.time.LocalDate;"));

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.equals("import dev.sqlcj.runtime.RowMapper;"))
                .count()
        );

        assertTrue(source.indexOf("createUser(") < source.indexOf("getUser("));
        assertTrue(source.indexOf("getUser(") < source.indexOf("listUsers("));
        assertTrue(source.indexOf("listUsers(") < source.indexOf("deleteUser("));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateRepositoryWithoutMethodsForEmptyGroup() throws IOException {
        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of())
        );

        String source = file.content();

        assertEquals(Path.of("generated", "UsersRepository.java"), file.path());
        assertTrue(source.contains("public final class UsersRepository {"));
        assertTrue(source.contains("private final QueryExecutor executor;"));
        assertTrue(source.contains("public UsersRepository(QueryExecutor executor)"));
        assertFalse(source.contains("RowMapper"));
        assertFalse(source.contains("public record"));

        assertCompiles(file);
    }

    /**
     * Every query that returns one complete row of a table returns the
     * package's single row record, which the repository reads through one row
     * mapper generated after the constructor.
     */
    @Test
    void shouldShareOneRowRecordAcrossFullRowQueriesOfOneTable() throws IOException {
        List<QueryColumn> columns = List.of(
            new QueryColumn("id", ColumnType.BIGINT, false),
            new QueryColumn("name", ColumnType.VARCHAR, true),
            new QueryColumn("created_at", ColumnType.TIMESTAMP, true)
        );

        QueryModel createUser = new QueryModel(
            "CreateUser",
            QueryType.ONE,
            "users",
            "INSERT INTO users (name) VALUES (?) RETURNING *",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            "users"
        );

        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT u.* FROM users u",
            List.of(),
            columns,
            List.of(),
            "users"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(createUser, getUser, listUsers)
            )
        );

        String source = file.content();

        assertFalse(source.contains("public record"));

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.contains("private static final RowMapper<UsersRow> usersRowMapper"))
                .count()
        );

        assertFalse(source.contains("Result"));

        assertTrue(source.contains("public UsersRow createUser(String name)"));
        assertTrue(source.contains("public UsersRow getUser(Long id)"));
        assertTrue(source.contains("public List<UsersRow> listUsers()"));

        assertEquals(
            3,
            source.lines()
                .filter(line -> line.strip().equals("usersRowMapper"))
                .count()
        );

        assertTrue(source.contains("resultSet.getObject(3, LocalDateTime.class)"));

        assertTrue(
            source.indexOf("public UsersRepository(QueryExecutor executor)") < source
                .indexOf("private static final RowMapper<UsersRow> usersRowMapper")
        );

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(1, rows.size());

        GeneratedFile row = rows.get(0);

        assertEquals(Path.of("generated", "UsersRow.java"), row.path());

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated;

                import java.time.LocalDateTime;

                /**
                 * Generated by sqlcj.
                 *
                 * Table: users
                 */

                public record UsersRow(
                    Long id,
                    String name,
                    LocalDateTime createdAt
                ) {
                }
                """,
            row.content()
        );

        assertCompiles(file, row);
    }

    @Test
    void shouldGenerateOptionalExecutionForOptionalQuery() throws IOException {
        QueryModel query = new QueryModel(
            "FindUser",
            QueryType.OPTIONAL,
            "users",
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.Optional;"));
        assertTrue(source.contains("public record FindUserResult("));
        assertTrue(
            source.contains("private static final RowMapper<FindUserResult> findUserRowMapper =")
        );
        assertTrue(source.contains("public Optional<FindUserResult> findUser(Long id)"));
        assertTrue(source.contains("return executor.queryOptional("));
        assertTrue(source.contains("java.util.Arrays.asList(id)"));
        assertTrue(source.contains("Type: OPTIONAL"));

        assertFalse(source.contains("return executor.queryOne("));
        assertFalse(source.contains("UnsupportedOperationException"));

        assertCompiles(file);
    }

    @Test
    void shouldShareOneRowRecordBetweenOptionalAndOtherFullRowQueries() throws IOException {
        List<QueryColumn> columns = List.of(
            new QueryColumn("id", ColumnType.BIGINT, false),
            new QueryColumn("name", ColumnType.VARCHAR, true)
        );

        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        QueryModel findUser = new QueryModel(
            "FindUser",
            QueryType.OPTIONAL,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            columns,
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUser, findUser)
            )
        );

        String source = file.content();

        assertFalse(source.contains("public record"));

        assertEquals(
            1,
            source.lines()
                .filter(line -> line.contains("private static final RowMapper<UsersRow> usersRowMapper"))
                .count()
        );

        assertTrue(source.contains("public UsersRow getUser(Long id)"));
        assertTrue(source.contains("public Optional<UsersRow> findUser(Long id)"));

        assertEquals(
            1,
            codeGenerator.generateRows().size()
        );

        assertCompilesWithRows(file);
    }

    @Test
    void shouldNotImportOptionalWithoutAnOptionalQuery() {
        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            null
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            null
        );

        String source = codeGenerator
            .generate(
                new QueryGroupModel(
                    GROUP,
                    List.of(getUser, listUsers)
                )
            )
            .content();

        assertTrue(source.contains("import java.util.List;"));
        assertFalse(source.contains("import java.util.Optional;"));
    }

    /**
     * Every executor call names the generated repository and the query it was
     * generated from, so a runtime failure identifies the method the
     * application called.
     */
    @Test
    void shouldPassRepositoryAndQueryIdentityToEveryExecutorCall() throws IOException {
        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT id FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel findUser = new QueryModel(
            "FindUser",
            QueryType.OPTIONAL,
            "users",
            "SELECT id FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        QueryModel listUsers = new QueryModel(
            "ListUsers",
            QueryType.MANY,
            "users",
            "SELECT id FROM users",
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            null
        );

        QueryModel deleteUser = new QueryModel(
            "DeleteUser",
            QueryType.EXEC,
            "users",
            "DELETE FROM users WHERE id = ?",
            List.of(1),
            List.of(),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            null
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUser, findUser, listUsers, deleteUser)
            )
        );

        String source = file.content();

        assertTrue(
            source.contains("""
                        return executor.queryOne(
                                "UsersRepository",
                                "GetUser",
                """)
        );

        assertTrue(
            source.contains("""
                        return executor.queryOptional(
                                "UsersRepository",
                                "FindUser",
                """)
        );

        assertTrue(
            source.contains("""
                        return executor.queryMany(
                                "UsersRepository",
                                "ListUsers",
                """)
        );

        assertTrue(
            source.contains("""
                        return executor.execute(
                                "UsersRepository",
                                "DeleteUser",
                """)
        );

        assertCompiles(file);
    }

    @Test
    void shouldGenerateOneRowRecordPerRowTable() throws IOException {
        QueryModel getUser = new QueryModel(
            "GetUser",
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );

        QueryModel getOrder = new QueryModel(
            "GetOrder",
            QueryType.ONE,
            "orders",
            "SELECT * FROM orders WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "orders"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(getUser, getOrder)
            )
        );

        String source = file.content();

        assertFalse(source.contains("public record"));
        assertTrue(source.contains("private static final RowMapper<UsersRow> usersRowMapper"));
        assertTrue(source.contains("private static final RowMapper<OrdersRow> ordersRowMapper"));
        assertTrue(source.contains("public UsersRow getUser(Long id)"));
        assertTrue(source.contains("public OrdersRow getOrder(Long id)"));

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(
            List.of(
                Path.of("generated", "UsersRow.java"),
                Path.of("generated", "OrdersRow.java")
            ),
            rows.stream().map(GeneratedFile::path).toList()
        );

        assertTrue(rows.get(0).content().contains("public record UsersRow("));
        assertTrue(rows.get(1).content().contains("public record OrdersRow("));

        assertCompilesWithRows(file);
    }

    /**
     * A row mapper yields to the mapper of a query that still generates its
     * own, so the two fields stay distinct.
     */
    @Test
    void shouldDisambiguateRowMapperFromQueryRowMapper() throws IOException {
        QueryModel authors = new QueryModel(
            "Authors",
            QueryType.MANY,
            "authors",
            "SELECT id, name FROM authors",
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(),
            null
        );

        QueryModel getAuthor = new QueryModel(
            "GetAuthor",
            QueryType.ONE,
            "authors",
            "SELECT * FROM authors WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("bio", ColumnType.TEXT, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "authors"
        );

        GeneratedFile file = codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(authors, getAuthor)
            )
        );

        String source = file.content();

        assertTrue(source.contains("private static final RowMapper<AuthorsResult> authorsRowMapper ="));
        assertTrue(source.contains("private static final RowMapper<AuthorsRow> authorsRowMapper1 ="));
        assertTrue(source.contains("public List<AuthorsResult> authors()"));
        assertTrue(source.contains("public AuthorsRow getAuthor(Long id)"));

        assertCompilesWithRows(file);
    }

    @Test
    void shouldRejectRowTypesThatAreEqualIgnoringCase() {
        QueryModel getUserData = new QueryModel(
            "GetUserData",
            QueryType.ONE,
            "user_data",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "user_data"
        );

        QueryModel fetchUserdata = new QueryModel(
            "FetchUserdata",
            QueryType.ONE,
            "userdata",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "userdata"
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(
                new QueryGroupModel(
                    GROUP,
                    List.of(getUserData, fetchUserdata)
                )
            )
        );

        assertEquals(
            "Tables 'user_data' and 'userdata' generate row types that are equal ignoring case: "
                + "UserDataRow and UserdataRow",
            exception.getMessage()
        );
    }

    @Test
    void shouldGenerateNoRowRecordWithoutAFullRowQuery() {
        generate(
            new QueryModel(
                "GetUser",
                QueryType.ONE,
                "users",
                SQL,
                List.of(),
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                List.of(),
                null
            )
        );

        assertEquals(List.of(), codeGenerator.generateRows());
    }

    /**
     * Two groups of one package that return the full row of one table share the
     * single row record the package generates for it.
     */
    @Test
    void shouldGenerateOneRowRecordForTwoGroupsReturningTheSameRow() throws IOException {
        GeneratedFile users = codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(getUsersRow("GetUser")))
        );

        GeneratedFile admins = codeGenerator.generate(
            new QueryGroupModel("Admins", List.of(getUsersRow("GetAdmin")))
        );

        List<GeneratedFile> rows = codeGenerator.generateRows();

        assertEquals(1, rows.size());
        assertEquals(Path.of("generated", "UsersRow.java"), rows.get(0).path());

        assertTrue(users.content().contains("public UsersRow getUser(Long id)"));
        assertTrue(admins.content().contains("public UsersRow getAdmin(Long id)"));

        assertFalse(users.content().contains("public record"));
        assertFalse(admins.content().contains("public record"));

        assertCompiles(users, admins, rows.get(0));
    }

    /**
     * Two groups that return the same row type of the same table must define it
     * from the same columns, because the package generates one record.
     */
    @Test
    void shouldRejectARowOfAnotherGroupDefinedFromDifferentColumns() {
        codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(getUsersRow("GetUser")))
        );

        QueryModel getAdmin = new QueryModel(
            "GetAdmin",
            QueryType.ONE,
            "users",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, false)
            ),
            List.of(),
            "users"
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(
                new QueryGroupModel("Admins", List.of(getAdmin))
            )
        );

        assertEquals(
            "Table 'users' differs from its definition in query group 'Users', "
                + "which generates the same row type UsersRow",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectRowTypesOfTwoGroupsThatAreEqualIgnoringCase() {
        QueryModel getUserData = new QueryModel(
            "GetUserData",
            QueryType.ONE,
            "user_data",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "user_data"
        );

        QueryModel fetchUserdata = new QueryModel(
            "FetchUserdata",
            QueryType.ONE,
            "userdata",
            SQL,
            List.of(),
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            List.of(),
            "userdata"
        );

        codeGenerator.generate(
            new QueryGroupModel(GROUP, List.of(getUserData))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(
                new QueryGroupModel("Admins", List.of(fetchUserdata))
            )
        );

        assertEquals(
            "Tables 'user_data' and 'userdata' generate row types that are equal ignoring case: "
                + "UserDataRow and UserdataRow",
            exception.getMessage()
        );
    }

    /** A {@code :one} query returning the complete row of the table {@code users}. */
    private QueryModel getUsersRow(String queryName) {
        return new QueryModel(
            queryName,
            QueryType.ONE,
            "users",
            "SELECT * FROM users WHERE id = ?",
            List.of(1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            "users"
        );
    }

    private GeneratedFile generate(QueryModel query) {
        return generate(codeGenerator, query);
    }

    /** Generates the repository of a single-query group. */
    private GeneratedFile generate(CodeGenerator generator, QueryModel query) {
        return generator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(query)
            )
        );
    }

    private QueryModel query(String name, QueryType type, List<QueryParameter> parameters) {
        return new QueryModel(
            name,
            type,
            "users",
            SQL,
            parameters.stream()
                .map(QueryParameter::index)
                .toList(),
            List.of(),
            parameters,
            null
        );
    }

    /**
     * An enum column and an enum parameter use the Java enum the package
     * generates for their enum type, by its simple name and without an import:
     * the parameter is bound as the label of its constant, wrapped so that
     * PostgreSQL types it from the context of its placeholder, and the column
     * is read from its label at every binding position of its index.
     */
    @Test
    void shouldBindEnumLabelsAndReadEnumColumnsByLabel() throws IOException {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            """
                SELECT id, setting, handle
                FROM stages
                WHERE handle = ?
                  AND (setting = ? OR setting = ?)
                """,
            List.of(2, 1, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("setting", ColumnType.ENUM, true, "stage_setting"),
                new QueryColumn("handle", ColumnType.VARCHAR, true)
            ),
            List.of(
                new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting"),
                new QueryParameter(2, "handle", ColumnType.VARCHAR)
            ),
            null
        );

        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                query,
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        String source = repository.content();

        assertTrue(
            source.contains("public FindStageResult findStage(StageSetting setting, String handle)")
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "handle, "
                    + "new dev.sqlcj.runtime.UntypedText(setting == null ? null : setting.label()), "
                    + "new dev.sqlcj.runtime.UntypedText(setting == null ? null : setting.label()))"
            )
        );

        assertTrue(source.contains("StageSetting setting"));
        assertTrue(source.contains("StageSetting.fromLabel(resultSet.getString(2))"));
        assertTrue(source.contains("resultSet.getObject(1, Long.class)"));
        assertTrue(source.contains("resultSet.getObject(3, String.class)"));

        assertFalse(source.contains("import generated.StageSetting;"));

        assertCompiles(repository, codeGenerator.generateEnums().get(0));
    }

    /**
     * The generated enum names one constant per label, in label order, holds
     * the exact labels, and resolves a label back to its constant. A null label
     * reads back as {@code null}, and a label the enum does not hold, which
     * means the database declares one the schema source does not, fails naming
     * the enum type and the label.
     */
    @Test
    void shouldGenerateCompilableEnumWithLabelLookups() throws Exception {
        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                enumQuery("GetStage"),
                new EnumType("stage_setting", List.of("indoor", "out door"))
            )
        );

        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(1, enums.size());
        assertEquals(Path.of("generated", "StageSetting.java"), enums.get(0).path());

        assertCompiles(repository, enums.get(0));

        try (URLClassLoader classLoader = classLoader()) {
            Class<?> type = Class.forName("generated.StageSetting", true, classLoader);

            assertTrue(type.isEnum());

            Object[] constants = type.getEnumConstants();

            assertEquals(
                List.of("INDOOR", "OUT_DOOR"),
                Arrays.stream(constants).map(Object::toString).toList()
            );

            Method label = type.getMethod("label");

            assertEquals("indoor", label.invoke(constants[0]));
            assertEquals("out door", label.invoke(constants[1]));

            Method fromLabel = type.getMethod("fromLabel", String.class);

            assertEquals(constants[1], fromLabel.invoke(null, "out door"));
            assertNull(fromLabel.invoke(null, new Object[] { null }));

            InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> fromLabel.invoke(null, "covered")
            );

            assertInstanceOf(IllegalArgumentException.class, failure.getCause());

            assertEquals(
                "Unknown label for enum type stage_setting: covered",
                failure.getCause().getMessage()
            );
        }
    }

    /** An enum type no column and no parameter uses generates no file. */
    @Test
    void shouldGenerateOnlyTheEnumTypesTheQueriesUse() {
        codeGenerator.generate(
            new QueryGroupModel(
                GROUP,
                List.of(enumQuery("GetStage")),
                List.of(
                    new EnumType("stage_setting", List.of("indoor")),
                    new EnumType("shelf_state", List.of("stocked"))
                )
            )
        );

        assertEquals(
            List.of(Path.of("generated", "StageSetting.java")),
            codeGenerator.generateEnums().stream()
                .map(GeneratedFile::path)
                .toList()
        );
    }

    /**
     * An enum type name is the upper camel form of its PostgreSQL name, and a
     * constant is the label's words, upper-cased and joined with {@code _},
     * with a leading {@code _} for a constant that would start with a digit.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "stage_setting|indoor|StageSetting|INDOOR",
            "HTTP_state|in progress|HttpState|IN_PROGRESS",
            "stage_setting|in-progress|StageSetting|IN_PROGRESS",
            "stage_setting|InProgress|StageSetting|INPROGRESS",
            "stage_setting|ID|StageSetting|ID",
            "stage_setting|2fast|StageSetting|_2FAST"
        }
    )
    void shouldNameTheGeneratedEnumAndItsConstants(
        String enumName,
        String label,
        String typeName,
        String constant
    ) {
        codeGenerator.generate(
            enumGroup(
                GROUP,
                enumQuery("GetStage", enumName),
                new EnumType(enumName, List.of(label))
            )
        );

        GeneratedFile file = codeGenerator.generateEnums().get(0);

        assertEquals(Path.of("generated", typeName + ".java"), file.path());
        assertTrue(file.content().contains("public enum " + typeName + " {"));
        assertTrue(file.content().contains(constant + "(\"" + label + "\");"));
    }

    /** A label with no letter and no digit generates no Java constant. */
    @Test
    void shouldRejectALabelWithoutALetterOrDigit() {
        QueryGroupModel group = enumGroup(
            GROUP,
            enumQuery("GetStage"),
            new EnumType("stage_setting", List.of("indoor", "***"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Label '***' of enum type 'stage_setting' has no letter or digit to generate a Java constant from",
            exception.getMessage()
        );
    }

    /** Two labels of one enum type must generate two constants. */
    @Test
    void shouldRejectTwoLabelsThatGenerateOneConstant() {
        QueryGroupModel group = enumGroup(
            GROUP,
            enumQuery("GetStage"),
            new EnumType("stage_setting", List.of("in progress", "in-progress"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Labels 'in progress' and 'in-progress' of enum type 'stage_setting' generate the same constant IN_PROGRESS",
            exception.getMessage()
        );
    }

    /**
     * Two groups that use one enum type generate one Java enum, so they must
     * define its labels alike.
     */
    @Test
    void shouldRejectTwoGroupsThatDefineOneEnumTypeDifferently() {
        codeGenerator.generate(
            enumGroup(
                "Stages",
                enumQuery("GetStage"),
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        QueryGroupModel group = enumGroup(
            "Venues",
            enumQuery("GetVenue"),
            new EnumType("stage_setting", List.of("indoor"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum type 'stage_setting' differs from its definition in query group 'Stages', "
                + "which generates the same enum type StageSetting",
            exception.getMessage()
        );

        assertEquals(1, codeGenerator.generateEnums().size());
    }

    /**
     * Two enum types whose Java enums are equal ignoring case are rejected,
     * because the compiled class files of those types are one path on a
     * case-insensitive filesystem.
     */
    @Test
    void shouldRejectEnumTypesThatAreEqualIgnoringCase() {
        codeGenerator.generate(
            enumGroup(
                "Stages",
                enumQuery("GetStage"),
                new EnumType("stage_setting", List.of("indoor"))
            )
        );

        QueryGroupModel group = enumGroup(
            "Venues",
            enumQuery("GetVenue", "stagesetting"),
            new EnumType("stagesetting", List.of("indoor"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum types 'stage_setting' and 'stagesetting' generate enum types that are equal ignoring case: "
                + "StageSetting and Stagesetting",
            exception.getMessage()
        );
    }

    /**
     * A Java enum must not take the name of another generated type of the
     * package, in either generation order.
     */
    @Test
    void shouldRejectAnEnumTypeThatCollidesWithAnEarlierGeneratedType() {
        codeGenerator.generate(
            new QueryGroupModel("Users", List.of(getUsersRow("GetUser")))
        );

        QueryGroupModel group = enumGroup(
            "Stages",
            enumQuery("GetStage", "users_row"),
            new EnumType("users_row", List.of("indoor"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum type 'users_row' generates UsersRow, which is equal ignoring case to the generated type UsersRow",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectAGeneratedTypeThatCollidesWithAnEarlierEnumType() {
        codeGenerator.generate(
            enumGroup(
                "Stages",
                enumQuery("GetStage", "usersrow"),
                new EnumType("usersrow", List.of("indoor"))
            )
        );

        QueryGroupModel group = new QueryGroupModel("Users", List.of(getUsersRow("GetUser")));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> codeGenerator.generate(group)
        );

        assertEquals(
            "Enum type 'usersrow' generates Usersrow, which is equal ignoring case to the generated type UsersRow",
            exception.getMessage()
        );
    }

    /**
     * An array column and an array parameter are a {@code List} of the
     * element's Java type. The parameter is wrapped in
     * {@code dev.sqlcj.runtime.SqlArray} with the element type's PostgreSQL
     * name, written out in full so that the generated imports are unchanged,
     * and the column is read through the same type.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER, int4, Integer",
            "BIGINT, int8, Long",
            "SMALLINT, int2, Short",
            "BOOLEAN, bool, Boolean",
            "VARCHAR, varchar, String",
            "TEXT, text, String",
            "DATE, date, LocalDate",
            "TIME, time, LocalTime",
            "TIMESTAMP, timestamp, LocalDateTime",
            "TIMESTAMP_WITH_TIME_ZONE, timestamptz, OffsetDateTime",
            "DECIMAL, numeric, BigDecimal",
            "REAL, float4, Float",
            "DOUBLE_PRECISION, float8, Double",
            "UUID, uuid, UUID"
        }
    )
    void shouldBindAndReadArrayColumnsPerElementType(
        ColumnType elementType,
        String elementTypeName,
        String javaType
    ) {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            SQL,
            List.of(1),
            List.of(new QueryColumn("values", elementType, true, null, true)),
            List.of(new QueryParameter(1, "values", elementType, null, true)),
            null
        );

        String source = generate(query).content();

        assertTrue(source.contains("public FindStageResult findStage(List<%s> values)".formatted(javaType)));
        assertTrue(source.contains("List<%s> values".formatted(javaType)));

        assertTrue(
            source.contains(
                "java.util.Arrays.asList(new dev.sqlcj.runtime.SqlArray(\"%s\", values))"
                    .formatted(elementTypeName)
            )
        );

        assertTrue(
            source.contains(
                "dev.sqlcj.runtime.SqlArray.getList(resultSet, 1, %s.class)".formatted(javaType)
            )
        );

        assertFalse(source.contains("import dev.sqlcj.runtime.SqlArray;"));
    }

    /**
     * A blank-padded character array parameter is bound with PostgreSQL's own
     * name of that type, {@code bpchar}, because PostgreSQL compares a
     * {@code bpchar} array only with another one, and a varying one keeps
     * {@code varchar}. Both are read as lists of {@code String}.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "true, bpchar",
            "false, varchar"
        }
    )
    void shouldBindBlankPaddedCharacterArraysAsBpchar(
        boolean blankPadded,
        String elementTypeName
    ) {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            SQL,
            List.of(1),
            List.of(new QueryColumn("marks", ColumnType.VARCHAR, true, null, true)),
            List.of(
                new QueryParameter(1, "marks", ColumnType.VARCHAR, null, true, blankPadded)
            ),
            null
        );

        String source = generate(query).content();

        assertTrue(source.contains("public FindStageResult findStage(List<String> marks)"));

        assertTrue(
            source.contains(
                "java.util.Arrays.asList(new dev.sqlcj.runtime.SqlArray(\"%s\", marks))"
                    .formatted(elementTypeName)
            )
        );

        assertTrue(
            source.contains("dev.sqlcj.runtime.SqlArray.getList(resultSet, 1, String.class)")
        );
    }

    /**
     * An array of an enum binds the labels of its constants and reads each
     * label back into a constant, at every binding position of its index. The
     * enum an array element alone names is generated like any other used enum.
     */
    @Test
    void shouldBindEnumArrayLabelsAndReadEnumArrayColumnsByLabel() throws IOException {
        QueryModel query = new QueryModel(
            "FindStage",
            QueryType.ONE,
            "stages",
            """
                SELECT id, past_settings
                FROM stages
                WHERE past_settings = ? OR past_settings = ?
                """,
            List.of(1, 1),
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("past_settings", ColumnType.ENUM, true, "stage_setting", true)
            ),
            List.of(
                new QueryParameter(1, "past_settings", ColumnType.ENUM, "stage_setting", true)
            ),
            null
        );

        GeneratedFile repository = generate(
            enumGroup(
                GROUP,
                query,
                new EnumType("stage_setting", List.of("indoor", "outdoor"))
            )
        );

        String source = repository.content();

        assertTrue(
            source.contains(
                "public FindStageResult findStage(List<StageSetting> pastSettings)"
            )
        );

        assertTrue(
            source.contains(
                "java.util.Arrays.asList("
                    + "dev.sqlcj.runtime.SqlArray.of(\"stage_setting\", pastSettings, StageSetting::label), "
                    + "dev.sqlcj.runtime.SqlArray.of(\"stage_setting\", pastSettings, StageSetting::label))"
            )
        );

        assertTrue(
            source.contains(
                "dev.sqlcj.runtime.SqlArray.getList(resultSet, 2, String.class, StageSetting::fromLabel)"
            )
        );

        List<GeneratedFile> enums = codeGenerator.generateEnums();

        assertEquals(1, enums.size());
        assertEquals(Path.of("generated", "StageSetting.java"), enums.get(0).path());

        assertCompiles(repository, enums.get(0));
    }

    /**
     * A row record of an array component imports {@code java.util.List} beside
     * the JDK types of its elements, in the order its components name them.
     */
    @Test
    void shouldImportListForAnArrayRowComponent() throws IOException {
        QueryModel query = new QueryModel(
            "GetStage",
            QueryType.ONE,
            "stages",
            SQL,
            List.of(),
            List.of(
                new QueryColumn("opened_on", ColumnType.DATE, true),
                new QueryColumn("tags", ColumnType.VARCHAR, true, null, true),
                new QueryColumn("closed_on", ColumnType.DATE, true, null, true)
            ),
            List.of(),
            "stages"
        );

        GeneratedFile repository = generate(query);

        GeneratedFile row = codeGenerator.generateRows().get(0);

        assertEquals(
            """
                // Code generated by sqlcj. DO NOT EDIT.

                package generated;

                import java.time.LocalDate;
                import java.util.List;

                /**
                 * Generated by sqlcj.
                 *
                 * Table: stages
                 */

                public record StagesRow(
                    LocalDate openedOn,
                    List<String> tags,
                    List<LocalDate> closedOn
                ) {
                }
                """,
            row.content()
        );

        assertCompiles(repository, row);
    }

    /** A {@code :one} query reading and binding one enum column. */
    private QueryModel enumQuery(String queryName) {
        return enumQuery(queryName, "stage_setting");
    }

    private QueryModel enumQuery(String queryName, String enumName) {
        return new QueryModel(
            queryName,
            QueryType.ONE,
            "stages",
            "SELECT setting FROM stages WHERE setting = ?",
            List.of(1),
            List.of(new QueryColumn("setting", ColumnType.ENUM, true, enumName)),
            List.of(new QueryParameter(1, "setting", ColumnType.ENUM, enumName)),
            null
        );
    }

    private QueryGroupModel enumGroup(String groupName, QueryModel query, EnumType enumType) {
        return new QueryGroupModel(groupName, List.of(query), List.of(enumType));
    }

    private GeneratedFile generate(QueryGroupModel group) {
        return codeGenerator.generate(group);
    }

    /** Loads the classes the compiled generated files left. */
    private URLClassLoader classLoader() throws IOException {
        return new URLClassLoader(
            new URL[] { tempDir.resolve("classes").toUri().toURL() },
            getClass().getClassLoader()
        );
    }
}
