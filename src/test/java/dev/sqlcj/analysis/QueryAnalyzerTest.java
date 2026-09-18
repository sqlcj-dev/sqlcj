package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import dev.sqlcj.sql.SqlParser;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerTest {

    private static final Schema schema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("active", ColumnType.BOOLEAN, true)
                ),
                List.of()
            )
        )
    );

    private static final Schema joinSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true)
                ),
                List.of()
            ),
            new Table(
                "profiles",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("user_id", ColumnType.BIGINT, false),
                    new Column("nickname", ColumnType.VARCHAR, true)
                ),
                List.of()
            ),
            new Table(
                "orders",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("user_id", ColumnType.BIGINT, false),
                    new Column("total", ColumnType.DECIMAL, true)
                ),
                List.of()
            )
        )
    );

    private final SqlParser parser = new SqlParser();
    private final QueryAnalyzer analyzer = new QueryAnalyzer();

    @Test
    void shouldAnalyzeSelectWithoutParameters() {
        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users;
                """
        );

        Statement statement = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, statement, schema);

        assertEquals("ListUsers", model.name());
        assertEquals(QueryType.MANY, model.type());
        assertEquals("users", model.table());
        assertTrue(model.parameters().isEmpty());
    }

    @Test
    void shouldAnalyzeSelectWithSingleParameter() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            """
                SELECT *
                FROM users
                WHERE id = $1;
                """
        );

        Statement statement = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, statement, schema);

        assertEquals("users", model.table());
        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldAnalyzeSelectWithMultipleParameters() {
        Query query = new Query(
            "ListUsersByIdAndName",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id = $1
                  AND name = $2;
                """
        );

        Statement statement = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, statement, schema);

        assertEquals("users", model.table());
        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldAnalyzeInsert() {
        Query query = new Query(
            "InsertUser",
            QueryType.EXEC,
            """
                INSERT INTO users (id, name)
                VALUES ($1, $2)
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("InsertUser", model.name());
        assertEquals(QueryType.EXEC, model.type());
        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());

        assertEquals(
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(1, 2),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldAnalyzeUpdate() {
        Query query = new Query(
            "UpdateUser",
            QueryType.EXEC,
            """
                UPDATE users
                SET name = $2,
                    active = $3
                WHERE id = $1
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("UpdateUser", model.name());
        assertEquals(QueryType.EXEC, model.type());
        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());

        assertEquals(
            """
                UPDATE users
                SET name = ?,
                    active = ?
                WHERE id = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(2, 3, 1),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldAnalyzeDelete() {
        Query query = new Query(
            "DeleteUser",
            QueryType.EXEC,
            """
                DELETE FROM users
                WHERE id = $1
                  AND active = $2
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("DeleteUser", model.name());
        assertEquals(QueryType.EXEC, model.type());
        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());

        assertEquals(
            """
                DELETE FROM users
                WHERE id = ?
                  AND active = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(1, 2),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldAnalyzeDeleteWithoutWhere() {
        Query query = new Query(
            "DeleteUsers",
            QueryType.EXEC,
            "DELETE FROM users"
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());
        assertTrue(model.parameters().isEmpty());
        assertTrue(model.bindingParameterIndexes().isEmpty());
    }

    @Test
    void shouldRejectWriteWithoutExecQueryType() {
        Query query = new Query(
            "InsertUser",
            QueryType.ONE,
            """
                INSERT INTO users (id)
                VALUES ($1)
                """
        );

        Statement statement = parser.parse(query.sql());

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, statement, schema)
        );

        assertEquals(
            "Write queries must be declared as :exec",
            exception.getMessage()
        );
    }

    @Test
    void shouldAnalyzeSelectWithExplicitColumns() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT id, name FROM users WHERE id = $1"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals("GetUser", model.name());
        assertEquals(QueryType.ONE, model.type());
        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldResolveAllColumns() {
        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            "SELECT * FROM users"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("active", ColumnType.BOOLEAN, true)
            ),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());
    }

    @Test
    void shouldResolveParameterTypeFromColumn() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE id = $1"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldResolveMultipleParameters() {
        Query query = new Query(
            "ListUsersByIdAndName",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id = $1
                  AND name = $2
                """
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldNotCreateParametersForLiteralExpressions() {
        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE 1 = 1"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertTrue(model.parameters().isEmpty());
    }

    @Test
    void shouldThrowWhenTableDoesNotExist() {
        Query query = new Query(
            "GetOrder",
            QueryType.ONE,
            "SELECT * FROM orders"
        );

        Statement statement = parser.parse(query.sql());

        assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, statement, schema)
        );
    }

    @Test
    void shouldThrowWhenColumnDoesNotExist() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT username FROM users"
        );

        Statement statement = parser.parse(query.sql());

        assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, statement, schema)
        );
    }

    @Test
    void shouldResolveQueryParameterFromReferencedColumn() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE id = $1"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(1, model.parameters().size());

        QueryParameter parameter = model.parameters().getFirst();

        assertEquals(1, parameter.index());
        assertEquals("id", parameter.name());
        assertEquals(ColumnType.BIGINT, parameter.type());
    }

    @Test
    void shouldResolveMultipleQueryParameters() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id = $1 AND active = $2"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @Test
    void shouldResolveMultipleParametersForSameColumn() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id = $1 AND id = $2"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());
    }

    @Test
    void shouldResolveQueryParametersInIndexOrder() {
        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE active = $2 AND id = $1"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @Test
    void shouldResolveQueryParametersInsideOrExpression() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id = $1 OR active = $2"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @Test
    void shouldResolveQueryParametersInsideNestedAndOrExpressions() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id = $1
                  AND (active = $2 OR name = $3)
                """
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(3, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("name", third.name());
        assertEquals(ColumnType.VARCHAR, third.type());
    }

    @Test
    void shouldResolveQueryParametersForComparisonOperators() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id > $1
                  AND active = $2
                """
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "=",
            "<>",
            ">",
            ">=",
            "<",
            "<="
        }
    )
    void shouldResolveQueryParameterForComparisonOperator(
        String operator
    ) {
        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            """
                SELECT *
                FROM users
                WHERE id %s $1
                """
                .formatted(operator)
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(1, model.parameters().size());

        QueryParameter parameter = model.parameters().getFirst();

        assertEquals(1, parameter.index());
        assertEquals("id", parameter.name());
        assertEquals(ColumnType.BIGINT, parameter.type());
    }

    @Test
    void shouldResolveQueryParameterWhenParameterIsOnLeftSide() {
        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE $1 = id"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(1, model.parameters().size());

        QueryParameter parameter = model.parameters().getFirst();

        assertEquals(1, parameter.index());
        assertEquals("id", parameter.name());
        assertEquals(ColumnType.BIGINT, parameter.type());
    }

    @Test
    void shouldResolveQueryParametersInsideInExpression() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id IN ($1, $2, $3)"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(3, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("id", third.name());
        assertEquals(ColumnType.BIGINT, third.type());
    }

    @Test
    void shouldResolveQueryParametersInsideInExpressionInIndexOrder() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id IN ($3, $1, $2)"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(3, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("id", third.name());
        assertEquals(ColumnType.BIGINT, third.type());
    }

    @Test
    void shouldResolveQueryParametersFromCombinedExpressions() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id IN ($1, $2)
                  AND (active = $3 OR name = $4)
                """
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            statement,
            schema
        );

        assertEquals(4, model.parameters().size());

        QueryParameter first = model.parameters().getFirst();
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("active", third.name());
        assertEquals(ColumnType.BOOLEAN, third.type());

        QueryParameter fourth = model.parameters().get(3);
        assertEquals(4, fourth.index());
        assertEquals("name", fourth.name());
        assertEquals(ColumnType.VARCHAR, fourth.type());
    }

    @Test
    void shouldProduceExecutableSql() {
        String sql = """
            SELECT id, name
            FROM users
            WHERE id = $1
            """;

        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            schema
        );

        assertEquals(
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(1),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldResolveBindingIndexesInTextualOrder() {
        String sql = """
            SELECT id, name
            FROM users
            WHERE active = $2
              AND id = $1
            """;

        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            schema
        );

        assertEquals(
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                  AND id = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(2, 1),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldResolveQuotedIdentifiersWithoutDelimiters() {
        Schema quotedSchema = new Schema(
            List.of(
                new Table(
                    "user data",
                    List.of(
                        new Column("user id", ColumnType.BIGINT, false),
                        new Column("select", ColumnType.VARCHAR, true)
                    ),
                    List.of()
                )
            )
        );

        String sql = """
            SELECT "user id", "select"
            FROM "user data"
            WHERE "select" = $1
            """;

        Query query = new Query(
            "ListUserData",
            QueryType.MANY,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            quotedSchema
        );

        assertEquals("user data", model.table());

        assertEquals(
            List.of(
                new QueryColumn("user id", ColumnType.BIGINT, false),
                new QueryColumn("select", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "select", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(
            """
                SELECT "user id", "select"
                FROM "user data"
                WHERE "select" = ?
                """,
            model.executableSql()
        );
    }

    @Test
    void shouldResolveEmptyBindingIndexesWithoutParameters() {
        String sql = """
            SELECT id, name
            FROM users
            """;

        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            schema
        );

        assertTrue(model.bindingParameterIndexes().isEmpty());
    }

    @Test
    void shouldAnalyzeAliasedSingleTableSelect() {
        String sql = """
            SELECT u.id, u.name
            FROM users u
            WHERE u.name = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("GetUser", QueryType.ONE, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            model.parameters()
        );
    }

    @Test
    void shouldRejectTableNameHiddenByAlias() {
        String sql = """
            SELECT users.id
            FROM users u
            """;

        Query query = new Query("GetUser", QueryType.ONE, sql);
        Statement statement = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, statement, joinSchema)
        );

        assertTrue(exception.getMessage().contains("users"));
    }

    @Test
    void shouldAnalyzeSingleInnerJoin() {
        String sql = """
            SELECT u.id, p.id, p.nickname
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            WHERE u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("GetUserProfile", QueryType.ONE, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );
    }

    @Test
    void shouldAnalyzeExplicitInnerJoinWithMultipleSources() {
        String sql = """
            SELECT u.name, p.nickname, o.total
            FROM users u
            INNER JOIN profiles p ON p.user_id = u.id
            INNER JOIN orders o ON o.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldExpandAllColumnsAcrossJoinedSourcesInOrder() {
        String sql = """
            SELECT *
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldExpandQualifiedAllColumnsForOneSource() {
        String sql = """
            SELECT p.*, u.name
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldResolveUniqueUnqualifiedColumnInJoinedQuery() {
        String sql = """
            SELECT nickname, name
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            WHERE nickname = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListNicknames", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "nickname", ColumnType.VARCHAR)),
            model.parameters()
        );
    }

    @Test
    void shouldResolveJoinedParametersInTextualBindingOrder() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            WHERE p.nickname = $2
              AND u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());
    }

    @Test
    void shouldRejectAmbiguousUnqualifiedColumn() {
        String sql = """
            SELECT id
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        Statement statement = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, statement, joinSchema)
        );

        assertTrue(exception.getMessage().contains("id"));
    }

    @Test
    void shouldRejectUnknownColumnQualifier() {
        String sql = """
            SELECT o.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        Statement statement = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, statement, joinSchema)
        );

        assertTrue(exception.getMessage().contains("o"));
    }

    @Test
    void shouldRejectDuplicateExposedSourceName() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles U ON U.user_id = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        Statement statement = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, statement, joinSchema)
        );

        assertTrue(exception.getMessage().contains("U"));
    }

    /**
     * {@link net.sf.jsqlparser.statement.select.Join#isInnerJoin()} also
     * reports these shapes as inner joins, so the supported-shape gate must
     * reject them explicitly.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "FROM users u, profiles p",
            "FROM users u STRAIGHT_JOIN profiles p ON p.user_id = u.id"
        }
    )
    void shouldRejectExcludedJoinReportedAsInnerJoin(String fromClause) {
        String sql = """
            SELECT u.id
            %s
            """.formatted(fromClause);

        Query query = new Query("ListIds", QueryType.MANY, sql);
        Statement statement = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, statement, joinSchema)
        );
    }

    @Test
    void shouldRejectJoinWithoutSingleQualifiedEquality() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id AND p.nickname = u.name
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        Statement statement = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, statement, joinSchema)
        );
    }

    @Test
    void shouldRejectJoinConditionWithoutEarlierSource() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = p.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        Statement statement = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, statement, joinSchema)
        );
    }
}
