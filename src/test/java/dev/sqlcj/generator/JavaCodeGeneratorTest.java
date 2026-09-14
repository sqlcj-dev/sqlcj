package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeGeneratorTest {

    private static final String SQL = "SELECT 1";

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertEquals(
                Path.of("generated", "GetUser.java"),
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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(file.content().contains("public final class GetUser"));
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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(file.content().contains("getUser("));
    }

    @Test
    void shouldGenerateSingleParameter() {
        GeneratedFile file = codeGenerator.generate(
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
        GeneratedFile file = codeGenerator.generate(
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
        GeneratedFile file = codeGenerator.generate(
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
        GeneratedFile file = codeGenerator.generate(
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
        GeneratedFile file = codeGenerator.generate(
                query(
                        "GetUser",
                        QueryType.ONE,
                        List.of(
                                new QueryParameter(1, "id", ColumnType.BIGINT)
                        )
                )
        );

        assertTrue(
                file.content().startsWith("package generated;")
        );
    }

    @Test
    void shouldGenerateConfiguredPackageDeclarationAndPath()
            throws IOException {

        CodeGenerator generator =
                new JavaCodeGenerator("dev.example.generated");

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
                )
        );

        GeneratedFile file = generator.generate(query);

        assertEquals(
                Path.of("dev", "example", "generated", "GetUser.java"),
                file.path()
        );

        assertTrue(
                file.content().startsWith("package dev.example.generated;")
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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        String source = file.content();

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("List.of()"));
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

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(
                file.content().contains(
                        "Long userId, Boolean enabled"
                )
        );
    }

    @ParameterizedTest
    @CsvSource({
            "INTEGER, Integer",
            "BIGINT, Long",
            "SMALLINT, Short",
            "BOOLEAN, Boolean",
            "VARCHAR, String",
            "TEXT, String",
            "DATE, LocalDate",
            "TIMESTAMP, LocalDateTime",
            "DECIMAL, BigDecimal"
    })
    void shouldGenerateJavaTypeForQueryParameter(
            ColumnType columnType,
            String expectedJavaType
    ) {
        GeneratedFile file = codeGenerator.generate(
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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(
                file.content().contains(
                        "import java.time.LocalDate;"
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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(
                file.content().contains(
                        "import java.math.BigDecimal;"
                )
        );
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
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                )
        );

        String source = codeGenerator.generate(query).content();

        assertTrue(source.contains("return executor.query("));
        assertTrue(source.contains("List.of(id)"));
        assertTrue(source.contains("ROW_MAPPER"));
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
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

        Path sourceDirectory = tempDir.resolve("generated");
        Path sourceFile = sourceDirectory.resolve("ListUsers.java");
        Path outputDirectory = tempDir.resolve("classes");

        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputDirectory);

        Files.writeString(sourceFile, file.content());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compiler);

        String classpath = System.getProperty("java.class.path");

        int result = compiler.run(
                null,
                null,
                null,
                "-classpath",
                classpath,
                "-d",
                outputDirectory.toString(),
                sourceFile.toString()
        );

        assertEquals(0, result);
    }

    @ParameterizedTest
    @CsvSource({
            "INTEGER, Integer",
            "BIGINT, Long",
            "SMALLINT, Short",
            "BOOLEAN, Boolean",
            "VARCHAR, String",
            "TEXT, String",
            "DATE, LocalDate",
            "TIMESTAMP, LocalDateTime",
            "DECIMAL, BigDecimal"
    })
    void shouldGenerateJavaTypeForResultColumn(
            ColumnType columnType,
            String expectedJavaType
    ) {
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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        String source = file.content();

        assertTrue(source.contains(
                "import dev.sqlcj.runtime.RowMapper;"
        ));

        assertTrue(source.contains(
                "private static final RowMapper<GetUserResult> ROW_MAPPER"
        ));

        assertTrue(source.contains(
                "resultSet.getObject(\"id\", Long.class)"
        ));

        assertTrue(source.contains(
                "resultSet.getObject(\"name\", String.class)"
        ));

        assertTrue(source.contains(
                "resultSet.getObject(\"birth_date\", LocalDate.class)"
        ));

        assertTrue(source.contains(
                "resultSet.getObject(\"created_at\", LocalDateTime.class)"
        ));

        assertTrue(source.contains(
                "resultSet.getObject(\"balance\", BigDecimal.class)"
        ));
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
                List.of()
        );

        String source = codeGenerator.generate(query).content();

        int nameIndex = source.indexOf(
                "resultSet.getObject(\"name\", String.class)"
        );

        int idIndex = source.indexOf(
                "resultSet.getObject(\"id\", Long.class)"
        );

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
                List.of()
        );

        String source = codeGenerator.generate(query).content();

        assertTrue(source.contains(
                "import dev.sqlcj.runtime.RowMapper;"
        ));

        assertTrue(source.contains(
                "private static final RowMapper<ListUsersResult> ROW_MAPPER"
        ));

        assertTrue(source.contains(
                "resultSet.getObject(\"id\", Long.class)"
        ));

        assertTrue(source.contains(
                "resultSet.getObject(\"name\", String.class)"
        ));
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
                List.of()
        );

        String source = codeGenerator.generate(query).content();

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
                List.of()
        );

        String source = codeGenerator.generate(query).content();

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
                List.of()
        );

        String source = codeGenerator.generate(query).content();

        assertTrue(
                source.contains(
                        "public GetUser(QueryExecutor executor)"
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
                )
        );

        String source = codeGenerator.generate(query).content();

        assertTrue(source.contains("""
                return executor.query(
                """));

        assertTrue(source.contains("""
                WHERE id = ?
                """));

        assertTrue(source.contains(
                "List.of(id)"
        ));

        assertTrue(source.contains(
                "ROW_MAPPER"
        ));

        assertFalse(
                source.contains("UnsupportedOperationException")
        );

        assertTrue(source.contains("""
                return executor.query(
                """));

        assertFalse(source.contains(
                "return executor.queryMany("
        ));
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
                )
        );

        String source = codeGenerator.generate(query).content();

        assertTrue(source.contains("""
                return executor.queryMany(
                """));

        assertTrue(source.contains(
                "List.of(active)"
        ));

        assertTrue(source.contains(
                "ROW_MAPPER"
        ));

        assertFalse(source.contains(
                "return executor.query("
        ));
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
                )
        );

        String source = codeGenerator.generate(query).content();

        assertTrue(
                source.contains(
                        "List.of(active, id, name)"
                )
        );
    }

    @Test
    void shouldGenerateLogicalParametersWithTextualBindingOrder()
            throws IOException {

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
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

        String source = file.content();

        assertTrue(source.contains(
                "public FindUserResult findUser(Long id, Boolean active)"
        ));

        assertTrue(source.contains("WHERE active = ?"));
        assertTrue(source.contains("AND id = ?"));
        assertTrue(source.contains("List.of(active, id)"));

        assertCompiles(file);
    }

    @Test
    void shouldGenerateEmptyArgumentListForQueryWithoutParameters()
            throws IOException {

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
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        String source = file.content();

        assertTrue(source.contains(
                "public GetFirstUserResult getFirstUser()"
        ));

        assertTrue(source.contains("import java.util.List;"));
        assertTrue(source.contains("List.of()"));

        assertCompiles(file);
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

    private QueryModel query(
            String name,
            QueryType type,
            List<QueryParameter> parameters
    ) {
        return new QueryModel(
                name,
                type,
                "users",
                SQL,
                parameters.stream()
                        .map(QueryParameter::index)
                        .toList(),
                List.of(),
                parameters
        );
    }
}
