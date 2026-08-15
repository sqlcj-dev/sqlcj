package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.ColumnType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeGeneratorTest {

    private final CodeGenerator codeGenerator = new JavaCodeGenerator();

    @Test
    void shouldGenerateFilePath() {
        QueryModel query = new QueryModel(
                "GetUser",
                QueryType.ONE,
                "users",
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
                                new QueryParameter(1, ColumnType.BIGINT)
                        )
                )
        );

        assertTrue(
                file.content().contains("Long param1")
        );
    }

    @Test
    void shouldGenerateMultipleParameters() {
        GeneratedFile file = codeGenerator.generate(
                query(
                        "ListUsersByIdAndName",
                        QueryType.MANY,
                        List.of(
                                new QueryParameter(1, ColumnType.BIGINT),
                                new QueryParameter(2, ColumnType.VARCHAR)
                        )
                )
        );

        assertTrue(
                file.content().contains(
                        "Long param1, String param2"
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
                        "public List<Result> listUsers()"
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
                                new QueryParameter(1, ColumnType.BIGINT)
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
                                new QueryParameter(1, ColumnType.BIGINT)
                        )
                )
        );

        assertTrue(
                file.content().startsWith("package generated;")
        );
    }

    @Test
    void shouldGenerateResultRecord() {
        QueryModel query = new QueryModel(
                "GetUser",
                QueryType.ONE,
                "users",
                List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("name", ColumnType.VARCHAR, true),
                        new QueryColumn("active", ColumnType.BOOLEAN, true)
                ),
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        String source = file.content();

        assertTrue(source.contains("public record Result("));
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
                List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false)
                ),
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(
                file.content().contains(
                        "public Result getUser()"
                )
        );
    }

    @Test
    void shouldGenerateListResultReturnType() {
        QueryModel query = new QueryModel(
                "ListUsers",
                QueryType.MANY,
                "users",
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
                        "public List<Result> listUsers()"
                )
        );
    }

    @Test
    void shouldGenerateTypedMethodParameters() {
        QueryModel query = new QueryModel(
                "ListUsersByIdAndName",
                QueryType.MANY,
                "users",
                List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("name", ColumnType.VARCHAR, true)
                ),
                List.of(
                        new QueryParameter(1, ColumnType.BIGINT),
                        new QueryParameter(2, ColumnType.VARCHAR)
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(
                file.content().contains(
                        "public List<Result> listUsersByIdAndName(Long param1, String param2)"
                )
        );
    }

    @Test
    void shouldGenerateSingleResultWithTypedParameter() {
        QueryModel query = new QueryModel(
                "GetUser",
                QueryType.ONE,
                "users",
                List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("name", ColumnType.VARCHAR, true)
                ),
                List.of(
                        new QueryParameter(1, ColumnType.BIGINT)
                )
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertTrue(
                file.content().contains(
                        "public Result getUser(Long param1)"
                )
        );
    }

    @Test
    void shouldNotGenerateListImportForSingleResult() {
        QueryModel query = new QueryModel(
                "GetUser",
                QueryType.ONE,
                "users",
                List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false)
                ),
                List.of()
        );

        GeneratedFile file = codeGenerator.generate(query);

        assertFalse(
                file.content().contains("import java.util.List;")
        );
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
                List.of(),
                parameters
        );
    }
}
