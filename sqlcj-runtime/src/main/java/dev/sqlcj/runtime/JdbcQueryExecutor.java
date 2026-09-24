package dev.sqlcj.runtime;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes generated queries over JDBC.
 *
 * <p>An executor is constructed either with a {@link DataSource} or with a
 * caller-owned {@link Connection}. Both construction paths share the same
 * positional parameter binding, row mapping, cardinality enforcement,
 * affected-row counting, and exception translation. Only connection ownership
 * differs:
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
 * <p>{@link #queryOne} reads the first row, maps it, and then advances the
 * result set once more to prove that there is no second row.
 * {@link #queryOptional} does the same but reports no row as
 * {@link Optional#empty()}. A row count an annotation does not allow raises a
 * {@link QueryCardinalityException} naming the query and its repository; the
 * statement has already executed, so a returning write that fails the check has
 * already changed the database.
 *
 * <p>In both paths the {@link PreparedStatement} and any {@link ResultSet}
 * opened for an operation are closed before that operation returns, on success,
 * on a cardinality failure, and on any other failure.
 *
 * <p>Every {@link SQLException} raised while acquiring a connection, preparing
 * a statement, binding parameters, executing, reading results, or closing a
 * DataSource-acquired connection is translated into a
 * {@link QueryExecutionException} with the message {@code "Failed to execute
 * query '<query>' in <repository>"} and the {@code SQLException} as its cause.
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
    public <T> T queryOne(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    ) {
        return onStatement(repository, query, sql, parameters, statement -> {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new QueryCardinalityException(
                        cardinalityMessage(
                            repository,
                            query,
                            "returned no row; expected exactly one"
                        )
                    );
                }

                T row = mapper.map(resultSet);

                if (resultSet.next()) {
                    throw new QueryCardinalityException(
                        cardinalityMessage(
                            repository,
                            query,
                            "returned more than one row; expected exactly one"
                        )
                    );
                }

                return row;
            }
        });
    }

    @Override
    public <T> Optional<T> queryOptional(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    ) {
        return onStatement(repository, query, sql, parameters, statement -> {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                T row = mapper.map(resultSet);

                if (resultSet.next()) {
                    throw new QueryCardinalityException(
                        cardinalityMessage(
                            repository,
                            query,
                            "returned more than one row; expected at most one"
                        )
                    );
                }

                return Optional.of(row);
            }
        });
    }

    @Override
    public <T> List<T> queryMany(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    ) {
        return onStatement(repository, query, sql, parameters, statement -> {
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
    public int execute(String repository, String query, String sql, List<?> parameters) {
        return onStatement(repository, query, sql, parameters, PreparedStatement::executeUpdate);
    }

    /**
     * Runs the given operation on a prepared statement of the connection this
     * executor owns or was given, closing the statement afterward and closing
     * the connection only when this executor acquired it.
     */
    private <T> T onStatement(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        StatementOperation<T> operation
    ) {
        if (connection != null) {
            return onConnection(connection, repository, query, sql, parameters, operation);
        }

        try (Connection acquired = Objects.requireNonNull(dataSource).getConnection()) {
            return onConnection(acquired, repository, query, sql, parameters, operation);
        } catch (SQLException e) {
            throw new QueryExecutionException(
                failureMessage(repository, query),
                e
            );
        }
    }

    private <T> T onConnection(
        Connection target,
        String repository,
        String query,
        String sql,
        List<?> parameters,
        StatementOperation<T> operation
    ) {
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            bindParameters(statement, parameters);

            return operation.run(statement);
        } catch (SQLException e) {
            throw new QueryExecutionException(
                failureMessage(repository, query),
                e
            );
        }
    }

    private void bindParameters(PreparedStatement statement, List<?> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    private static String failureMessage(String repository, String query) {
        return "Failed to execute query '%s' in %s".formatted(query, repository);
    }

    private static String cardinalityMessage(String repository, String query, String outcome) {
        return "Query '%s' in %s %s".formatted(query, repository, outcome);
    }

    private interface StatementOperation<T> {

        T run(PreparedStatement statement) throws SQLException;
    }
}
