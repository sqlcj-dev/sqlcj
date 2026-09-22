package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaNamesTest {

    private static final String SQL = "SELECT 1";

    @Test
    void shouldKeepSafeQueryNameAsClassName() {
        JavaNames names = names("GetUser");

        assertEquals("GetUser", names.className());
        assertEquals("GetUserResult", names.resultTypeName());
        assertEquals("getUser", names.methodName());
    }

    @Test
    void shouldKeepSafeLowercaseQueryNameAsClassName() {
        JavaNames names = names("getUser");

        assertEquals("getUser", names.className());
        assertEquals("getUser", names.methodName());
    }

    @Test
    void shouldKeepClassNameThatIsOnlyAKeywordAsMethodName() {
        JavaNames names = names("Class");

        assertEquals("Class", names.className());
        assertEquals("class_", names.methodName());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "Get-User, Get_User",
            "get user, get_user",
            "'Get**User', Get_User",
            "'get user ', get_user_",
            "1stQuery, _1stQuery",
            "default, default_",
            "int, int_",
            "'true', true_",
            "_, __"
        }
    )
    void shouldNormalizeUnsafeQueryName(String queryName, String expectedClassName) {
        assertEquals(expectedClassName, names(queryName).className());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "List, List_",
            "String, String_",
            "RowMapper, RowMapper_",
            "QueryExecutor, QueryExecutor_",
            "record, record_",
            "var, var_"
        }
    )
    void shouldRenameClassConflictingWithGeneratedOrImportedType(String queryName, String expectedClassName) {
        assertEquals(expectedClassName, names(queryName).className());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "ToString, toString_",
            "HashCode, hashCode_",
            "GetClass, getClass_",
            "Wait, wait_"
        }
    )
    void shouldRenameMethodConflictingWithInheritedObjectMethod(String queryName, String expectedMethodName) {
        assertEquals(expectedMethodName, names(queryName).methodName());
    }

    @Test
    void shouldKeepSafeColumnNamesAsResultComponents() {
        JavaNames names = names("ListUsers", List.of(), List.of("id", "created_at"));

        assertEquals(List.of("id", "created_at"), names.componentNames());
    }

    @Test
    void shouldNormalizeAndDisambiguateResultComponents() {
        JavaNames names = names(
            "ListUsers",
            List.of(),
            List.of("user id", "user-id", "class", "hashCode")
        );

        assertEquals(
            List.of("user_id1", "user_id2", "class_", "hashCode1"),
            names.componentNames()
        );
    }

    @Test
    void shouldSkipUsedCandidateWhenDisambiguatingResultComponents() {
        JavaNames names = names(
            "ListUsers",
            List.of(),
            List.of("id", "id", "id1")
        );

        assertEquals(List.of("id1", "id2", "id11"), names.componentNames());
    }

    @Test
    void shouldKeepExistingDuplicateParameterNaming() {
        JavaNames names = names("FindUsers", List.of("id", "id"), List.of());

        assertEquals(List.of("id1", "id2"), names.parameterNames());
    }

    @Test
    void shouldResolveParameterNamesReservedByGeneratedCode() {
        JavaNames names = names(
            "FindUsers",
            List.of("executor", "ROW_MAPPER", "name"),
            List.of()
        );

        assertEquals(
            List.of("executor1", "ROW_MAPPER1", "name"),
            names.parameterNames()
        );
    }

    @Test
    void shouldResolveParameterAndComponentNamesIndependently() {
        JavaNames names = names(
            "FindUsers",
            List.of("id", "id"),
            List.of("id")
        );

        assertEquals(List.of("id1", "id2"), names.parameterNames());
        assertEquals(List.of("id"), names.componentNames());
    }

    private JavaNames names(String queryName) {
        return names(queryName, List.of(), List.of());
    }

    private JavaNames names(String queryName, List<String> parameterNames, List<String> columnNames) {
        List<QueryParameter> parameters = IntStream.range(0, parameterNames.size())
            .mapToObj(
                index -> new QueryParameter(
                    index + 1,
                    parameterNames.get(index),
                    ColumnType.BIGINT
                )
            )
            .toList();

        List<QueryColumn> columns = columnNames.stream()
            .map(name -> new QueryColumn(name, ColumnType.BIGINT, false))
            .toList();

        return JavaNames.of(
            new QueryModel(
                queryName,
                QueryType.MANY,
                "users",
                SQL,
                parameters.stream()
                    .map(QueryParameter::index)
                    .toList(),
                columns,
                parameters
            )
        );
    }
}
