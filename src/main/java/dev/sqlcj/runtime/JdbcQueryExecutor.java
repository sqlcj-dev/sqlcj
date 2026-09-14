package dev.sqlcj.runtime;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class JdbcQueryExecutor implements QueryExecutor {

    private final DataSource dataSource;

    public JdbcQueryExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T query(
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
    ) {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            bindParameters(statement, parameters);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return mapper.map(resultSet);
            }
        } catch (SQLException e) {
            throw new QueryExecutionException(
                    "Failed to execute query",
                    e
            );
        }
    }

    @Override
    public <T> List<T> queryMany(
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
    ) {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            bindParameters(statement, parameters);

            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> results = new ArrayList<>();

                while (resultSet.next()) {
                    results.add(mapper.map(resultSet));
                }

                return results;
            }
        } catch (SQLException e) {
            throw new QueryExecutionException(
                    "Failed to execute query",
                    e
            );
        }
    }

    private void bindParameters(
            PreparedStatement statement,
            List<?> parameters
    ) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }
}
