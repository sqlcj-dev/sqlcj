package dev.sqlcj.runtime;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes generated queries over JDBC.
 *
 * <p>An executor is constructed either with a {@link DataSource} or with a
 * caller-owned {@link Connection}. Both construction paths share the same
 * positional parameter binding, row mapping, single-row and multi-row result
 * handling, affected-row counting, and exception translation. Only connection
 * ownership differs:
 *
 * <ul>
 * <li>{@link #JdbcQueryExecutor(DataSource)} obtains one connection per
 * operation and closes it before the operation returns. Each operation
 * therefore runs on that connection's own transaction state, typically one
 * auto-committed statement.</li>
 * <li>{@link #JdbcQueryExecutor(Connection)} runs every operation on the
 * supplied connection and never closes, commits, or rolls it back, never
 * changes its auto-commit setting, and never otherwise configures it. The
 * application alone controls the connection's lifetime and transaction, so
 * several generated operations can take part in one application-controlled
 * commit or rollback.</li>
 * </ul>
 *
 * <p>In both paths the {@link PreparedStatement} and any {@link ResultSet}
 * opened for an operation are closed before that operation returns, on success
 * and on failure.
 *
 * <p>Every {@link SQLException} raised while acquiring a connection, preparing
 * a statement, binding parameters, executing, reading results, or closing a
 * DataSource-acquired connection is translated into a
 * {@link QueryExecutionException} with the message {@code "Failed to execute
 * query"} and the {@code SQLException} as its cause.
 *
 * <p>This executor is blocking and synchronous. An instance constructed with a
 * caller-owned connection inherits that connection's confinement to a single
 * thread at a time.
 */
public final class JdbcQueryExecutor implements QueryExecutor {

    private final DataSource dataSource;

    private final Connection connection;

    /**
     * Creates an executor that acquires and closes one connection from the
     * given {@code dataSource} per operation.
     */
    public JdbcQueryExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
        this.connection = null;
    }

    /**
     * Creates an executor that runs every operation on the given caller-owned
     * {@code connection}. The connection is never closed, committed, rolled
     * back, or reconfigured by this executor.
     */
    public JdbcQueryExecutor(Connection connection) {
        this.dataSource = null;
        this.connection = connection;
    }

    @Override
    public <T> T query(String sql, List<?> parameters, RowMapper<T> mapper) {
        return onStatement(sql, parameters, statement -> {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return mapper.map(resultSet);
            }
        });
    }

    @Override
    public <T> List<T> queryMany(String sql, List<?> parameters, RowMapper<T> mapper) {
        return onStatement(sql, parameters, statement -> {
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> results = new ArrayList<>();

                while (resultSet.next()) {
                    results.add(mapper.map(resultSet));
                }

                return results;
            }
        });
    }

    @Override
    public int execute(String sql, List<?> parameters) {
        return onStatement(sql, parameters, PreparedStatement::executeUpdate);
    }

    /**
     * Runs the given operation on a prepared statement of the connection this
     * executor owns or was given, closing the statement afterward and closing
     * the connection only when this executor acquired it.
     */
    private <T> T onStatement(
        String sql,
        List<?> parameters,
        StatementOperation<T> operation
    ) {
        if (connection != null) {
            return onConnection(connection, sql, parameters, operation);
        }

        try (Connection acquired = Objects.requireNonNull(dataSource).getConnection()) {
            return onConnection(acquired, sql, parameters, operation);
        } catch (SQLException e) {
            throw new QueryExecutionException(
                "Failed to execute query",
                e
            );
        }
    }

    private <T> T onConnection(
        Connection target,
        String sql,
        List<?> parameters,
        StatementOperation<T> operation
    ) {
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            bindParameters(statement, parameters);

            return operation.run(statement);
        } catch (SQLException e) {
            throw new QueryExecutionException(
                "Failed to execute query",
                e
            );
        }
    }

    private void bindParameters(PreparedStatement statement, List<?> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    private interface StatementOperation<T> {

        T run(PreparedStatement statement) throws SQLException;
    }
}
