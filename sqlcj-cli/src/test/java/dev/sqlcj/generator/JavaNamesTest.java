package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryGroupModel;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaNamesTest {

    private static final String SQL = "SELECT 1";

    @ParameterizedTest
    @CsvSource(
        {
            "Author, AuthorRepository",
            "author_admin, AuthorAdminRepository"
        }
    )
    void shouldNameRepositoryAfterConfiguredGroupName(String groupName, String expectedClassName) {
        JavaNames names = names(groupName, "GetAuthor");

        assertEquals(expectedClassName, names.repositoryClassName());
    }

    @Test
    void shouldNameResultTypeAndRowMapperAfterQueryName() {
        JavaNames.QueryNames names = queryNames("GetUser");

        assertEquals("GetUserResult", names.resultTypeName());
        assertEquals("getUser", names.methodName());
        assertEquals("getUserRowMapper", names.rowMapperName());
    }

    @Test
    void shouldKeepKeywordMethodNameSafe() {
        JavaNames.QueryNames names = queryNames("Class");

        assertEquals("ClassResult", names.resultTypeName());
        assertEquals("class_", names.methodName());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "authors, AuthorsResult",
            "get_author, GetAuthorResult",
            "GetAuthor, GetAuthorResult",
            "user_ID, UserIdResult",
            "URL, UrlResult",
            "Get-User, GetUserResult",
            "get user, GetUserResult",
            "'Get**User', GetUserResult",
            "'get user ', GetUserResult",
            "1st_query, _1stQueryResult",
            "1stQuery, _1stQueryResult",
            "default, DefaultResult",
            "int, IntResult",
            "'true', TrueResult"
        }
    )
    void shouldNameResultTypeInUpperCamelCase(String queryName, String expectedResultTypeName) {
        assertEquals(expectedResultTypeName, queryNames(queryName).resultTypeName());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "get_author, getAuthor",
            "GetAuthor, getAuthor",
            "getAuthor, getAuthor",
            "HTTPStatus, httpStatus",
            "HTTP2Status, http2Status",
            "GetHTTPStatus, getHTTPStatus",
            "created_at, createdAt",
            "user_ID, userId",
            "1st_query, _1stQuery",
            "default, default_",
            "'true', true_"
        }
    )
    void shouldNameMethodInLowerCamelCase(String queryName, String expectedMethodName) {
        assertEquals(expectedMethodName, queryNames(queryName).methodName());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "created_at, createdAt",
            "'user id', userId",
            "user_ID, userId",
            "ID, id",
            "URL, url",
            "HTTPStatus, httpStatus",
            "class, class_"
        }
    )
    void shouldNameResultComponentInLowerCamelCase(String columnName, String expectedComponentName) {
        JavaNames.QueryNames names = queryNames("ListUsers", List.of(), List.of(columnName));

        assertEquals(List.of(expectedComponentName), names.componentNames());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "_",
            "'***'",
            "$"
        }
    )
    void shouldRejectQueryNameWithoutAWord(String queryName) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> queryNames(queryName)
        );

        assertEquals(
            "SQL name '%s' has no letter or digit to generate a Java name from".formatted(queryName),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectColumnNameWithoutAWord() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> queryNames("ListUsers", List.of(), List.of("***"))
        );

        assertEquals(
            "SQL name '***' of query 'ListUsers' has no letter or digit to generate a Java name from",
            exception.getMessage()
        );
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
        assertEquals(expectedMethodName, queryNames(queryName).methodName());
    }

    @Test
    void shouldRejectQueriesOfOneGroupThatGenerateTheSameMethod() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> names("Author", "get_author", "GetAuthor")
        );

        assertEquals(
            "Queries 'get_author' and 'GetAuthor' generate the same repository method 'getAuthor'",
            exception.getMessage()
        );
    }

    /**
     * The compiled class files of two nested result types that differ only by
     * case are one path on a case-insensitive filesystem.
     */
    @Test
    void shouldRejectQueriesOfOneGroupWhoseResultTypesDifferOnlyByCase() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> names("Author", "GetUser", "getuser")
        );

        assertEquals(
            "Queries 'GetUser' and 'getuser' generate result types that differ only by case: "
                + "GetUserResult and GetuserResult",
            exception.getMessage()
        );
    }

    @Test
    void shouldKeepTheSameQueryNameInTwoRepositoriesIndependent() {
        assertEquals(
            "getUser",
            names("Author", "GetUser").queries().getFirst().methodName()
        );

        assertEquals(
            "getUser",
            names("Book", "GetUser").queries().getFirst().methodName()
        );
    }

    @Test
    void shouldKeepSelectedColumnOrderOfResultComponents() {
        JavaNames.QueryNames names = queryNames("ListUsers", List.of(), List.of("id", "created_at"));

        assertEquals(List.of("id", "createdAt"), names.componentNames());
    }

    @Test
    void shouldNormalizeAndDisambiguateResultComponents() {
        JavaNames.QueryNames names = queryNames(
            "ListUsers",
            List.of(),
            List.of("user id", "user-id", "class", "hashCode")
        );

        assertEquals(
            List.of("userId1", "userId2", "class_", "hashCode1"),
            names.componentNames()
        );
    }

    @Test
    void shouldSkipUsedCandidateWhenDisambiguatingResultComponents() {
        JavaNames.QueryNames names = queryNames(
            "ListUsers",
            List.of(),
            List.of("id", "id", "id1")
        );

        assertEquals(List.of("id1", "id2", "id11"), names.componentNames());
    }

    @Test
    void shouldKeepExistingDuplicateParameterNaming() {
        JavaNames.QueryNames names = queryNames("FindUsers", List.of("id", "id"), List.of());

        assertEquals(List.of("id1", "id2"), names.parameterNames());
    }

    @Test
    void shouldResolveParameterNamesReservedByGeneratedCode() {
        JavaNames.QueryNames names = queryNames(
            "FindUsers",
            List.of("executor", "findUsersRowMapper", "name"),
            List.of()
        );

        assertEquals(
            List.of("executor1", "findUsersRowMapper1", "name"),
            names.parameterNames()
        );
    }

    /**
     * A parameter must not shadow the row mapper of another method of the same
     * repository either, because every mapper is a field of that repository.
     */
    @Test
    void shouldResolveParameterNameReservedByAnotherQueryRowMapper() {
        JavaNames names = JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(
                    query("FindUsers", List.of("listUsersRowMapper"), List.of()),
                    query("ListUsers", List.of(), List.of("id"))
                )
            )
        );

        assertEquals(
            List.of("listUsersRowMapper1"),
            names.queries().getFirst().parameterNames()
        );
    }

    @Test
    void shouldResolveParameterAndComponentNamesIndependently() {
        JavaNames.QueryNames names = queryNames(
            "FindUsers",
            List.of("id", "id"),
            List.of("id")
        );

        assertEquals(List.of("id1", "id2"), names.parameterNames());
        assertEquals(List.of("id"), names.componentNames());
    }

    private JavaNames names(String groupName, String... queryNames) {
        return JavaNames.of(
            new QueryGroupModel(
                groupName,
                Arrays.stream(queryNames)
                    .map(queryName -> query(queryName, List.of(), List.of()))
                    .toList()
            )
        );
    }

    private JavaNames.QueryNames queryNames(String queryName) {
        return queryNames(queryName, List.of(), List.of());
    }

    private JavaNames.QueryNames queryNames(
        String queryName,
        List<String> parameterNames,
        List<String> columnNames
    ) {
        return JavaNames.of(
            new QueryGroupModel(
                "Users",
                List.of(query(queryName, parameterNames, columnNames))
            )
        )
            .queries()
            .getFirst();
    }

    private QueryModel query(
        String queryName,
        List<String> parameterNames,
        List<String> columnNames
    ) {
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

        return new QueryModel(
            queryName,
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
}
