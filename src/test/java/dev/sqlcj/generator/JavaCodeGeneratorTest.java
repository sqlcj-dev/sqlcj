package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.parser.QueryType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeGeneratorTest {

    private final CodeGenerator codeGenerator = new JavaCodeGenerator();

    @Test
    void shouldGenerateFilePath() {
        GeneratedFile file = codeGenerator.generate(
                query(
                        "GetUser",
                        QueryType.ONE,
                        List.of(1)
                )
        );

        assertEquals(
                Path.of("generated", "GetUser.java"),
                file.path()
        );
    }

    @Test
    void shouldGenerateClassName() {
        GeneratedFile file = codeGenerator.generate(
                query(
                        "GetUser",
                        QueryType.ONE,
                        List.of(1)
                )
        );

        assertTrue(
                file.content().contains("public final class GetUser")
        );
    }

    @Test
    void shouldGenerateMethodName() {
        GeneratedFile file = codeGenerator.generate(
                query(
                        "GetUser",
                        QueryType.ONE,
                        List.of(1)
                )
        );

        assertTrue(
                file.content().contains("public Object getUser")
        );
    }

    @Test
    void shouldGenerateSingleParameter() {
        GeneratedFile file = codeGenerator.generate(
                query(
                        "GetUser",
                        QueryType.ONE,
                        List.of(1)
                )
        );

        assertTrue(
                file.content().contains("Object param1")
        );
    }

    @Test
    void shouldGenerateMultipleParameters() {
        GeneratedFile file = codeGenerator.generate(
                query(
                        "ListUsersByIdAndUsername",
                        QueryType.MANY,
                        List.of(1, 2)
                )
        );

        assertTrue(
                file.content().contains(
                        "Object param1, Object param2"
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
                        "public Object listUsers()"
                )
        );
    }

    @Test
    void shouldGenerateJavaDoc() {
        GeneratedFile file = codeGenerator.generate(
                query(
                        "GetUser",
                        QueryType.ONE,
                        List.of(1)
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
                        List.of(1)
                )
        );

        assertTrue(
                file.content().startsWith("package generated;")
        );
    }

    private QueryModel query(
            String name,
            QueryType type,
            List<Integer> parameters
    ) {
        return new QueryModel(
                name,
                type,
                "users",
                parameters
        );
    }
}
