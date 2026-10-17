package org.sqlcj.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SqlRow} against an in-memory database, because what it reports
 * is defined by what a driver reads for a SQL {@code NULL} rather than by
 * sqlcj. The read columns stand for one embedded row's range beside a column
 * selected outside it.
 */
class SqlRowTest {

    private static final String SELECT_ROW = "SELECT id, user_id, nickname, active FROM joined";

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
                CREATE TABLE joined (
                    id       BIGINT PRIMARY KEY,
                    user_id  BIGINT,
                    nickname VARCHAR,
                    active   BOOLEAN
                )
                """);
        }
    }

    /** A range every column of which reads SQL {@code NULL} is absent. */
    @Test
    void shouldReportAnAllNullRangeAsNull() throws SQLException {
        insert(1L, null, null, null);

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(SELECT_ROW);
            ResultSet resultSet = statement.executeQuery()
        ) {

            assertTrue(resultSet.next());

            assertTrue(SqlRow.isNull(resultSet, 2, 4));
        }
    }

    /**
     * A range one column of which reads a value is present, whichever column of
     * the range that is.
     */
    @Test
    void shouldReportAPartlyNullRangeAsPresent() throws SQLException {
        insert(1L, null, "ada", null);

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(SELECT_ROW);
            ResultSet resultSet = statement.executeQuery()
        ) {

            assertTrue(resultSet.next());

            assertFalse(SqlRow.isNull(resultSet, 2, 4));
        }
    }

    /**
     * The report covers the given range alone, so the non-null column selected
     * next to it neither makes the range present nor is read as part of it.
     */
    @Test
    void shouldReadTheGivenRangeAlone() throws SQLException {
        insert(7L, null, null, null);

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(SELECT_ROW);
            ResultSet resultSet = statement.executeQuery()
        ) {

            assertTrue(resultSet.next());

            assertTrue(SqlRow.isNull(resultSet, 2, 4));
            assertFalse(SqlRow.isNull(resultSet, 1, 1));
            assertFalse(SqlRow.isNull(resultSet, 1, 4));
        }
    }

    private void insert(
        long id,
        Long userId,
        String nickname,
        Boolean active
    ) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO joined (id, user_id, nickname, active) VALUES (?, ?, ?, ?)"
            )
        ) {

            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.setObject(3, nickname);
            statement.setObject(4, active);

            statement.executeUpdate();
        }
    }
}
