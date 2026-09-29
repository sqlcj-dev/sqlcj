package dev.sqlcj.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two directions of {@link SqlArray}: the elements it carries into a
 * binding, and the list it reads an array column back as. The read side runs
 * against an in-memory database, because it is defined by the JDBC
 * {@link java.sql.Array} API rather than by sqlcj.
 */
class SqlArrayTest {

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {

            statement.execute("""
                CREATE TABLE tagged (
                    id   BIGINT PRIMARY KEY,
                    tags VARCHAR ARRAY
                )
                """);
        }
    }

    /**
     * {@code of} converts every element that is not {@code null} and keeps a
     * {@code null} element and a {@code null} list as they are.
     */
    @Test
    void shouldConvertTheElementsOfAList() {
        assertEquals(
            new SqlArray("int4", Arrays.asList(1, null, 3)),
            SqlArray.of("int4", Arrays.asList("1", null, "3"), Integer::valueOf)
        );

        assertEquals(
            new SqlArray("int4", List.of()),
            SqlArray.<String>of("int4", List.of(), Integer::valueOf)
        );

        assertEquals(
            new SqlArray("int4", null),
            SqlArray.<String>of("int4", null, Integer::valueOf)
        );
    }

    /**
     * {@code getList} reads an array column as a list of its element type, in
     * array order: a SQL {@code NULL} column as {@code null}, an empty array as
     * an empty list, and a {@code NULL} element as a {@code null} element.
     */
    @Test
    void shouldReadArrayColumnsAsLists() throws SQLException {
        insert(1L, Arrays.asList("a", null, "b"));
        insert(2L, List.of());
        insert(3L, null);

        assertEquals(Arrays.asList("a", null, "b"), tags(1L));
        assertEquals(List.of(), tags(2L));
        assertNull(tags(3L));
    }

    /**
     * The converting read applies its converter to every element that is not
     * {@code null} and keeps a {@code null} element as {@code null}.
     */
    @Test
    void shouldConvertTheElementsOfAReadArrayColumn() throws SQLException {
        insert(1L, Arrays.asList("a", null, "b"));

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT tags FROM tagged WHERE id = 1"
            );
            ResultSet resultSet = statement.executeQuery()
        ) {

            assertTrue(resultSet.next());

            assertEquals(
                Arrays.asList("A", null, "B"),
                SqlArray.getList(resultSet, 1, String.class, String::toUpperCase)
            );
        }
    }

    private void insert(long id, List<String> tags) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tagged (id, tags) VALUES (?, ?)"
            )
        ) {

            statement.setObject(1, id);

            if (tags == null) {
                statement.setNull(2, Types.ARRAY);
            } else {
                statement.setArray(2, connection.createArrayOf("varchar", tags.toArray()));
            }

            statement.executeUpdate();
        }
    }

    private List<String> tags(long id) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT tags FROM tagged WHERE id = ?"
            )
        ) {

            statement.setObject(1, id);

            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());

                return SqlArray.getList(resultSet, 1, String.class);
            }
        }
    }
}
